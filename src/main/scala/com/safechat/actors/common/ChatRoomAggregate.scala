package com.safechat.actors.common

import akka.actor.Props
import com.safechat.Boot
import com.safechat.Boot.AppCfg
import com.safechat.actors.ChatRoomEvent
import com.safechat.actors.ChatRoomState
import com.safechat.actors.Command
import com.safechat.actors.Reply
import com.safechat.actors.common.Aggregate.AggReply
import com.safechat.actors.common.Aggregate.PersistEvent
import com.safechat.actors.common.Aggregate.RejectCmd
import com.safechat.actors.common.Aggregate.ValidationRejection
import com.safechat.domain.RingBuffer

import java.util.TimeZone

object ChatRoomAggregate {
  def props(appCfg: AppCfg) =
    Props(new ChatRoomAggregate(appCfg)).withDispatcher(Boot.dbDispatcher)
}

final class ChatRoomAggregate(appCfg: AppCfg)
    extends Aggregate[ChatRoomState, Command[Reply], ChatRoomEvent](
      ChatRoomState(recentHistory = RingBuffer(appCfg.recentHistorySize)),
      appCfg.snapshotEvery
    )
    with CommandHandler[ChatRoomState, Command[Reply], ChatRoomEvent]
    with EventHandler[ChatRoomState, ChatRoomEvent] {

  val rb = RingBuffer(appCfg.recentHistorySize)

  override val persistenceId = self.path.name

  override def applyCommand: (Command[Reply], ChatRoomState) => AggReply[ChatRoomEvent] = {
    (cmd: Command[Reply], s: ChatRoomState) =>
      cmd match {
        case c: Command.JoinUser =>
          if (s.users.contains(c.user)) RejectCmd(ValidationRejection(""))
          else PersistEvent(ChatRoomEvent.UserJoined(c.user, lastSequenceNr + 1, c.pubKey))
        case Command.PostText(_, sender, receiver, content, _) =>
          PersistEvent(
            ChatRoomEvent.UserTextAdded(
              sender,
              lastSequenceNr + 1,
              receiver,
              content,
              System.currentTimeMillis(),
              TimeZone.getDefault.getID
            )
          )

        case _: Command.Leave           => RejectCmd(ValidationRejection(""))
        case _: Command.HandOffChatRoom => RejectCmd(ValidationRejection(""))
      }
  }

  override def applyEvent: (ChatRoomEvent, ChatRoomState) => ChatRoomState = {
    (e: ChatRoomEvent, prevState: ChatRoomState) =>
      e match {
        case _: ChatRoomEvent.UserJoined       => prevState
        case _: ChatRoomEvent.UserTextAdded    => prevState
        case _: ChatRoomEvent.UserDisconnected => prevState
      }
  }

  override def onRecoveryCompleted(rState: ChatRoomState): Unit =
    log.info("Recovered {}", rState)

}
