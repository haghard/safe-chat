package com.safechat.actors

import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Stash
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.cluster.sharding.ShardRegion
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.TextMessage
import akka.persistence.PersistentActor
import akka.persistence.RecoveryCompleted
import akka.persistence.SaveSnapshotFailure
import akka.persistence.SaveSnapshotSuccess
import akka.persistence.SnapshotOffer
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.EventEnvelope
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.TimeBasedUUID
import akka.stream.KillSwitches
import akka.stream.OverflowStrategy
import akka.stream.UniqueKillSwitch
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.MergeHub
import akka.stream.scaladsl.Source
import com.safechat.Boot
import com.safechat.Boot.AppCfg
import com.safechat.actors.ChatRoomClassic.chatRoomHub
import com.safechat.domain.RingBuffer

import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable
import scala.collection.mutable
import scala.concurrent.duration._

object ChatRoomClassic {

  val MSG_SEP = "$"

  def msg(persistenceId: String, seqNum: Long, userId: String, recipient: String, content: String) =
    s"[$persistenceId:$seqNum - from:$userId -> to:$recipient] - $content"

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command[Reply] => (cmd.chatId.value, cmd) }

  // How many shards should you try to have in your system? 10 per node is recommended
  val shardExtractor: ShardRegion.ExtractShardId = {
    case cmd: Command[Reply] => cmd.chatId.value
    // only if you use memember entities
    case ShardRegion.StartEntity(chatId) => chatId
  }

  private def journal(
    chatId: String,
    fromSequenceNr: Long,
    classicSystem: akka.actor.ActorSystem
  ): Source[ChatRoomEvent.UserTextAdded, akka.NotUsed] =
    PersistenceQuery(classicSystem)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
      .eventsByPersistenceId(chatId, fromSequenceNr, Long.MaxValue)
      .collect {
        case EventEnvelope(
              TimeBasedUUID(_),
              `chatId`,
              sequenceNr @ _,
              ChatRoomEvent.UserTextAdded(userId, seqNum, recipient, content, w, tz)
            ) =>
          ChatRoomEvent.UserTextAdded(userId, seqNum, recipient, content, w, tz)
      }

  def props(
    totalFailoverTimeout: FiniteDuration,
    kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]],
    appCfg: AppCfg
  ) = Props(new ChatRoomClassic()(kksRef, totalFailoverTimeout, appCfg)).withDispatcher(Boot.dbDispatcher)

  def chatRoomHub(
    chatId: String,
    recentHistorySize: Int,
    fromSequenceNr: Long,
    kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]]
  )(implicit classicSystem: akka.actor.ActorSystem): ChatRoomHub = {
    implicit val t = akka.util.Timeout(2.seconds)
    /*
    //By the time the 1st element hass emited from the journal, the previous [1...recentHistorySize] had already been written
    val ((sinkHub, ks), sourceHub) =
      MergeHub
        .source[Message](perProducerBufferSize = 1)
        .flatMapConcat(_.asTextMessage.getStreamedText.fold("")(_ + _))
        .groupedWithin(recentHistorySize, 150.millis)
        .mapAsync(1) { msgs ⇒
          val batch = msgs.map(_.split(MSG_SEP)).map { segments ⇒
            if (segments.size == 3) Content(segments(0).trim, segments(1).trim, segments(2).trim)
            else Content("", "", s"Wrong format: ${segments.mkString(",")}")
          }
          shardRegion.ask[Reply.TextsPostedReply](
            Command.PostTexts(persistenceId, batch, _)
          ) //retry or fail on akka.pattern.AskTimeoutException ???
        }
        .async(Server.httpDispatcher, 1)
        //.via(postPersist)
        .zip(journal(persistenceId, /*lastRepsistedEventId*/, classicSystem))
        .buffer(1, OverflowStrategy.backpressure)
        .mapConcat {
          case (seqNum, e: ChatRoomEvent.UserTextAdded) ⇒
            assert(seqNum == e.seqNum, s"seqNum mismatch $seqNum:${e.seqNum}")
            Seq(TextMessage.Strict(ChatRoomClassic.msg(persistenceId, e.seqNum, e.userId, e.recipient, e.content)))
          case (seqNum, e: ChatRoomEvent.UserTextsAdded) ⇒
            assert(seqNum == e.seqNum, s"seqNum mismatch $seqNum:${e.seqNum}")
            e.msgs.map { ev ⇒
              assert(seqNum == e.seqNum, s"seqNum mismatch $seqNum : ${e.seqNum}")
              TextMessage.Strict(ChatRoomClassic.msg(persistenceId, e.seqNum, ev.userId, ev.recipient, ev.content))
            }
          case (seqNum, e) ⇒
            throw new java.lang.AssertionError(s"Unexpected event: ${e.getClass.getName}")
        }
        .viaMat(KillSwitches.single)(Keep.both)
        .toMat(BroadcastHub.sink[Message](bufferSize = recentHistorySize))(Keep.both)
        .run()
     */

    val reader: Source[ChatRoomEvent.UserTextAdded, akka.NotUsed] =
      journal(chatId, fromSequenceNr, classicSystem)
        .buffer(1, OverflowStrategy.backpressure)
    // .viaMat(new LastConsumed[ChatRoomEvent])(Keep.right)

    // format: off
    /** {{{
      * +--------------------------------------------------------------------------+
      * |    +-------+                            +-------+       +-------------+  |
      * |    |Client ○------+                     |Reader ○------> Client's sink|  |
      * |    +-------+      |                     +-------+       +-------------+  |
      * |                   |       +--------+       |                             |
      * |                   |       |        |------>|pulls                        |
      * |                   +------>|Journal |<------+                             |
      * |                           +--------+                                     |
      * +--------------------------------------------------------------------------+
      * }}}
      */
    // format: on
    val ((sinkHub, ks), sourceHub) =
      MergeHub
        // .sourceWithDraining[Message](perProducerBufferSize = 1)
        .source[Message](perProducerBufferSize = 1)
        .flatMapConcat(_.asTextMessage.getStreamedText.fold("")(_ + _))
        .via(persist(chatId))
        .async(Boot.httpDispatcher, 1)
        .zip(reader)
        .map { case (r @ _, userTextAdded) =>
          val content = ChatRoomClassic.msg(
            chatId,
            userTextAdded.seqNum,
            userTextAdded.userId.value,
            userTextAdded.recipient.value,
            userTextAdded.content
          )
          TextMessage.Strict(content)
        }
        .viaMat(KillSwitches.single)(Keep.both)
        .toMat(BroadcastHub.sink[Message](bufferSize = recentHistorySize))(Keep.both)
        .run()

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

  implicit val classicSystem = context.system
  implicit val typedSystem   = classicSystem.toTyped

  private val chatId = ChatId(self.path.name)

  // Do not use it directly. Use `chatId` instead
  override val persistenceId = chatId.value // self.path.name

  override def receiveRecover: Receive = {
    var regUsersKeys: mutable.Map[UserId, String] = mutable.Map.empty
    var online: mutable.Set[UserId]               = mutable.Set.empty
    var recentHistory: RingBuffer[String]         = RingBuffer[String](appCfg.recentHistorySize)
    var hub: Option[ChatRoomHub]                  = None

    {
      case e: ChatRoomEvent =>
        e match {
          case ChatRoomEvent.UserJoined(userId, _, pubKey) =>
            regUsersKeys.put(userId, pubKey)
            online += userId

          case ChatRoomEvent.UserTextAdded(userId, seqNum, recipient, content, _, _) =>
            recentHistory :+ ChatRoomClassic.msg(chatId.value, seqNum, userId.value, recipient.value, content)

          case ChatRoomEvent.UserDisconnected(userId) =>
            online -= userId
        }

      case SnapshotOffer(metadata, snapshot: ChatRoomState) =>
        log.info(s"Recovered snapshot: $metadata")
        val state = snapshot
        regUsersKeys = state.users
        online = state.usersOnline
        recentHistory = state.recentHistory

      case RecoveryCompleted =>
        if (regUsersKeys.nonEmpty)
          hub = Some(chatRoomHub(chatId.value, appCfg.recentHistorySize, lastSequenceNr, kksRef))

        log.info(s"Recovered: [${regUsersKeys.keySet.mkString(",")}] - [${online.mkString(",")}] ")
        context become active(ChatRoomState(regUsersKeys, online, recentHistory, hub))
    }
  }

  def maybeSnapshot(state: ChatRoomState): ChatRoomState =
    if (state.commandsWithoutCheckpoint >= appCfg.snapshotEvery) {
      saveSnapshot(state)
      state.copy(commandsWithoutCheckpoint = 0)
    } else state.copy(commandsWithoutCheckpoint = state.commandsWithoutCheckpoint + 1)

  def notActive(state: ChatRoomState): Receive = {
    case cmd: Command.JoinUser =>
      persist(ChatRoomEvent.UserJoined(cmd.user, lastSequenceNr + 1, cmd.pubKey)) { ev =>
        val newState = maybeSnapshot(Handler(cmd, cmd.coerce(ev), state))
        unstashAll()
        context become active(newState)
      }

    case other =>
      // Shouldn't arrive here
      log.error("Unexpected cmd {} (notActive) mode", other)
      stash()
  }

  def active(state: ChatRoomState): Receive = {
    case cmd: Command.JoinUser =>
      persist(ChatRoomEvent.UserJoined(cmd.user, lastSequenceNr + 1, cmd.pubKey)) { ev =>
        val newState = maybeSnapshot(Handler(cmd, cmd.coerce(ev), state))
        context become active(newState)
      }

    case cmd: Command.PostText =>
      // log.info("registered:[{}] - online:[{}]", state.users.keySet.mkString(","), state.usersOnline.mkString(","))
      persist(
        ChatRoomEvent.UserTextAdded(
          cmd.sender,
          lastSequenceNr + 1,
          cmd.receiver,
          cmd.content,
          System.currentTimeMillis,
          TimeZone.getDefault.getID
        )
      ) { ev =>
        val newState = maybeSnapshot(Handler(cmd, cmd.coerce(ev), state))
        context become active(newState)
      }

    case cmd: Command.Leave =>
      persist(ChatRoomEvent.UserDisconnected(cmd.user)) { ev =>
        val newState = maybeSnapshot(Handler(cmd, cmd.coerce(ev), state))
        context become active(newState)
      }

    case cmd: Command.HandOffChatRoom =>
      state.hub.foreach { hub =>
        hub.ks.shutdown()
        unregisterKS(kksRef, hub.ks)
      }
      log.info(s"${cmd.getClass.getSimpleName}")
      context.stop(self)

    // snapshot-related messages
    case SaveSnapshotSuccess(metadata) =>
      log.info(s"Saving snapshot succeeded: $metadata")

    case SaveSnapshotFailure(metadata, reason) =>
      log.warning(s"Saving snapshot $metadata failed because of $reason")
  }

  override def receiveCommand: Receive =
    notActive(ChatRoomState(recentHistory = RingBuffer(appCfg.recentHistorySize)))

  /*
      This method is called if persisting failed. The actor will be stopped.
      Best practice: start the actor again after a while.
      (use Backoff supervisor)
   */
  override def onPersistFailure(cause: Throwable, event: Any, seqNr: Long): Unit = {
    log.error(s"Fail to persist $event because of $cause")
    super.onPersistFailure(cause, event, seqNr)
  }

  //  Called if the JOURNAL fails to persist the event  The actor is RESUMED.
  override def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit = {
    log.error(s"Persist rejected for $event because of $cause")
    super.onPersistRejected(cause, event, seqNr)
  }
}
