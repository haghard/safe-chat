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

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command[Reply] ⇒ (cmd.chatId, cmd) }

  val shardExtractor: ShardRegion.ExtractShardId = {
    case cmd: Command[Reply] ⇒ cmd.chatId
    //only if you use memember entities
    case ShardRegion.StartEntity(chatId) ⇒ chatId
  }

  private def journal(
    persistenceId: String,
    fromSequenceNr: Long,
    classicSystem: akka.actor.ActorSystem
  ): Source[ChatRoomEvent.UserTextAdded, akka.NotUsed] =
    PersistenceQuery(classicSystem)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
      .eventsByPersistenceId(persistenceId, fromSequenceNr, Long.MaxValue)
      .collect {
        case EventEnvelope(
              TimeBasedUUID(_),
              `persistenceId`,
              sequenceNr @ _,
              ChatRoomEvent.UserTextAdded(userId, seqNum, recipient, content, w, tz)
            ) ⇒
          ChatRoomEvent.UserTextAdded(userId, seqNum, recipient, content, w, tz)
      }

  def props(
    totalFailoverTimeout: FiniteDuration,
    kksRef: AtomicReference[immutable.Map[String, UniqueKillSwitch]],
    appCfg: AppCfg
  ) = Props(new ChatRoomClassic()(kksRef, totalFailoverTimeout, appCfg)).withDispatcher(Boot.dbDispatcher)

  def chatRoomHub(
    persistenceId: String,
    recentHistorySize: Int,
    fromSequenceNr: Long,
    kksRef: AtomicReference[immutable.Map[String, UniqueKillSwitch]]
  )(implicit classicSystem: akka.actor.ActorSystem): ChatRoomHub = {
    implicit val t = akka.util.Timeout(2.seconds)

    //sys.log.warn("Create chatroom {}", persistenceId)
    //ResumableQuery https://github.com/dnvriend/akka-persistence-query-extensions#akkapersistencequeryextensionresumablequery
    //val persistFlow = persist(persistenceId)(classicSystem, persistTimeout).withAttributes(Attributes.asyncBoundary)

    /*
    val postPersist =
      Flow[Reply.TextsPostedReply]
        .withAttributes(Attributes.inputBuffer(1, 1).and(Attributes.asyncBoundary))
        //.buffer(1, OverflowStrategy.backpressure).async
     */

    //By the time the 1st element hass emited from the journal, the previous [1...recentHistorySize] had already been written
    /*
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
      journal(persistenceId, fromSequenceNr, classicSystem)
        .buffer(1, OverflowStrategy.backpressure)
    //.viaMat(new LastConsumed[ChatRoomEvent])(Keep.right)

    val ((sinkHub, ks), sourceHub) =
      MergeHub
        //.sourceWithDraining[Message](perProducerBufferSize = 1)
        .source[Message](perProducerBufferSize = 1)
        .flatMapConcat(_.asTextMessage.getStreamedText.fold("")(_ + _))
        .via(persist(persistenceId))
        .async(Boot.httpDispatcher, 1)
        .zip(reader)
        .map { case (r @ _, userTextAdded) ⇒
          val content = ChatRoomClassic.msg(
            persistenceId,
            userTextAdded.seqNum,
            userTextAdded.userId,
            userTextAdded.recipient,
            userTextAdded.content
          )
          TextMessage.Strict(content)
        }
        .viaMat(KillSwitches.single)(Keep.both)
        .toMat(BroadcastHub.sink[Message](bufferSize = recentHistorySize))(Keep.both)
        .run()

    /*val name = s"shutdown.hub.$persistenceId"
    CoordinatedShutdown(sys)
      .addTask(akka.actor.CoordinatedShutdown.PhaseServiceRequestsDone, name) { () ⇒
        scala.concurrent.Future.successful {
          ks.shutdown()
          akka.Done
        }
      }*/

    registerKS(persistenceId, ks, kksRef)
    ChatRoomHub(sinkHub, sourceHub, ks)
  }
}

class ChatRoomClassic(implicit
  kksRef: AtomicReference[immutable.Map[String, UniqueKillSwitch]],
  totalFailoverTimeout: FiniteDuration,
  appCfg: AppCfg
) extends PersistentActor
    with Passivation
    with ActorLogging
    with Stash {

  implicit val classicSystem = context.system
  implicit val typedSystem   = classicSystem.toTyped

  override val persistenceId = self.path.name

  override def receiveRecover: Receive = {
    var regUsers: mutable.Map[String, String] = mutable.Map.empty
    var online: mutable.Set[String]           = mutable.Set.empty
    var recentHistory: RingBuffer[String]     = RingBuffer[String](appCfg.recentHistorySize)
    var hub: Option[ChatRoomHub]              = None

    {
      case e: ChatRoomEvent ⇒
        e match {
          case ChatRoomEvent.UserJoined(userId, _, pubKey) ⇒
            regUsers.put(userId, pubKey)
            online += userId

          case ChatRoomEvent.UserTextAdded(userId, seqNum, recipient, content, _, _) ⇒
            recentHistory :+ ChatRoomClassic.msg(persistenceId, seqNum, userId, recipient, content)

          /*
          case ChatRoomEvent.UserTextsAdded(seqNum, msgs, _, _) ⇒
            msgs.foreach { ev ⇒
              recentHistory :+ ChatRoomClassic.msg(persistenceId, seqNum, ev.userId, ev.recipient, ev.content)
            }*/

          case ChatRoomEvent.UserDisconnected(userId) ⇒
            online -= userId
        }

      case SnapshotOffer(metadata, snapshot: ChatRoomState) ⇒
        log.info(s"Recovered snapshot: $metadata")
        val state = snapshot
        regUsers = state.users
        online = state.usersOnline
        recentHistory = state.recentHistory

      case RecoveryCompleted ⇒
        if (regUsers.nonEmpty)
          hub = Some(chatRoomHub(persistenceId, appCfg.recentHistorySize, lastSequenceNr, kksRef))

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
      persist(ChatRoomEvent.UserJoined(cmd.user, lastSequenceNr + 1, cmd.pubKey)) { ev ⇒
        val newState = maybeSnapshot(EventHandler(cmd, cmd.correspondingEvent(ev), state))
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
      persist(ChatRoomEvent.UserJoined(cmd.user, lastSequenceNr + 1, cmd.pubKey)) { ev ⇒
        val newState = maybeSnapshot(EventHandler(cmd, cmd.correspondingEvent(ev), state))
        context become active(newState)
      }

    /*case cmd: Command.PostTexts ⇒
      log.info("{} - {}", state.users.keySet.mkString(","), state.usersOnline.mkString(","))
      val msgs = cmd.content.map(c ⇒ Content(c.userId, c.recipient, c.content))
      persist(ChatRoomEvent.UserTextsAdded(lastSequenceNr, msgs, System.currentTimeMillis, TimeZone.getDefault.getID)) {
        ev ⇒
          val newState = maybeSnapshot(EventHandler(cmd, cmd.correspondingEvent(ev), state))
          context become active(newState)
      }*/

    case cmd: Command.PostText ⇒
      log.info("{} - {}", state.users.keySet.mkString(","), state.usersOnline.mkString(","))
      persist(
        ChatRoomEvent.UserTextAdded(
          cmd.sender,
          lastSequenceNr + 1,
          cmd.receiver,
          cmd.content,
          System.currentTimeMillis,
          TimeZone.getDefault.getID
        )
      ) { ev ⇒
        val newState = maybeSnapshot(EventHandler(cmd, cmd.correspondingEvent(ev), state))
        context become active(newState)
      }

    case cmd: Command.Leave ⇒
      persist(ChatRoomEvent.UserDisconnected(cmd.user)) { ev ⇒
        val newState = maybeSnapshot(EventHandler(cmd, cmd.correspondingEvent(ev), state))
        context become active(newState)
      }

    //
    case cmd: Command.HandOffChatRoom ⇒
      /*state.hub.foreach { hub ⇒
        hub.ks.shutdown()
        unregisterKS(persistenceId, kksRef)
      }*/

      unregisterKS(persistenceId, kksRef)

      log.info(s"${cmd.getClass.getSimpleName}")
      context.stop(self)

    // snapshot-related messages
    case SaveSnapshotSuccess(metadata) ⇒
      log.info(s"Saving snapshot succeeded: $metadata")

    case SaveSnapshotFailure(metadata, reason) ⇒
      log.warning(s"Saving snapshot $metadata failed because of $reason")
  }

  override def receiveCommand: Receive = notActive(ChatRoomState(recentHistory = RingBuffer(appCfg.recentHistorySize)))

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
