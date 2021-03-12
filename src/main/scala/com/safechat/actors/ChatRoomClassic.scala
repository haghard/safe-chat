package com.safechat.actors

import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Stash
import akka.actor.Timers
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.cluster.sharding.ShardRegion
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.TextMessage
import akka.persistence.PersistentActor
import akka.persistence.RecoveryCompleted
import akka.stream.KillSwitches
import akka.stream.StreamRefAttributes
import akka.stream.javadsl.StreamRefs
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.MergeHub
import akka.stream.scaladsl.Source
import com.safechat.actors.ChatRoom.persistTimeout
import com.safechat.actors.ChatRoomClassic.HeartBeat
import com.safechat.actors.ChatRoomClassic.chatRoomHub
import com.safechat.domain.RingBuffer

import java.util.TimeZone
import scala.concurrent.duration._

object ChatRoomClassic {
  case object HeartBeat

  def msg(persistenceId: String, seqNum: Long, userId: String, recipient: String, content: String) =
    s"[$persistenceId:$seqNum - from:$userId -> to:$recipient] - $content"

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command[Reply] ⇒ (cmd.chatId, cmd) }

  val shardExtractor: ShardRegion.ExtractShardId = {
    case cmd: Command[Reply]             ⇒ cmd.chatId
    case ShardRegion.StartEntity(chatId) ⇒ chatId
  }

  def props(to: FiniteDuration) =
    Props(new ChatRoomClassic(to)).withDispatcher("cassandra-dispatcher")

  def chatRoomHub(persistenceId: String)(implicit
    sys: ActorSystem[Nothing]
  ): ChatRoomHub = {
    val bs = 1 << 2
    //sys.log.warn("Create chatroom {}", persistenceId)

    val persistFlow = persist(persistenceId)(sys.classicSystem, persistTimeout)
      .collect { case r: TextPostedReply ⇒
        TextMessage.Strict(ChatRoomClassic.msg(persistenceId, r.seqNum, r.userId, r.recipient, r.content))
      }

    val ((sinkHub, ks), sourceHub) =
      MergeHub
        .source[Message](perProducerBufferSize = bs)
        .via(persistFlow)
        .viaMat(KillSwitches.single)(Keep.both)
        .toMat(BroadcastHub.sink[Message](bufferSize = bs))(Keep.both)
        .run()

    ChatRoomHub(sinkHub, sourceHub, ks)
  }
}

class ChatRoomClassic(totalFailoverTimeout: FiniteDuration)
    extends Timers
    with PersistentActor
    with ActorLogging
    with Stash {
  //
  val tickTo = 3.seconds

  implicit val sys = context.system.toTyped

  override val persistenceId = self.path.name

  override def postStop(): Unit =
    timers.cancel(persistenceId)

  override def receiveRecover: Receive = {
    var regUsers: Map[String, String]     = Map.empty
    var online: Set[String]               = Set.empty
    val recentHistory: RingBuffer[String] = RingBuffer[String](1 << 3)
    var hub: Option[ChatRoomHub]          = None

    {
      case ChatRoomEvent.UserJoined(userId, pubKey) ⇒
        if (userId != ChatRoom.wakeUpUserName) {
          if (hub.isEmpty) {
            hub = Some(chatRoomHub(persistenceId))
          }
          regUsers = regUsers + (userId → pubKey)
          online = online + userId
        }

      case e: ChatRoomEvent.UserTextAdded ⇒
        val historyItem = ChatRoomClassic.msg(persistenceId, e.seqNum, e.userId, e.recipient, e.content)
        recentHistory :+ historyItem

      case e: ChatRoomEvent.UserDisconnected ⇒
        online = online - e.userId

      case RecoveryCompleted ⇒
        timers.startTimerWithFixedDelay(persistenceId, HeartBeat, tickTo)
        context become active(ChatRoomState(regUsers, online, recentHistory, hub))
    }
  }

  /** Does 2 things
    * 1) Update current state
    * 2) Send reply back
    */
  def onEvent[T <: Reply](
    cmd: Command[T],
    state: ChatRoomState,
    event: ChatRoomEvent
  ): ChatRoomState = {
    val (newState, reply) = event match {
      case ChatRoomEvent.UserJoined(userId, pubKey) ⇒
        val newState =
          if (state.online.isEmpty && state.hub.isEmpty) {
            if (userId == ChatRoom.wakeUpUserName) state
            else
              state.copy(
                regUsers = state.regUsers + (userId → pubKey),
                online = Set(userId),
                hub = Some(ChatRoomClassic.chatRoomHub(persistenceId))
              )
          } else
            state.copy(
              regUsers = state.regUsers + (userId → pubKey),
              online = state.online + userId
            )

        val reply = newState.hub match {
          case Some(hub) ⇒
            val settings      = StreamRefAttributes.subscriptionTimeout(totalFailoverTimeout)
            val recentHistory = newState.recentHistory.entries.mkString("\n")
            val srcRef = (Source.single[Message](TextMessage(recentHistory)) ++ hub.srcHub)
              .runWith(StreamRefs.sourceRef[Message].addAttributes(settings))
            val sinkRef = hub.sinkHub.runWith(StreamRefs.sinkRef[Message].addAttributes(settings))
            JoinReply(persistenceId, userId, Some((sinkRef, srcRef)))
          case None ⇒
            JoinReply(persistenceId, userId, None)
        }
        (newState, reply)

      case e: ChatRoomEvent.UserTextAdded ⇒
        val historyItem = ChatRoomClassic.msg(persistenceId, e.seqNum, e.userId, e.recipient, e.content)
        state.recentHistory :+ historyItem
        (state, TextPostedReply(persistenceId, e.seqNum, e.userId, e.recipient, e.content))

      case e: ChatRoomEvent.UserDisconnected ⇒
        (state.copy(online = state.online - e.userId), LeaveReply(persistenceId, e.userId))
    }

    cmd.replyTo.tell(reply.asInstanceOf[cmd.T])
    newState
  }

  def notActive(state: ChatRoomState): Receive = {
    case cmd: Command.JoinUser ⇒
      persist(ChatRoomEvent.UserJoined(cmd.user, cmd.pubKey)) { ev ⇒
        timers.startTimerAtFixedRate(persistenceId, HeartBeat, tickTo)

        val newState = onEvent(cmd, state, ev)
        unstashAll()
        context become active(newState.copy(obvervedHeartBeatTime = System.currentTimeMillis))
      }
    case other ⇒
      //Shouldn't arrive here
      log.error("Unexpected cmd {} (notActive) mode", other)
      stash()
  }

  def active(state: ChatRoomState): Receive = {
    case cmd: Command.JoinUser ⇒
      val msSinceLastTick = System.currentTimeMillis - state.obvervedHeartBeatTime
      if (msSinceLastTick < totalFailoverTimeout.toMillis)
        persist(ChatRoomEvent.UserJoined(cmd.user, cmd.pubKey)) { ev ⇒
          val newState = onEvent(cmd, state, ev)
          context become active(newState)
        }
      else log.error("Ignore {} to avoid  possible journal corruption. {} ms", cmd, msSinceLastTick)

    case cmd: Command.PostText ⇒
      //log.info("{}", lastSequenceNr)
      val msSinceLastTick = System.currentTimeMillis - state.obvervedHeartBeatTime
      //Thread.sleep(400)
      if (msSinceLastTick < totalFailoverTimeout.toMillis) {
        persist(
          ChatRoomEvent.UserTextAdded(
            lastSequenceNr,
            cmd.sender,
            cmd.receiver,
            cmd.content,
            System.currentTimeMillis,
            TimeZone.getDefault.getID
          )
        ) { ev ⇒
          val newState = onEvent(cmd, state, ev)
          context become active(newState)
        }
      } else log.error("Ignore {} to avoid  possible journal corruption. {} ms", cmd, msSinceLastTick)

    case cmd: Command.Leave ⇒
      val msSinceLastTick = System.currentTimeMillis - state.obvervedHeartBeatTime
      if (msSinceLastTick < totalFailoverTimeout.toMillis)
        persist(ChatRoomEvent.UserDisconnected(cmd.user)) { ev ⇒
          val newState = onEvent(cmd, state, ev)
          context become active(newState)
        }
      else log.error("Ignore {} to avoid  possible journal corruption. {} ms", cmd, msSinceLastTick)

    case _: Command.StopChatRoom ⇒
      state.hub.foreach(_.ks.shutdown())
      log.info(
        s"${getClass.getName}. {} millis since last tick",
        System.currentTimeMillis - state.obvervedHeartBeatTime
      )
      context.stop(self)

    case HeartBeat ⇒
      val newState = state.copy(obvervedHeartBeatTime = System.currentTimeMillis)
      context become active(newState)

    /*
    case ReceiveTimeout ⇒
      context.parent ! ShardRegion.Passivate(stopMessage = HandOff)

    case PassivatePA ⇒
      log.info("PassivatePA")
      state.hub.foreach(_.ks.shutdown())
      context.stop(self)
     */
  }

  override def receiveCommand: Receive = notActive(ChatRoomState())
}
