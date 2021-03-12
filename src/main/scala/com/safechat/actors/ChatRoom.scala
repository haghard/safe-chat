// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat.actors

import akka.actor.typed._
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityContext
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.TextMessage
import akka.persistence.typed._
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.ReplyEffect
import akka.persistence.typed.scaladsl.RetentionCriteria
import akka.stream.KillSwitches
import akka.stream.StreamRefAttributes
import akka.stream.UniqueKillSwitch
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.MergeHub
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.StreamRefs

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable
import scala.concurrent.duration._

/** https://doc.akka.io/docs/akka/current/typed/persistence-style.html#event-handlers-in-the-state
  * https://doc.akka.io/docs/akka/current/typed/persistence-fsm.html
  */
object ChatRoom {

  val snapshotEveryN = 300 //TODO should be configurable
  val MSG_SEP        = ":"

  val wakeUpUserName   = "John Doe"
  val wakeUpEntityName = "dungeon"

  val persistTimeout = akka.util.Timeout(2.second) //write to journal timeout

  val entityKey: EntityTypeKey[Command[Reply]] =
    EntityTypeKey[Command[Reply]]("chat-rooms")

  val emptyState = com.safechat.actors.ChatRoomState()

  /** Each `ChatRoomEntity` actor is a single source of true, acting as a consistency boundary for the data that it manages.
    *
    * Messages in a single chat room define a single total order.
    * Messages in a single chat causally dependent on each other by design.
    *
    * Total order for persistence is maintained as we always write to the journal with parallelism == 1
    */
  def apply(
    entityCtx: EntityContext[Command[Reply]],
    localChatRooms: AtomicReference[immutable.Set[String]],
    kks: AtomicReference[immutable.Set[UniqueKillSwitch]],
    to: FiniteDuration
  ): Behavior[Command[Reply]] =
    Behaviors.setup { ctx ⇒
      implicit val sys      = ctx.system
      implicit val actorCtx = ctx
      val pId               = PersistenceId(entityCtx.entityTypeKey.name, entityCtx.entityId)

      /*
      fp style
      EventSourcedBehavior.withEnforcedReplies[UserCmd, ChatRoomEvent, ChatRoomState](
        pId,
        empty,
        (state, cmd) ⇒ state.applyCmd(cmd),
        (state, event) ⇒ state.applyEvn(event)
      )*/

      com.safechat.LoggingBehaviorInterceptor(ctx.log) {
        EventSourcedBehavior
          .withEnforcedReplies[Command[Reply], ChatRoomEvent, ChatRoomState](
            pId,
            ChatRoomState(),
            onCommand(ctx, to),
            //commandHandler,
            onEvent(ctx.self.path.name, kks, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z"))
          )
          /*.withTagger {
          //tagged events are useful for querying  by tag
          case m: MsgEnvelope if m.getPayload.isInstanceOf[Joined] ⇒ Set("user")
        }*/
          .receiveSignal {
            case (state, RecoveryCompleted) ⇒
              //val leaseName = s"${sys.name}-shard-${ChatRoomEntity.entityKey.name}-${entityCtx.entityId}"
              //captureChatRoom(localChatRooms, leaseName)
              ctx.log.info(s"★ Recovered: [${state.regUsers.keySet.mkString(",")}] ★")
            case (state, PreRestart) ⇒
              ctx.log.info(s"★ Pre-restart ${state.regUsers.keySet.mkString(",")} ★")
            case (state, PostStop) ⇒
              state.hub.foreach(_.ks.shutdown())
              ctx.log.info("★ PostStop. Clean up resources ★")
            case (state, SnapshotCompleted(_)) ⇒
              ctx.log.info(s"★ SnapshotCompleted [${state.regUsers.keySet.mkString(",")}]")
            case (state, SnapshotFailed(_, ex)) ⇒
              ctx.log.error(s"★ SnapshotFailed ${state.regUsers.keySet.mkString(",")}", ex)
            case (_, RecoveryFailed(cause)) ⇒
              ctx.log.error(s"★ RecoveryFailed $cause", cause)
            case (_, signal) ⇒
              ctx.log.info(s"★ Signal $signal ★")
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
    }

  /** Each chat root contains MergeHub and BroadcastHub connected together to form a runnable graph.
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
  def chatRoomHub(
    persistenceId: String,
    kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]]
  )(implicit
    sys: ActorSystem[Nothing]
  ): ChatRoomHub = {
    //val initBs = sys.settings.config.getInt("akka.stream.materializer.initial-input-buffer-size")
    val bs = 1
    sys.log.warn("Create chatroom {}", persistenceId)

    val ((sinkHub, ks), sourceHub) =
      MergeHub
        .source[Message](perProducerBufferSize = bs)
        //import akka.actor.typed.scaladsl.AskPattern._
        //.alsoTo(Sink.foreachAsync(1) { entity.ask[ChatRoomReply](PostText(persistenceId, "", "", "", _))(???, ???) })
        //.alsoTo()
        //.wireTap(m ⇒ sys.log.info("before p: {}", m)) //for rebug
        .via(
          //WsScaffolding.flowWithHeartbeat(30.second).via(persist(persistenceId, entity))
          persist(persistenceId /*, entity*/ )(sys.classicSystem, persistTimeout).collect { case r: TextPostedReply ⇒
            TextMessage.Strict(s"${r.chatId}:${r.seqNum} - ${r.content}")
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
        .toMat(BroadcastHub.sink[Message](bufferSize = bs))(Keep.both)
        .run()

    registerKS(kksRef, ks)
    ChatRoomHub(sinkHub, sourceHub, ks)
  }

  /*val commandHandler: (ChatRoomState, Command[Reply]) ⇒ ReplyEffect[ChatRoomEvent, ChatRoomState] = { (state, cmd) ⇒
    cmd match {
      case JoinUser(chatId, user, pubKey, replyTo)              ⇒
      case PostText(chatId, sender, receiver, content, replyTo) ⇒
      case Leave(chatId, user, replyTo)                         ⇒
    }
  }*/

  def onCommand(
    ctx: ActorContext[_],
    to: FiniteDuration
  )(state: ChatRoomState, cmd: Command[Reply])(implicit
    sys: ActorSystem[Nothing]
  ): ReplyEffect[ChatRoomEvent, ChatRoomState] =
    cmd match {
      case cmd: Command.JoinUser ⇒
        Effect
          .persist(ChatRoomEvent.UserJoined(cmd.user, cmd.pubKey))
          .thenReply[JoinReply](cmd.replyTo) { updateState: ChatRoomState ⇒ //That's new state after applying the event

            val settings =
              StreamRefAttributes.subscriptionTimeout(to) //.and(akka.stream.Attributes.inputBuffer(bs, bs))

            updateState.hub match {
              case Some(hub) ⇒
                val chatHistory = updateState.recentHistory.entries.mkString("\n")
                //Add new producer on the fly.  If the consumer cannot keep up, all producers will be backpressured
                val srcRefF = (Source.single[Message](TextMessage(chatHistory)) ++ hub.srcHub)
                  .runWith(StreamRefs.sourceRef[Message].addAttributes(settings))

                //Add new consumers on the fly. The rate of the producer will be automatically adapted to the slowest consumer
                val sinkRefF = hub.sinkHub.runWith(StreamRefs.sinkRef[Message].addAttributes(settings))
                JoinReply(cmd.chatId, cmd.user, Some(sinkRefF, srcRefF))
              case None ⇒
                JoinReply(cmd.chatId, cmd.user, None)
            }
          }

      case cmd: Command.PostText ⇒
        val seqNum = EventSourcedBehavior.lastSequenceNumber(ctx)
        Effect
          .persist(
            ChatRoomEvent.UserTextAdded(
              seqNum,
              cmd.sender,
              cmd.receiver,
              cmd.content,
              System.currentTimeMillis,
              TimeZone.getDefault.getID
            )
          )
          .thenReply(cmd.replyTo) { updatedState: ChatRoomState ⇒
            //ctx.log.info("online:[{}]", updatedState.online.mkString(","))
            TextPostedReply(
              cmd.chatId,
              seqNum,
              cmd.sender,
              cmd.receiver,
              s"[from:${cmd.sender} -> to:${cmd.receiver}] - ${cmd.content}"
            )
          }

      case cmd: Command.Leave ⇒
        Effect
          .persist(ChatRoomEvent.UserDisconnected(cmd.user))
          .thenReply(cmd.replyTo) { updatedState: ChatRoomState ⇒
            ctx.log.info("{} disconnected - online:[{}]", cmd.user, updatedState.online.mkString(""))
            LeaveReply(cmd.chatId, cmd.user)
          }

      case cmd: Command.StopChatRoom ⇒
        state.hub.foreach(_.ks.shutdown())
        ctx.log.info(cmd.getClass.getName)
        Effect.none.thenStop().thenNoReply()
    }

  def onEvent(
    persistenceId: String,
    kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]],
    frmtr: DateTimeFormatter
  )(state: ChatRoomState, event: ChatRoomEvent)(implicit
    sys: ActorSystem[Nothing],
    ctx: ActorContext[Command[Reply]]
  ): ChatRoomState =
    event match {
      case ChatRoomEvent.UserJoined(login, pubKey) ⇒
        if (state.online.isEmpty && state.hub.isEmpty)
          if (login == ChatRoom.wakeUpUserName)
            state
          else
            state.copy(
              regUsers = state.regUsers + (login → pubKey),
              online = Set(login),
              hub = Some(chatRoomHub(persistenceId, kksRef))
            )
        else
          state.copy(
            regUsers = state.regUsers + (login → pubKey),
            online = state.online + login
          )
      case ChatRoomEvent.UserTextAdded(seqNum, originator, receiver, content, when, tz) ⇒
        val zoneDT = ZonedDateTime.ofInstant(Instant.ofEpochMilli(when), ZoneId.of(tz))
        state.recentHistory :+ s"[$seqNum at ${frmtr.format(zoneDT)}] - $originator -> $receiver:$content"
        state
      case ChatRoomEvent.UserDisconnected(login) ⇒
        state.copy(online = state.online - login)
    }

  def snapshotPredicate(
    ctx: ActorContext[Command[Reply]]
  )(state: ChatRoomState, event: ChatRoomEvent, sequenceNr: Long): Boolean = {
    val ifSnap = sequenceNr % snapshotEveryN == 0

    if (ifSnap)
      ctx.log.info(s"Snapshot {}", sequenceNr)

    ifSnap
  }
}
