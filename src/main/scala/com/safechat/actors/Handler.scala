package com.safechat.actors

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.StreamRefAttributes
import akka.stream.UniqueKillSwitch
import akka.stream.javadsl.StreamRefs
import akka.stream.scaladsl.Source

import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration

sealed trait Handler[C <: Command[_]] {

  def apply(cmd: C, event: C#Event, state: ChatRoomState)(implicit
    sys: ActorSystem[Nothing],
    totalFailoverTimeout: FiniteDuration,
    kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]]
  ): ChatRoomState

}

object Handler {

  implicit val a = new Handler[Command.JoinUser] {
    def apply(cmd: Command.JoinUser, event: ChatRoomEvent.UserJoined, state: ChatRoomState)(implicit
      sys: ActorSystem[Nothing],
      totalFailoverTimeout: FiniteDuration,
      kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]]
    ) = {
      val newState =
        if (state.online.isEmpty && state.hub.isEmpty) {
          if (event.userId == ChatRoom.wakeUpUserName) state
          else
            state.copy(
              regUsers = state.regUsers + (event.userId → event.pubKey),
              online = Set(event.userId),
              hub = Some(ChatRoomClassic.chatRoomHub(cmd.chatId, kksRef))
            )
        } else
          state.copy(
            regUsers = state.regUsers + (event.userId → event.pubKey),
            online = state.online + event.userId
          )

      val reply = newState.hub match {
        case Some(hub) ⇒
          val settings      = StreamRefAttributes.subscriptionTimeout(totalFailoverTimeout)
          val recentHistory = newState.recentHistory.entries.mkString("\n")
          val srcRef = (Source.single[Message](TextMessage(recentHistory)) ++ hub.srcHub)
            .runWith(StreamRefs.sourceRef[Message].addAttributes(settings))
          val sinkRef = hub.sinkHub.runWith(StreamRefs.sinkRef[Message].addAttributes(settings))
          Reply.JoinReply(cmd.chatId, event.userId, Some((sinkRef, srcRef)))
        case None ⇒
          Reply.JoinReply(cmd.chatId, event.userId, None)
      }
      cmd.replyTo.tell(reply)
      newState
    }
  }

  implicit val b = new Handler[Command.PostText] {
    def apply(cmd: Command.PostText, event: ChatRoomEvent.UserTextAdded, state: ChatRoomState)(implicit
      sys: ActorSystem[Nothing],
      totalFailoverTimeout: FiniteDuration,
      kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]]
    ) = {
      state.recentHistory :+ ChatRoomClassic.msg(cmd.chatId, event.seqNum, event.userId, event.recipient, event.content)
      val reply = Reply.TextPostedReply(cmd.chatId, event.seqNum, event.userId, event.recipient, event.content)
      cmd.replyTo.tell(reply)
      state
    }
  }

  implicit val c = new Handler[Command.Leave] {
    def apply(cmd: Command.Leave, event: ChatRoomEvent.UserDisconnected, state: ChatRoomState)(implicit
      sys: ActorSystem[Nothing],
      totalFailoverTimeout: FiniteDuration,
      kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]]
    ) = {
      val reply = Reply.LeaveReply(cmd.chatId, event.userId)
      cmd.replyTo.tell(reply)
      state.copy(online = state.online - event.userId)
    }
  }

  def apply[C <: Command[_]](c: C, e: C#Event, s: ChatRoomState)(implicit
    h: Handler[C],
    sys: ActorSystem[Nothing],
    totalFailoverTimeout: FiniteDuration,
    kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]]
  ) = h(c, e, s)
}
