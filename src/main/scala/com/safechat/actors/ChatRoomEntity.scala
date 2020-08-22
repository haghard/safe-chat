// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat.actors

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.TimeZone

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed._
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import akka.persistence.typed._
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, RestartFlow, Source, StreamRefs}
import akka.stream.{KillSwitches, StreamRefAttributes}
import akka.util.Timeout

import scala.concurrent.duration._

object ChatRoomEntity {

  val snapshotEveryN   = 300       //TODO should be configurable
  val hubInitTimeout   = 3.seconds //TODO should be configurable
  val writeParallelism = 1

  val MSG_SEP = ":"

  val wakeUpUserName   = "John Doe"
  val wakeUpEntityName = "dungeon"
  val frmtr            = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")

  val entityKey: EntityTypeKey[UserCmdWithReply] =
    EntityTypeKey[UserCmdWithReply]("chat-rooms")

  val empty = com.safechat.actors.ChatRoomState()

  def persist(persistenceId: String, entity: ActorRef[PostText])(implicit
    persistTimeout: Timeout
  ): Flow[Message, ChatRoomReply, akka.NotUsed] = {
    def persistFlow =
      akka.stream.typed.scaladsl.ActorFlow.ask[Message, PostText, ChatRoomReply](writeParallelism)(entity) {
        (msg: Message, replyTo: ActorRef[ChatRoomReply]) ⇒
          msg match {
            case TextMessage.Strict(text) ⇒
              val segments = text.split(MSG_SEP)
              if (text.split(MSG_SEP).size == 3)
                PostText(persistenceId, segments(0).trim, segments(1).trim, segments(2).trim, replyTo)
              else
                PostText(persistenceId, "null", "null", s"Message error. Wrong format $text", replyTo)
            case other ⇒
              throw new Exception(s"Unexpected message type ${other.getClass.getName}")
          }
      }

    //TODO: maybe would be better to fail instead of retrying
    RestartFlow.withBackoff(1.second, 10.seconds, 0.3)(() ⇒ persistFlow)
  }

  /**
    * Each `ChatRoomEntity` actor is a single source of true, acting as a consistency boundary for the data that is manages.
    *
    * Messages in a single chat define a single total order.
    *
    * Messages in a single chat causally dependent on each other by design.
    *
    * Causal consistency is maintained as we always write to the journal with parallelism == 1
    */
  def apply(entityCtx: EntityContext[UserCmdWithReply]): Behavior[UserCmdWithReply] =
    //com.safechat.LoggingBehaviorInterceptor(ctx.log) {
    Behaviors.setup { ctx ⇒
      implicit val sys      = ctx.system
      implicit val actorCtx = ctx

      //fp style
      /*EventSourcedBehavior.withEnforcedReplies[UserCmd, ChatRoomEvent, ChatRoomState](
        PersistenceId(entityCtx.entityTypeKey.name, entityCtx.entityId),
        empty,
        (state, cmd) ⇒ state.applyCmd(cmd),
        (state, event) ⇒ state.applyEvn(event)
      )*/

      EventSourcedBehavior
        .withEnforcedReplies[UserCmdWithReply, ChatRoomEvent, ChatRoomState](
          //PersistenceId.ofUniqueId(entityId),
          PersistenceId(entityCtx.entityTypeKey.name, entityCtx.entityId),
          ChatRoomState(),
          onCommand(ctx),
          onEvent(ctx.self.path.name)
        )
        /*.withTagger {
          //tagged events are useful for querying  by tag
          case m: MsgEnvelope if m.getPayload.isInstanceOf[Joined] ⇒ Set("user")
        }*/
        .receiveSignal {
          case (state, RecoveryCompleted) ⇒
            ctx.log.info(s"★ ★ ★ Recovered: [${state.regUsers.keySet.mkString(",")}] ★ ★ ★")
          case (state, PreRestart) ⇒
            ctx.log.info(s"★ ★ ★ Pre-restart ${state.regUsers.keySet.mkString(",")} ★ ★ ★")
          case (state, PostStop) ⇒
            state.hub.foreach(_.ks.shutdown)
            ctx.log.info("★ ★ ★ PostStop(Passivation). Clean up chat resources ★ ★ ★")
          case (state, SnapshotCompleted(_)) ⇒
            ctx.log.info(s"SnapshotCompleted [${state.regUsers.keySet.mkString(",")}]")
          case (state, SnapshotFailed(_, ex)) ⇒
            ctx.log.error(s"SnapshotFailed ${state.regUsers.keySet.mkString(",")}", ex)
          case (_, RecoveryFailed(cause)) ⇒
            ctx.log.error(s"RecoveryFailed $cause", cause)
          case (_, signal) ⇒
            ctx.log.info(s"★ ★ ★ Signal $signal ★ ★ ★")
        }
        /*.snapshotWhen {
          case (_, UserJoined(_, _), _) ⇒ true
          case _                        ⇒ false
        }*/
        .snapshotWhen(snapshotPredicate(ctx))
        //save a snapshot on every 100 events and keep max 2
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = snapshotEveryN, keepNSnapshots = 2))
        .onPersistFailure(
          SupervisorStrategy
            .restartWithBackoff(minBackoff = 2.seconds, maxBackoff = 20.seconds, randomFactor = 0.3)
            .withMaxRestarts(100)
        )
    }

  /**
    * Each chat root contains MergeHub and BroadcastHub connected together to form a runnable graph.
    * Once we materialize this stream, we get back a pair of Source and Sink that together define the publish and subscribe sides of our chat room.
    *
    * Dynamic fan-in and fan-out with MergeHub and BroadcastHub (https://doc.akka.io/docs/akka/current/stream/stream-dynamic.html#combining-dynamic-operators-to-build-a-simple-publish-subscribe-service)
    *
    * A MergeHub allows to implement a dynamic fan-in junction point(many-to-one) in a graph where elements coming from
    * different producers are emitted in a First-Comes-First-Served fashion.
    * If the consumer cannot keep up, then all of the producers will be backpressured.
    *
    * A BroadcastHub can be used to consume elements from a common producer by a dynamic set of consumers (one-to-many).
    * (dynamic number of producers and new consumers can be added on the fly)
    * The rate of the producer will be automatically adapted to the slowest consumer.
    */
  def createPubSub(
    persistenceId: String,
    entity: ActorRef[PostText]
  )(implicit
    sys: ActorSystem[Nothing]
  ): ChatRoomPubSub = {
    implicit val ec = sys.executionContext

    val initBs = sys.settings.config.getInt("akka.stream.materializer.initial-input-buffer-size")
    //val bs1 = sys.settings.config.getInt("akka.stream.materializer.max-input-buffer-size")

    sys.log.warn("Create pub-sub for {} chatroom", persistenceId)

    val ((sinkHub, ks), sourceHub) =
      MergeHub
        .source[Message](initBs)
        .via(
          //WsScaffolding.flowWithHeartbeat(30.second).via(persist(persistenceId, entity))
          persist(persistenceId, entity)(akka.util.Timeout(1.second))
            .collect { case r: TextPostedReply ⇒ TextMessage.Strict(s"${r.chatId}:${r.seqNum} - ${r.content}") }
        )
        /*
        .via(
          WsScaffolding
            .flowWithHeartbeat()
            .mapAsync(1) { //to preserve the real time ordering
              case TextMessage.Strict(text) ⇒
                //message pattern alice:bob:......message body....
                val segments = text.split(":")
                if (segments.size == 3) {
                  entity
                    .ask[ChatRoomReply](
                      PostText(persistenceId, segments(0).trim, segments(1).trim, segments(2).trim, _)
                    )
                    .collect { case r: TextPostedReply ⇒ TextMessage.Strict(s"${r.chatId}:${r.seqNum}") }
                } else if (text eq WsScaffolding.hbMessage)
                  Future.successful(TextMessage.Strict(s"$persistenceId:ping"))
                else Future.successful(TextMessage.Strict("Invalid msg format"))
              case other ⇒
                throw new Exception(s"Unexpected message type ${other.getClass.getName}")
            }
        )*/
        .viaMat(KillSwitches.single)(Keep.both)
        .toMat(BroadcastHub.sink[Message](initBs))(Keep.both)
        .run()

    ChatRoomPubSub(sinkHub, sourceHub, ks)
  }

  def onCommand(ctx: ActorContext[UserCmdWithReply])(state: ChatRoomState, cmd: UserCmdWithReply)(implicit
    sys: ActorSystem[Nothing]
  ): ReplyEffect[ChatRoomEvent, ChatRoomState] =
    cmd match {
      case cmd: JoinUser ⇒
        Effect
          .persist(UserJoined(cmd.user, cmd.pubKey))
          .thenReply(cmd.replyTo) { updateState: ChatRoomState ⇒ //That's new state after applying the event
            //ctx.log.info("JoinUser attempt {}", m.user)

            val settings =
              StreamRefAttributes.subscriptionTimeout(hubInitTimeout) //.and(akka.stream.Attributes.inputBuffer(bs, bs))

            updateState.hub
              .map { hub ⇒
                val chatHistory = updateState.recentHistory.entries.mkString("\n")

                //Add new producer on the fly
                //If the consumer cannot keep up then all of the producers are backpressured
                val srcRefF = (Source.single[Message](TextMessage(chatHistory)) ++ hub.srcHub)
                  .runWith(StreamRefs.sourceRef[Message].addAttributes(settings))

                //Add new consumers on the fly
                //The rate of the producer will be automatically adapted to the slowest consumer
                val sinkRefF = hub.sinkHub.runWith(StreamRefs.sinkRef[Message].addAttributes(settings))
                JoinReply(cmd.chatId, cmd.user, sinkRefF, srcRefF)
              }
              .getOrElse(JoinReplyFailure(cmd.chatId, cmd.user))
          }

      case cmd: PostText ⇒
        val num = EventSourcedBehavior.lastSequenceNumber(ctx)
        Effect
          .persist(
            UserTextAdded(cmd.sender, cmd.receiver, cmd.content, System.currentTimeMillis, TimeZone.getDefault.getID)
          )
          .thenReply(cmd.replyTo) { updatedState: ChatRoomState ⇒
            ctx.log.info("online:[{}]", updatedState.online.mkString(","))
            TextPostedReply(cmd.chatId, num, s"[from:${cmd.sender} -> to:${cmd.receiver}] - ${cmd.content}")
          }

      case cmd: DisconnectUser ⇒
        Effect
          .persist(UserDisconnected(cmd.user))
          .thenReply(cmd.replyTo) { updatedState: ChatRoomState ⇒
            ctx.log.info("{} disconnected - online:[{}]", cmd.user, updatedState.online.mkString(""))
            DisconnectedReply(cmd.chatId, cmd.user)
          }
    }

  def onEvent(persistenceId: String)(state: ChatRoomState, event: ChatRoomEvent)(implicit
    sys: ActorSystem[Nothing],
    ctx: ActorContext[UserCmdWithReply]
  ): ChatRoomState =
    event match {
      case UserJoined(login, pubKey) ⇒
        if (state.online.isEmpty && state.hub.isEmpty)
          if (login == ChatRoomEntity.wakeUpUserName)
            state
          else
            state.copy(
              regUsers = state.regUsers + (login → pubKey),
              online = Set(login),
              hub = Some(createPubSub(persistenceId, ctx.self.narrow[PostText]))
            )
        else
          state.copy(
            regUsers = state.regUsers + (login → pubKey),
            online = state.online + login
          )
      case UserTextAdded(originator, receiver, content, when, tz) ⇒
        val zoneDT = ZonedDateTime.ofInstant(Instant.ofEpochMilli(when), ZoneId.of(tz))
        state.recentHistory.add(s"[${frmtr.format(zoneDT)}] - $originator -> $receiver:$content")
        state
      case UserDisconnected(login) ⇒
        state.copy(online = state.online - login)
    }

  def snapshotPredicate(
    ctx: ActorContext[UserCmdWithReply]
  )(state: ChatRoomState, event: ChatRoomEvent, id: Long): Boolean = {
    val ifSnap = id > 0 && id % snapshotEveryN == 0

    if (ifSnap)
      ctx.log.info(s"Snapshot {}", id)

    ifSnap
  }
}
