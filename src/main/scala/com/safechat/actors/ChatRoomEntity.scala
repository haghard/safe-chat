// Copyright (c) 2018-19 by Haghard. All rights reserved.

package com.safechat.actors

import java.util.{TimeZone, UUID}

import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop, PreRestart, SupervisorStrategy}

import scala.concurrent.duration._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.sharding.typed.scaladsl.{EntityTypeKey, EventSourcedEntity}
import akka.persistence.typed.{RecoveryCompleted, RecoveryFailed, SnapshotCompleted, SnapshotFailed}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.stream.{KillSwitches, StreamRefAttributes}
import com.safechat.LoggingBehaviorInterceptor
import com.safechat.domain.{Disconnected, Joined, MsgEnvelope, RingBuffer, TextAdded}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{BroadcastHub, Keep, MergeHub, StreamRefs}

import scala.concurrent.Future
import akka.actor.typed.scaladsl.AskPattern._
import com.safechat.crypto.Account
import com.safechat.rest.WsScaffolding

object ChatRoomEntity {

  val snapshotEveryN = 100
  val bs             = 1 << 4
  val hubInitTimeout = 3.seconds

  val wakeUpUserName   = "John Doe"
  val wakeUpEntityName = "none"

  val settings = StreamRefAttributes
    .subscriptionTimeout(hubInitTimeout)
    .and(akka.stream.Attributes.inputBuffer(bs, bs))

  implicit val t = akka.util.Timeout(hubInitTimeout)

  val entityKey: EntityTypeKey[UserCmd] =
    EntityTypeKey[UserCmd]("chat-rooms")

  def apply(entId: String): Behavior[UserCmd] =
    Behaviors.setup { ctx ⇒
      //LoggingBehaviorInterceptor(ctx.log) {
      //https://doc.akka.io/docs/akka/2.6/project/migration-guide-2.5.x-2.6.x.html
      implicit val sys = ctx.system
      implicit val sch = ctx.system.scheduler
      implicit val ec  = ctx.system.executionContext

      EventSourcedEntity(
        entityTypeKey = entityKey,
        entityId = entId,
        emptyState = FullChatState(),
        commandHandler = ch(ctx.self.path.name, ctx),
        //When an event has been persisted successfully the new state is created by applying the event to the current state with the eventHandler.
        eventHandler = eh(ctx, ctx.self.path.name)
      ).receiveSignal {
          case (state, RecoveryCompleted) ⇒
            ctx.log.info(s"Recovered: [${state.regUsers.mkString(",")}]")
          case (state, PreRestart) ⇒
            ctx.log.info(s"PreRestart ${state.regUsers.mkString(",")}")
          case (state, PostStop) ⇒
            state.hub.foreach(_.ks.shutdown)
            ctx.log.info("PostStop. Clean up chat resources")
          case (state, SnapshotCompleted(_)) ⇒
            ctx.log.info(s"SnapshotCompleted [${state.regUsers.mkString(",")}]")
          case (state, SnapshotFailed(_, ex)) ⇒
            ctx.log.error(s"SnapshotFailed ${state.regUsers.mkString(",")}", ex)
          case (_, RecoveryFailed(cause)) ⇒
            ctx.log.error(s"RecoveryFailed $cause", cause)
          case (_, signal) ⇒
            ctx.log.info(s"Signal $signal")
        }
        .snapshotWhen(snapshotPredicate(ctx))
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
    *
    */
  def createHub(
    bs: Int,
    persistenceId: String,
    ctx: ActorRef[PostText]
  )(
    implicit sys: ActorSystem[Nothing]
  ): ChatRoomHub = {
    implicit val ec  = sys.executionContext
    implicit val sch = sys.scheduler
    //ctx.log.info("create hub for {}", persistenceId)
    val ((sinkHub, ks), sourceHub) =
      MergeHub
        .source[Message](bs)
        .via(
          WsScaffolding
            .flowWithHeartbeat()
            .mapAsync(1) { //to preserve the real time ordering
              case TextMessage.Strict(text) ⇒
                val segments = text.split(":")
                if (segments.size == 2) {
                  ctx
                    .ask[ChatRoomReply](PostText(persistenceId, segments(0).trim, segments(1).trim, _))
                    .collect { case r: TextPostedReply ⇒ TextMessage.Strict(s"${r.chatId}:${r.seqNum}") }
                } else if (text eq WsScaffolding.hbMessage)
                  Future.successful(TextMessage.Strict(s"$persistenceId:ping"))
                else Future.successful(TextMessage.Strict("Invalid msg format"))
              case other ⇒
                throw new Exception(s"Unexpected message type ${other.getClass.getName}")
            }
        )
        .viaMat(KillSwitches.single)(Keep.both)
        .toMat(BroadcastHub.sink[Message](1))(Keep.both)
        .run()
    ChatRoomHub(sinkHub, sourceHub, ks)
  }

  def ch(persistenceId: String, ctx: ActorContext[UserCmd])(state: FullChatState, cmd: UserCmd)(
    implicit sys: ActorSystem[Nothing]
  ): Effect[MsgEnvelope, FullChatState] =
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
          ) //Note that the new state after applying the event is passed as parameter to the thenRun function
          .thenRun { newState: FullChatState ⇒
            newState.hub.foreach { h ⇒
              val srcRefF  = h.srcHub.runWith(StreamRefs.sourceRef[Message].addAttributes(settings))
              val sinkRefF = h.sinkHub.runWith(StreamRefs.sinkRef[Message].addAttributes(settings))
              cmd.replyTo.tell(JoinReply(m.chatId, m.user, sinkRefF, srcRefF))
            }
          }

      case cmd: PostText ⇒
        val num = EventSourcedBehavior.lastSequenceNumber(ctx)
        Effect
          .persist(
            new MsgEnvelope(
              UUID.randomUUID.toString,
              System.currentTimeMillis,
              TimeZone.getDefault.getID,
              new TextAdded(cmd.user, cmd.text)
            )
          )
          .thenRun { newState: FullChatState ⇒
            ctx.log.info("users online:[{}]", newState.online.mkString(","))
            cmd.replyTo.tell(TextPostedReply(cmd.chatId, num))
          }

      case cmd: DisconnectUser ⇒
        /*Effect.none.thenRun { s: FullChatState ⇒
          ctx.log.info("{} left - online:[{}]", user, s.online.mkString(","))
          replyTo.tell(LeaveReply(chatId, user))
        }*/

        Effect
          .persist(
            new MsgEnvelope(
              UUID.randomUUID.toString,
              System.currentTimeMillis,
              TimeZone.getDefault.getID,
              Disconnected.newBuilder.setLogin(cmd.user).build
            )
          )
          .thenRun { newState: FullChatState ⇒
            ctx.log.info("{} left - online:[{}]", cmd.user, newState.online.mkString(""))
            cmd.replyTo.tell(DisconnectReply(cmd.chatId, cmd.user))
          }
    }

  def eh(ctx: ActorContext[UserCmd], persistenceId: String)(state: FullChatState, event: MsgEnvelope)(
    implicit sys: ActorSystem[Nothing]
  ): FullChatState =
    if (event.getPayload.isInstanceOf[Joined]) {
      val ev = event.getPayload.asInstanceOf[Joined]

      if (state.online.isEmpty && state.hub.isEmpty)
        if (ev.getLogin.toString == ChatRoomEntity.wakeUpUserName)
          state
        else
          state.copy(
            regUsers = state.regUsers + (ev.getLogin.toString → ev.getPubKey.toString),
            hub = Some(createHub(bs, persistenceId, ctx.self.narrow[PostText])),
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
    } else if (event.getPayload.isInstanceOf[com.safechat.domain.TextAdded])
      state
    else
      state

  def snapshotPredicate(
    ctx: ActorContext[UserCmd]
  )(state: FullChatState, event: MsgEnvelope, id: Long): Boolean = {
    //Shouldn't be called here
    //val lastSeqNum = EventSourcedBehavior.lastSequenceNumber(ctx)

    val ifSnap = id > 0 && id % snapshotEveryN == 0

    if (ifSnap)
      ctx.log.info(s"Snapshot {}", id)

    ifSnap
  }
}
