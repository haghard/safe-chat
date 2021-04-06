package com.safechat.actors.common

import akka.actor.Props
import com.safechat.Server
import com.safechat.Server.AppCfg
import com.safechat.actors.ChatRoomEvent
import com.safechat.actors.ChatRoomState
import com.safechat.actors.Command
import com.safechat.actors.Reply
import com.safechat.actors.common.BasicPersistentActor.ValidationRejection
import com.safechat.actors.common.BasicPersistentActor.NoEvent
import com.safechat.actors.common.BasicPersistentActor.PersistEvent
import com.safechat.domain.RingBuffer

import java.util.TimeZone

object ChatRoom0 {

  def props(appCfg: AppCfg) =
    Props(new ChatRoom0(appCfg)).withDispatcher(Server.dbDispatcher)
}

final class ChatRoom0(appCfg: AppCfg)
    extends BasicPersistentActor[ChatRoomState, Command[Reply], ChatRoomEvent](
      ChatRoomState(recentHistory = RingBuffer(appCfg.recentHistorySize)),
      appCfg.snapshotEvery
    )
    with CommandHandler[ChatRoomState, Command[Reply], ChatRoomEvent]
    with EventHandler[ChatRoomState, Command[Reply], ChatRoomEvent] {

  override val persistenceId = self.path.name

  val rb = RingBuffer(appCfg.recentHistorySize)

  override def applyCommand: Command[Reply] ⇒ Result = { (cmd: Command[Reply]) ⇒
    cmd match {
      case _: Command.JoinUser ⇒ NoEvent(ValidationRejection(""))
      case Command.PostText(_, sender, receiver, content, _) ⇒
        PersistEvent(
          ChatRoomEvent.UserTextAdded(
            sender,
            lastSequenceNr,
            receiver,
            content,
            System.currentTimeMillis(),
            TimeZone.getDefault.getID
          )
        )
      case _: Command.PostTexts       ⇒ NoEvent(ValidationRejection(""))
      case _: Command.Leave           ⇒ NoEvent(ValidationRejection(""))
      case _: Command.HandOffChatRoom ⇒ NoEvent(ValidationRejection(""))
    }
  }

  override def applyEvent: (ChatRoomState, ChatRoomEvent) ⇒ ChatRoomState = {
    (prevState: ChatRoomState, e: ChatRoomEvent) ⇒
      e match {
        case _: ChatRoomEvent.UserJoined       ⇒ prevState
        case _: ChatRoomEvent.UserTextAdded    ⇒ prevState
        case _: ChatRoomEvent.UserTextsAdded   ⇒ prevState
        case _: ChatRoomEvent.UserDisconnected ⇒ prevState
      }
  }

  override def onRecoveryCompleted(rState: ChatRoomState): Unit =
    log.info("Recovered {}", rState)
}
