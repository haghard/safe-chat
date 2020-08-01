// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat.actors

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.{TimeZone, UUID}

import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop, PreRestart, SupervisorStrategy}

import scala.concurrent.duration._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted, RecoveryFailed, SnapshotCompleted, SnapshotFailed}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import akka.stream.{KillSwitches, StreamRefAttributes}
import com.safechat.domain.{Disconnected, Joined, MsgEnvelope, TextAdded}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, Source, StreamRefs}
import akka.util.Timeout
import com.safechat.rest.WsScaffolding

object ChatRoomEntity {

  val snapshotEveryN = 100       //TODO should be configurable
  val hubInitTimeout = 2.seconds //TODO should be configurable

  val wakeUpUserName   = "John Doe"
  val wakeUpEntityName = "dungeon"
  val frmtr            = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")

  val entityKey: EntityTypeKey[UserCmdWithReply] =
    EntityTypeKey[UserCmdWithReply]("chat-rooms")

  def empty = ChatRoomState()

  def persist(persistenceId: String, entity: ActorRef[PostText])(implicit
    writeTo: Timeout
  ): Flow[Message, ChatRoomReply, akka.NotUsed] =
    akka.stream.typed.scaladsl.ActorFlow.ask[Message, PostText, ChatRoomReply](1)(entity) {
      (msg: Message, replyTo: akka.actor.typed.ActorRef[ChatRoomReply]) ⇒
        msg match {
          case TextMessage.Strict(text) ⇒
            val segments = text.split(":")
            if (text.split(":").size == 3)
              PostText(persistenceId, segments(0).trim, segments(1).trim, segments(2).trim, replyTo)
            else if (text eq WsScaffolding.hbMessage)
              PostText(persistenceId, text, text, text, replyTo)
            else PostText(persistenceId, "null", "null", s"Message error. Wrong format $text", replyTo)
          //throw new Exception(s"Unexpected text message $text")
          case other ⇒
            throw new Exception(s"Unexpected message type ${other.getClass.getName}")
        }
    }

  /**
    * Each `ChatRoomEntity` actor is a single source of true, acting as a consistency boundary for the data that is manages.
    */
  def apply(entityCtx: EntityContext[UserCmdWithReply]): Behavior[UserCmdWithReply] =
    //com.safechat.LoggingBehaviorInterceptor(ctx.log) {
    Behaviors.setup { ctx ⇒
      implicit val sys      = ctx.system
      implicit val actorCtx = ctx

      //fp style
      /*EventSourcedBehavior.withEnforcedReplies[UserCmd, MsgEnvelope, FullChatState](
        PersistenceId(entityCtx.entityTypeKey.name, entityCtx.entityId),
        empty,
        (state, cmd) ⇒ state.applyCmd(cmd),
        (state, event) ⇒ state.applyEvn(event)
      )*/

      EventSourcedBehavior
        .withEnforcedReplies[UserCmdWithReply, MsgEnvelope, ChatRoomState](
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
          case (_, env, _) if env.getPayload.isInstanceOf[JoinUser] ⇒ true
          case _                                                    ⇒ false
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
    * Dynamic fan-in and fan-out with MergeHub and BroadcastHub (//https://doc.akka.io/docs/akka/current/stream/stream-dynamic.html#combining-dynamic-operators-to-build-a-simple-publish-subscribe-service)
    *
    * A MergeHub allows to implement a dynamic fan-in junction point(many-to-one) in a graph where elements coming from
    * different producers are emitted in a First-Comes-First-Served fashion.
    * If the consumer cannot keep up then all of the producers are backpressured.
    *
    * A BroadcastHub can be used to consume elements from a common producer by a dynamic set of consumers (one-to-many).
    * (dynamic number of producers and new consumers can be added on the fly)
    * The rate of the producer will be automatically adapted to the slowest consumer. In this case, the hub is a Sink to
    * which the single producer must be attached first
    */
  def createHub(
    persistenceId: String,
    entity: ActorRef[PostText]
  )(implicit
    sys: ActorSystem[Nothing]
  ): ChatRoomHub = {
    implicit val ec    = sys.executionContext
    implicit val askTo = akka.util.Timeout(1.second) //persist timeout

    sys.log.warn("Hub for {}", persistenceId)
    val ((sinkHub, ks), sourceHub) =
      MergeHub
        .source[Message](sys.settings.config.getInt("akka.stream.materializer.max-input-buffer-size"))
        .via(
          WsScaffolding
            .flowWithHeartbeat(30.second)
            .via(persist(persistenceId, entity))
            .collect {
              case r: PingReply       ⇒ TextMessage.Strict(s"${r.chatId}:${r.msg}")
              case r: TextPostedReply ⇒ TextMessage.Strict(s"chat-room:${r.chatId} msgId:${r.seqNum}  ${r.content}")
            }
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
        .toMat(BroadcastHub.sink[Message](1 << 3))(Keep.both)
        .run()
    ChatRoomHub(sinkHub, sourceHub, ks)
  }

  def onCommand(ctx: ActorContext[UserCmdWithReply])(state: ChatRoomState, cmd: UserCmdWithReply)(implicit
    sys: ActorSystem[Nothing]
  ): ReplyEffect[MsgEnvelope, ChatRoomState] =
    cmd match {
      case m: JoinUser ⇒
        Effect
          .persist(
            new MsgEnvelope(
              UUID.randomUUID.toString,
              System.currentTimeMillis,
              TimeZone.getDefault.getID,
              Joined.newBuilder.setLogin(m.user).setPubKey(m.pubKey).build()
            )
          ) //Note that the new state after applying the event is passed as parameter to the thenReply function
          .thenReply(m.replyTo) { state: ChatRoomState ⇒
            //ctx.log.info("JoinUser attempt {}", m.user)

            val settings =
              StreamRefAttributes.subscriptionTimeout(hubInitTimeout) //.and(akka.stream.Attributes.inputBuffer(bs, bs))

            state.hub
              .map { hub ⇒
                val chatHistory = state.recentHistory.entries.mkString("\n")

                //Add new producer on the fly
                //If the consumer cannot keep up then all of the producers are backpressured
                val srcRefF = (Source.single[Message](TextMessage(chatHistory)) ++ hub.srcHub)
                  .runWith(StreamRefs.sourceRef[Message].addAttributes(settings))

                //Add new consumers on the fly
                //The rate of the producer will be automatically adapted to the slowest consumer
                val sinkRefF = hub.sinkHub.runWith(StreamRefs.sinkRef[Message].addAttributes(settings))
                JoinReply(m.chatId, m.user, sinkRefF, srcRefF)
              }
              .getOrElse(JoinReplyFailure(m.chatId, m.user))
          }

      case cmd: PostText ⇒
        val num = EventSourcedBehavior.lastSequenceNumber(ctx)
        if (cmd.text eq WsScaffolding.hbMessage)
          Effect.none.thenReply(cmd.replyTo)(_ ⇒ PingReply(cmd.chatId, cmd.text))
        else
          Effect
            .persist(
              new MsgEnvelope(
                UUID.randomUUID.toString,
                System.currentTimeMillis,
                TimeZone.getDefault.getID,
                new TextAdded(cmd.sender, cmd.receiver, cmd.text)
              )
            )
            .thenReply(cmd.replyTo) { newState: ChatRoomState ⇒
              ctx.log.info("users online:[{}]", newState.online.mkString(","))
              TextPostedReply(cmd.chatId, num, s"[from:${cmd.sender} -> to:${cmd.receiver}] - ${cmd.text}")
            }

      case cmd: DisconnectUser ⇒
        Effect
          .persist(
            new MsgEnvelope(
              UUID.randomUUID.toString,
              System.currentTimeMillis,
              TimeZone.getDefault.getID,
              Disconnected.newBuilder.setLogin(cmd.user).build
            )
          )
          .thenReply(cmd.replyTo) { newState: ChatRoomState ⇒
            ctx.log.info("{} disconnected - online:[{}]", cmd.user, newState.online.mkString(""))
            DisconnectedReply(cmd.chatId, cmd.user)
          }
    }

  def onEvent(persistenceId: String)(state: ChatRoomState, event: MsgEnvelope)(implicit
    sys: ActorSystem[Nothing],
    ctx: ActorContext[UserCmdWithReply]
  ): ChatRoomState =
    //sys.log.warn("onEvent: {}", event.getPayload)
    if (event.getPayload.isInstanceOf[Joined]) {
      val ev = event.getPayload.asInstanceOf[Joined]

      //Hub hasn't been created yet
      if (state.online.isEmpty && state.hub.isEmpty)
        if (ev.getLogin.toString == ChatRoomEntity.wakeUpUserName)
          state
        else
          state.copy(
            regUsers = state.regUsers + (ev.getLogin.toString → ev.getPubKey.toString),
            online = Set(ev.getLogin.toString),
            hub = Some(createHub(persistenceId, ctx.self.narrow[PostText]))
          )
      else
        state.copy(
          regUsers = state.regUsers + (ev.getLogin.toString → ev.getPubKey.toString),
          online = state.online + ev.getLogin.toString
        )

    } else if (event.getPayload.isInstanceOf[com.safechat.domain.Disconnected])
      state.copy(
        online = state.online - event.getPayload
            .asInstanceOf[com.safechat.domain.Disconnected]
            .getLogin
            .toString
      )
    else if (event.getPayload.isInstanceOf[TextAdded]) {
      val ev     = event.getPayload.asInstanceOf[TextAdded]
      val zoneDT = ZonedDateTime.ofInstant(Instant.ofEpochMilli(event.getWhen), ZoneId.of(event.getTz.toString))
      state.recentHistory.add(s"[${frmtr.format(zoneDT)}] - ${ev.getUser} -> ${ev.getReceiver}:${ev.getText}")
      state
    } else
      state

  def snapshotPredicate(
    ctx: ActorContext[UserCmdWithReply]
  )(state: ChatRoomState, event: MsgEnvelope, id: Long): Boolean = {
    val ifSnap = id > 0 && id % snapshotEveryN == 0

    if (ifSnap)
      ctx.log.info(s"Snapshot {}", id)

    ifSnap
  }
}
