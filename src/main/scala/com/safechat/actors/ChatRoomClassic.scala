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
import akka.persistence.SaveSnapshotFailure
import akka.persistence.SaveSnapshotSuccess
import akka.persistence.SnapshotOffer
import akka.stream.KillSwitches
import akka.stream.UniqueKillSwitch
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.MergeHub
import com.safechat.actors.ChatRoom.persistTimeout
import com.safechat.actors.ChatRoomClassic.HeartBeat
import com.safechat.actors.ChatRoomClassic.chatRoomHub
import com.safechat.actors.ChatRoomClassic.snapshotEveryN
import com.safechat.domain.RingBuffer

import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable
import scala.concurrent.duration._

object ChatRoomClassic {
  case object HeartBeat

  val snapshotEveryN = 1 << 8

  def msg(persistenceId: String, seqNum: Long, userId: String, recipient: String, content: String) =
    s"[$persistenceId:$seqNum - from:$userId -> to:$recipient] - $content"

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command[Reply] ⇒ (cmd.chatId, cmd) }

  val shardExtractor: ShardRegion.ExtractShardId = {
    case cmd: Command[Reply]             ⇒ cmd.chatId
    case ShardRegion.StartEntity(chatId) ⇒ chatId
  }

  def props(totalFailoverTimeout: FiniteDuration, kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]]) =
    Props(new ChatRoomClassic()(kksRef, totalFailoverTimeout)).withDispatcher("cassandra-dispatcher")

  def chatRoomHub(
    persistenceId: String,
    kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]]
  )(implicit
    sys: ActorSystem[Nothing]
  ): ChatRoomHub = {
    val bs = 1 << 2
    //sys.log.warn("Create chatroom {}", persistenceId)

    val persistFlow = persist(persistenceId)(sys.classicSystem, persistTimeout)
      .map {
        case r: Reply.TextPostedReply ⇒
          TextMessage.Strict(ChatRoomClassic.msg(persistenceId, r.seqNum, r.userId, r.recipient, r.content))
        case other ⇒
          throw new Exception(s"Unexpected reply $other")
      }

    val ((sinkHub, ks), sourceHub) =
      MergeHub
        .source[Message](perProducerBufferSize = bs)
        .via(persistFlow)
        .viaMat(KillSwitches.single)(Keep.both)
        .toMat(BroadcastHub.sink[Message](bufferSize = bs))(Keep.both)
        .run()

    /*val name = s"shutdown.hub.$persistenceId"
    CoordinatedShutdown(sys)
      .addTask(akka.actor.CoordinatedShutdown.PhaseServiceRequestsDone, name) { () ⇒
        scala.concurrent.Future.successful {
          sys.log.info(s"★ ★ ★ CoordinatedShutdown [$name] ★ ★ ★")
          ks.shutdown()
          akka.Done
        }
      }*/

    registerKS(kksRef, ks)
    ChatRoomHub(sinkHub, sourceHub, ks)
  }
}

class ChatRoomClassic()(implicit
  kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]],
  totalFailoverTimeout: FiniteDuration
) extends Timers
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
    var recentHistory: RingBuffer[String] = RingBuffer[String](1 << 3)
    var hub: Option[ChatRoomHub]          = None

    {
      case ChatRoomEvent.UserJoined(userId, pubKey) ⇒
        if (userId != ChatRoom.wakeUpUserName) {
          if (hub.isEmpty) {
            hub = Some(chatRoomHub(persistenceId, kksRef))
          }
          regUsers = regUsers + (userId → pubKey)
          online = online + userId
        }

      case e: ChatRoomEvent.UserTextAdded ⇒
        recentHistory :+ ChatRoomClassic.msg(persistenceId, e.seqNum, e.userId, e.recipient, e.content)

      case e: ChatRoomEvent.UserDisconnected ⇒
        online = online - e.userId

      case SnapshotOffer(metadata, snapshot) ⇒
        log.info(s"Recovered snapshot: $metadata")
        val state = snapshot.asInstanceOf[ChatRoomState]
        regUsers = state.regUsers
        online = state.online
        recentHistory = state.recentHistory
        hub = Some(chatRoomHub(persistenceId, kksRef))

      case RecoveryCompleted ⇒
        log.info(s"Recovered: [${regUsers.keySet.mkString(",")}] - [${online.mkString(",")}] ")
        timers.startTimerWithFixedDelay(persistenceId, HeartBeat, tickTo)
        context become active(ChatRoomState(regUsers, online, recentHistory, hub))
    }
  }

  def maybeSnapshot(state: ChatRoomState): ChatRoomState =
    if (state.commandsWithoutCheckpoint >= snapshotEveryN) {
      saveSnapshot(state)
      state.copy(commandsWithoutCheckpoint = 0)
    } else state.copy(commandsWithoutCheckpoint = state.commandsWithoutCheckpoint + 1)

  def notActive(state: ChatRoomState): Receive = {
    case cmd: Command.JoinUser ⇒
      persist(ChatRoomEvent.UserJoined(cmd.user, cmd.pubKey)) { ev ⇒
        timers.startTimerAtFixedRate(persistenceId, HeartBeat, tickTo)
        val newState = maybeSnapshot(Handler(cmd, cmd.ident(ev), state))
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
          val newState = maybeSnapshot(Handler(cmd, cmd.ident(ev), state))
          context become active(newState)
        }
      else log.error("Ignore {} to avoid possible journal corruption. {} ms", cmd, msSinceLastTick)

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
          val newState = maybeSnapshot(Handler(cmd, cmd.ident(ev), state))
          context become active(newState)
        }
      } else log.error("Ignore {} to avoid possible journal corruption. {} ms", cmd, msSinceLastTick)

    case cmd: Command.Leave ⇒
      val msSinceLastTick = System.currentTimeMillis - state.obvervedHeartBeatTime
      if (msSinceLastTick < totalFailoverTimeout.toMillis)
        persist(ChatRoomEvent.UserDisconnected(cmd.user)) { ev ⇒
          val newState = maybeSnapshot(Handler(cmd, cmd.ident(ev), state))
          context become active(newState)
        }
      else log.error("Ignore {} to avoid possible journal corruption. {} ms", cmd, msSinceLastTick)

    case HeartBeat ⇒
      val newState = maybeSnapshot(state.copy(obvervedHeartBeatTime = System.currentTimeMillis))
      context become active(newState)

    case cmd: Command.StopChatRoom ⇒
      state.hub.foreach { hub ⇒
        hub.ks.shutdown()
        unregisterKS(kksRef, hub.ks)
      }

      log.info(
        s"${cmd.getClass.getSimpleName}. {} millis since last hb",
        System.currentTimeMillis - state.obvervedHeartBeatTime
      )
      context.stop(self)

    // snapshot-related messages
    case SaveSnapshotSuccess(metadata) ⇒
      log.info(s"Saving snapshot succeeded: $metadata")

    case SaveSnapshotFailure(metadata, reason) ⇒
      log.warning(s"Saving snapshot $metadata failed because of $reason")
  }

  override def receiveCommand: Receive = notActive(ChatRoomState())
}

/*
  case ReceiveTimeout ⇒
    context.parent ! ShardRegion.Passivate(stopMessage = HandOff)

  case PassivatePA ⇒
    log.info("PassivatePA")
    state.hub.foreach(_.ks.shutdown())
    context.stop(self)
 */
