package com.safechat.actors

import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.StreamRefAttributes
import akka.stream.UniqueKillSwitch
import akka.stream.javadsl.StreamRefs
import akka.stream.scaladsl.Source
import com.safechat.Boot.AppCfg

import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration

sealed trait Handler[C <: Command[_]] {
  def apply(cmd: C, event: C#Event, state: ChatRoomState)(implicit
    sys: akka.actor.ActorSystem,
    failoverTimeout: FiniteDuration,
    kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]],
    appCfg: AppCfg
  ): ChatRoomState
}

object Handler {

  implicit object Join extends Handler[Command.JoinUser] {
    def apply(cmd: Command.JoinUser, event: ChatRoomEvent.UserJoined, state: ChatRoomState)(implicit
      sys: akka.actor.ActorSystem,
      failoverTimeout: FiniteDuration,
      kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]],
      appCfg: AppCfg
    ) = {
      val newState =
        if (state.usersOnline.isEmpty && state.hub.isEmpty)
          state.copy(hub =
            Some(ChatRoomClassic.chatRoomHub(cmd.chatId.value, appCfg.recentHistorySize, event.seqNum - 1, kksRef))
          )
        else state

      newState.users.put(event.userId, event.pubKey)
      newState.usersOnline.add(event.userId)

      val reply = newState.hub match {
        case Some(hub) ⇒
          val settings      = StreamRefAttributes.subscriptionTimeout(failoverTimeout)
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

  implicit object Post extends Handler[Command.PostText] {
    def apply(cmd: Command.PostText, event: ChatRoomEvent.UserTextAdded, state: ChatRoomState)(implicit
      sys: akka.actor.ActorSystem,
      failoverTimeout: FiniteDuration,
      kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]],
      appCfg: AppCfg
    ) = {
      state.recentHistory.add(
        ChatRoomClassic.msg(cmd.chatId.value, event.seqNum, event.userId.value, event.recipient.value, event.content)
      )
      val reply = Reply.TextPostedReply(cmd.chatId, event.seqNum, event.userId, event.recipient, event.content)
      cmd.replyTo.tell(reply)
      state
    }
  }

  implicit object Leave extends Handler[Command.Leave] {
    def apply(cmd: Command.Leave, event: ChatRoomEvent.UserDisconnected, state: ChatRoomState)(implicit
      sys: akka.actor.ActorSystem,
      failoverTimeout: FiniteDuration,
      kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]],
      appCfg: AppCfg
    ) = {
      val reply = Reply.LeaveReply(cmd.chatId, event.userId)
      cmd.replyTo.tell(reply)
      state.copy(usersOnline = state.usersOnline - event.userId)
    }
  }

  def apply[C <: Command[_]](c: C, e: C#Event, state: ChatRoomState)(implicit
    H: Handler[C],
    sys: akka.actor.ActorSystem,
    failoverTimeout: FiniteDuration,
    kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]],
    appCfg: AppCfg
  ) = H(c, e, state)
}
