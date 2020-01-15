// Copyright (c) 2019 Vadim Bondarev. All rights reserved.

package com.safechat.actors

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.{TimeZone, UUID}

import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop, PreRestart, SupervisorStrategy}

import scala.concurrent.duration._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.{PersistenceId, RecoveryCompleted, RecoveryFailed, SnapshotCompleted, SnapshotFailed}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import akka.stream.{KillSwitches, StreamRefAttributes}
import com.safechat.domain.{Disconnected, Joined, MsgEnvelope, TextAdded}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{BroadcastHub, Keep, MergeHub, Source, StreamRefs}

import scala.concurrent.Future
import akka.actor.typed.scaladsl.AskPattern._
import com.safechat.rest.WsScaffolding

object ChatRoomEntity {

  val snapshotEveryN = 100       //TODO should be configurable
  val hubInitTimeout = 5.seconds //TODO should be configurable

  val wakeUpUserName   = "John Doe"
  val wakeUpEntityName = "dungeon"
  val frmtr            = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")

  val entityKey: EntityTypeKey[UserCmd] =
    EntityTypeKey[UserCmd]("chat-rooms")

  def empty = FullChatState()

  def apply(entityId: String): Behavior[UserCmd] =
    Behaviors.setup { ctx ⇒
      //com.safechat.LoggingBehaviorInterceptor(ctx.log) {
      implicit val sys = ctx.system

      /*EventSourcedBehavior.withEnforcedReplies[UserCmd, MsgEnvelope, FullChatState](
        PersistenceId("chat-room", entityId),
        empty,
        (state, cmd) ⇒ state.applyCmd(cmd),
        (state, event) ⇒ state.applyEvn(event)
      )*/

      EventSourcedBehavior
        .withEnforcedReplies[UserCmd, MsgEnvelope, FullChatState](
          PersistenceId("chat-room", entityId),
          FullChatState(),
          onCommand(ctx),
          onEvent(ctx, ctx.self.path.name)
        )
        .receiveSignal {
          case (state, RecoveryCompleted) ⇒
            ctx.log.info(s"Recovered: [${state.regUsers.keySet.mkString(",")}]")
          case (state, PreRestart) ⇒
            ctx.log.info(s"Pre-restart ${state.regUsers.keySet.mkString(",")}")
          case (state, PostStop) ⇒
            state.hub.foreach(_.ks.shutdown)
            ctx.log.info("PostStop. Clean up chat resources")
          case (state, SnapshotCompleted(_)) ⇒
            ctx.log.info(s"SnapshotCompleted [${state.regUsers.keySet.mkString(",")}]")
          case (state, SnapshotFailed(_, ex)) ⇒
            ctx.log.error(s"SnapshotFailed ${state.regUsers.keySet.mkString(",")}", ex)
          case (_, RecoveryFailed(cause)) ⇒
            ctx.log.error(s"RecoveryFailed $cause", cause)
          case (_, signal) ⇒
            ctx.log.info(s"Signal $signal")
        }
        .snapshotWhen(snapshotPredicate(ctx))
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = snapshotEveryN, keepNSnapshots = 2)) //.withDeleteEventsOnSnapshot
        .onPersistFailure(
          SupervisorStrategy.restartWithBackoff(minBackoff = 2.seconds, maxBackoff = 20.seconds, randomFactor = 0.3)
        )
      //}
    }

  /**
    *
    * Dynamic fan-in and fan-out with MergeHub and BroadcastHub
    *
    * A MergeHub allows to implement a dynamic fan-in junction point(many-to-one) in a graph where elements coming from
    * different producers are emitted in a First-Comes-First-Served fashion.
    *
    * A BroadcastHub can be used to consume elements from a common producer by a dynamic set of consumers (one-to-many).
    * (dynamic number of producers and new consumers can be added on the fly)
    */
  def createHub(
    persistenceId: String,
    ctx: ActorRef[PostText]
  )(
    implicit sys: ActorSystem[Nothing]
  ): ChatRoomHub = {
    implicit val ec = sys.executionContext
    implicit val t  = akka.util.Timeout(1.second)
    /*
      Try it out
      case class Persisted(num: Long)
      case class PersistElement(num: Long, srcSender: akka.actor.typed.ActorRef[Persisted])

      def typeAsk: Source[Persisted, akka.NotUsed] = {
        import akka.actor.typed.scaladsl.adapter._
        import akka.actor.typed.scaladsl.Behaviors

        implicit val t = akka.util.Timeout(1.second)

        val dbRef: akka.actor.typed.ActorRef[PersistElement] =
          sys.spawn(
            Behaviors.receiveMessage[PersistElement] {
              case PersistElement(num, srcSender) ⇒
                println(num)
                Thread.sleep(500) //write operation
                //confirm the element
                srcSender.tell(Persisted(num))
                Behaviors.same
            },
            "actor-in-the-middle"
          )

        Source
          .repeat(42L)
          .via(
            akka.stream.typed.scaladsl.ActorFlow.ask[Long, PersistElement, Persisted](dbRef)(
              (elem: Long, srcSender: akka.actor.typed.ActorRef[Persisted]) ⇒ PersistElement(elem, srcSender)
            )
          )
          .take(100)
      }
    */

    //ctx.log.info("create hub for {}", persistenceId)
    val ((sinkHub, ks), sourceHub) =
      MergeHub
        .source[Message](sys.settings.config.getInt("akka.stream.materializer.max-input-buffer-size"))
        .via(
          WsScaffolding
            .flowWithHeartbeat()
            .mapAsync(1) { //to preserve the real time ordering
              case TextMessage.Strict(text) ⇒
                //message pattern alice:bob:......message body....
                val segments = text.split(":")
                if (segments.size == 3) {
                  ctx
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
        )
        .viaMat(KillSwitches.single)(Keep.both)
        .toMat(BroadcastHub.sink[Message](1))(Keep.both) //??? sys.settings.config.getInt("akka.stream.materializer.max-input-buffer-size")
        .run()
    ChatRoomHub(sinkHub, sourceHub, ks)
  }

  def onCommand(ctx: ActorContext[UserCmd])(state: FullChatState, cmd: UserCmd)(
    implicit sys: ActorSystem[Nothing]
  ): ReplyEffect[MsgEnvelope, FullChatState] =
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
          .thenReply(m.replyTo) { state: FullChatState ⇒
            //val settings = StreamRefAttributes.subscriptionTimeout(hubInitTimeout).and(akka.stream.Attributes.inputBuffer(bs, bs))
            state.hub
              .map { hub ⇒
                val history = state.recentHistory.entries.mkString("\n")
                //val userKeys = newState.regUsers.filter(_._1 != m.user).map { case (k, v) ⇒ s"$k:$v" }.mkString("\n")

                //Add new producer on the fly
                //If the consumer cannot keep up then all of the producers are backpressured
                val srcRefF = (Source.single[Message](TextMessage(history)) ++ hub.srcHub)
                  .runWith(StreamRefs.sourceRef[Message]) /*.addAttributes(settings)*/

                //Add new consumers on the fly
                //The rate of the producer will be automatically adapted to the slowest consumer
                val sinkRefF = hub.sinkHub.runWith(StreamRefs.sinkRef[Message] /*.addAttributes(settings)*/ )
                JoinReply(m.chatId, m.user, sinkRefF, srcRefF)
              }
              .getOrElse(JoinReplyFailure(m.chatId, m.user))
          }

      case cmd: PostText ⇒
        val num = EventSourcedBehavior.lastSequenceNumber(ctx)
        Effect
          .persist(
            new MsgEnvelope(
              UUID.randomUUID.toString,
              System.currentTimeMillis,
              TimeZone.getDefault.getID,
              new TextAdded(cmd.sender, cmd.receiver, cmd.text)
            )
          )
          .thenReply(cmd.replyTo) { newState: FullChatState ⇒
            ctx.log.info("[{}]: users online:[{}]", newState.online.mkString(","))
            TextPostedReply(cmd.chatId, num)
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
          .thenReply(cmd.replyTo) { newState: FullChatState ⇒
            ctx.log.info("{} disconnected - online:[{}]", cmd.user, newState.online.mkString(""))
            DisconnectReply(cmd.chatId, cmd.user)
          }
    }

  def onEvent(ctx: ActorContext[UserCmd], persistenceId: String)(state: FullChatState, event: MsgEnvelope)(
    implicit sys: ActorSystem[Nothing]
  ): FullChatState =
    if (event.getPayload.isInstanceOf[Joined]) {
      val ev = event.getPayload.asInstanceOf[Joined]
      if (state.online.isEmpty && state.hub.isEmpty)
        if (ev.getLogin == ChatRoomEntity.wakeUpUserName)
          state
        else
          state.copy(
            regUsers = state.regUsers + (ev.getLogin.toString → ev.getPubKey.toString),
            hub = Some(createHub(persistenceId, ctx.self.narrow[PostText])),
            online = Set(ev.getLogin.toString)
          )
      else
        state.copy(
          regUsers = state.regUsers + (ev.getLogin.toString → ev.getPubKey.toString),
          online = state.online + ev.getLogin.toString
        )

    } else if (event.getPayload.isInstanceOf[com.safechat.domain.Disconnected]) {
      state.copy(
        online = state.online - event.getPayload
            .asInstanceOf[com.safechat.domain.Disconnected]
            .getLogin
            .toString
      )
    } else if (event.getPayload.isInstanceOf[TextAdded]) {
      val ev     = event.getPayload.asInstanceOf[TextAdded]
      val zoneDT = ZonedDateTime.ofInstant(Instant.ofEpochMilli(event.getWhen), ZoneId.of(event.getTz.toString))
      state.recentHistory.add(s"[${frmtr.format(zoneDT)}] - ${ev.getUser} -> ${ev.getReceiver}:${ev.getText}")
      state
    } else
      state

  def snapshotPredicate(
    ctx: ActorContext[UserCmd]
  )(state: FullChatState, event: MsgEnvelope, id: Long): Boolean = {
    val ifSnap = id > 0 && id % snapshotEveryN == 0

    if (ifSnap)
      ctx.log.info(s"Snapshot {}", id)

    ifSnap
  }
}
