package com.safechat.actors

import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Stash
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
import com.safechat.Server.AppCfg
import com.safechat.actors.ChatRoom.persistTimeout
import com.safechat.actors.ChatRoomClassic.chatRoomHub
import com.safechat.domain.RingBuffer

import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable
import scala.collection.mutable
import scala.concurrent.duration._

object ChatRoomClassic {

  def msg(persistenceId: String, seqNum: Long, userId: String, recipient: String, content: String) =
    s"[$persistenceId:$seqNum - from:$userId -> to:$recipient] - $content"

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command[Reply] ⇒ (cmd.chatId, cmd) }

  val shardExtractor: ShardRegion.ExtractShardId = {
    case cmd: Command[Reply]             ⇒ cmd.chatId
    case ShardRegion.StartEntity(chatId) ⇒ chatId
  }

  def props(
    totalFailoverTimeout: FiniteDuration,
    kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]],
    appCfg: AppCfg
  ) =
    Props(new ChatRoomClassic()(kksRef, totalFailoverTimeout, appCfg)).withDispatcher("cassandra-dispatcher")

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
        .flatMapConcat(_.asTextMessage.getStreamedText.fold("")(_ + _)) //.recoverWithRetries()
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

class ChatRoomClassic(implicit
  kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]],
  totalFailoverTimeout: FiniteDuration,
  appCfg: AppCfg
) extends PersistentActor
    with Passivation
    with ActorLogging
    with Stash {

  implicit val sys = context.system.toTyped

  override val persistenceId = self.path.name

  override def receiveRecover: Receive = {
    var regUsers: mutable.Map[String, String] = mutable.Map.empty
    var online: mutable.Set[String]           = mutable.Set.empty
    var recentHistory: RingBuffer[String]     = RingBuffer[String](1 << 3)
    var hub: Option[ChatRoomHub]              = None

    {
      case ChatRoomEvent.UserJoined(userId, pubKey) ⇒
        if (hub.isEmpty)
          hub = Some(chatRoomHub(persistenceId, kksRef))

        regUsers.put(userId, pubKey)
        online += userId

      case e: ChatRoomEvent.UserTextAdded ⇒
        recentHistory :+ ChatRoomClassic.msg(persistenceId, e.seqNum, e.userId, e.recipient, e.content)

      case e: ChatRoomEvent.UserDisconnected ⇒
        online -= e.userId

      case SnapshotOffer(metadata, snapshot) ⇒
        log.info(s"Recovered snapshot: $metadata")
        val state = snapshot.asInstanceOf[ChatRoomState]
        regUsers = state.users
        online = state.usersOnline
        recentHistory = state.recentHistory
        hub = Some(chatRoomHub(persistenceId, kksRef))

      case RecoveryCompleted ⇒
        log.info(s"Recovered: [${regUsers.keySet.mkString(",")}] - [${online.mkString(",")}] ")
        context become active(ChatRoomState(regUsers, online, recentHistory, hub))
    }
  }

  def maybeSnapshot(state: ChatRoomState): ChatRoomState =
    if (state.commandsWithoutCheckpoint >= appCfg.snapshotEvery) {
      saveSnapshot(state)
      state.copy(commandsWithoutCheckpoint = 0)
    } else state.copy(commandsWithoutCheckpoint = state.commandsWithoutCheckpoint + 1)

  def notActive(state: ChatRoomState): Receive = {
    case cmd: Command.JoinUser ⇒
      persist(ChatRoomEvent.UserJoined(cmd.user, cmd.pubKey)) { ev ⇒
        val newState = maybeSnapshot(Handler(cmd, cmd.coerceEvent(ev), state))
        unstashAll()
        context become active(newState)
      }
    case other ⇒
      //Shouldn't arrive here
      log.error("Unexpected cmd {} (notActive) mode", other)
      stash()
  }

  def active(state: ChatRoomState): Receive = {
    case cmd: Command.JoinUser ⇒
      persist(ChatRoomEvent.UserJoined(cmd.user, cmd.pubKey)) { ev ⇒
        val newState = maybeSnapshot(Handler(cmd, cmd.coerceEvent(ev), state))
        context become active(newState)
      }

    case cmd: Command.PostText ⇒
      //log.info("{}", lastSequenceNr)
      log.info("{} - {}", state.users.keySet.mkString(","), state.usersOnline.mkString(","))
      //Thread.sleep(400)
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
        val newState = maybeSnapshot(Handler(cmd, cmd.coerceEvent(ev), state))
        context become active(newState)
      }

    case cmd: Command.Leave ⇒
      persist(ChatRoomEvent.UserDisconnected(cmd.user)) { ev ⇒
        val newState = maybeSnapshot(Handler(cmd, cmd.coerceEvent(ev), state))
        context become active(newState)
      }

    case cmd: Command.HandOffChatRoom ⇒
      state.hub.foreach { hub ⇒
        hub.ks.shutdown()
        unregisterKS(kksRef, hub.ks)
      }
      log.info(s"${cmd.getClass.getSimpleName}")
      context.stop(self)

    // snapshot-related messages
    case SaveSnapshotSuccess(metadata) ⇒
      log.info(s"Saving snapshot succeeded: $metadata")

    case SaveSnapshotFailure(metadata, reason) ⇒
      log.warning(s"Saving snapshot $metadata failed because of $reason")
  }

  override def receiveCommand: Receive = notActive(ChatRoomState())
}
