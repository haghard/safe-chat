// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat.actors

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.ws.Message
import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{SinkRef, SourceRef, UniqueKillSwitch}
import com.safechat.actors.ChatRoomEvent.{ChatRoomHub, UserJoined}
import com.safechat.actors.Command.{JoinUser, Leave, PostText}
import com.safechat.domain.RingBuffer

/*
  Making T contravariant in ActorRef implies that
  for two types JoinReply and Reply where JoinReply is a subtype of Reply, ActorRef[Reply] is a subtype of ActorRef[JoinReply]
  Which means, whenever you see ActorRef[Reply] you can pass ActorRef[JoinReply] in.

  val a: ActorRef[JoinReply] = null.asInstanceOf[ActorRef[Reply]]
  val b: ActorRef[Reply] = null.asInstanceOf[ActorRef[JoinReply]] //error unless .narrow[Reply]

 */
sealed trait Reply {
  def chatId: String
}

final case class ReconnectReply(
  chatId: String,
  user: String,
  sinkRef: SinkRef[Message]
) extends Reply

final case class JoinReply(
  chatId: String,
  user: String,
  sinkSourceRef: Option[(SinkRef[Message], SourceRef[Message])]
) extends Reply

final case class TextPostedReply(chatId: String, seqNum: Long, content: String) extends Reply

final case class LeaveReply(chatId: String, user: String) extends Reply

sealed trait Command[+R <: Reply] {
  type T <: R
  def chatId: String
  def replyTo: ActorRef[T]
}

object Command {

  final case class JoinUser(
    chatId: String,
    user: String,
    pubKey: String,
    replyTo: ActorRef[JoinReply]
  ) extends Command[JoinReply] {
    override val toString = s"JoinUser($chatId, $user, $pubKey)"
  }

  final case class PostText(
    chatId: String,
    sender: String,
    receiver: String,
    content: String,
    replyTo: ActorRef[TextPostedReply]
  ) extends Command[TextPostedReply] {
    override val toString = s"PostText($chatId, $sender, $receiver)"
  }

  final case class Leave(
    chatId: String,
    user: String,
    replyTo: ActorRef[LeaveReply]
  ) extends Command[LeaveReply] {
    override val toString = s"Leave($chatId, $user)"
  }

  //
  final case class StopChatRoom(
    chatId: String = null,
    user: String = null,
    replyTo: ActorRef[Nothing] = null //akka.actor.ActorRef.noSender.toTyped[Nothing]
  ) extends Command[Nothing] {
    override val toString = "StopChatRoom"
  }

  val Stop = StopChatRoom()

}

/*

//Option 1.

sealed trait Cmd[M <: ReplyModule] {
  def chatId: String
  def replyTo: ActorRef[M#R]
}

sealed trait ReplyModule {
  type R <: Reply
}

abstract sealed trait JoinR extends ReplyModule {
  override type R = JoinReply
}

final case class JoinUser0(
  chatId: String,
  user: String,
  pubKey: String,
  replyTo: ActorRef[JoinR#R]
) extends Cmd[JoinR]
 */

/*

//Option 2

sealed trait Command {
  type R <: Reply

  def chatId: String
  def replyTo: ActorRef[R]
}

object Command {

  final case class JoinUser(
    chatId: String,
    user: String,
    pubKey: String,
    replyTo: ActorRef[JoinReply]
  ) extends Command {
    override type R = JoinReply
  }

  final case class PostText(
    chatId: String,
    sender: String,
    receiver: String,
    content: String,
    replyTo: ActorRef[TextPostedReply]
  ) extends Command {
    override type R = TextPostedReply
  }

  final case class DisconnectUser(
    chatId: String,
    user: String,
    replyTo: ActorRef[DisconnectedReply]
  ) extends Command {
    override type R = DisconnectedReply
  }
}
 */

/*

// Option 3

sealed trait Command {
  def chatId: String
  def replyTo: ActorRef[Reply]
}

object Command {

  final case class JoinUser(
    chatId: String,
    user: String,
    pubKey: String,
    replyTo: ActorRef[Reply]
  ) extends Command

  final case class PostText(
    chatId: String,
    sender: String,
    receiver: String,
    content: String,
    replyTo: ActorRef[Reply]
  ) extends Command

  final case class Leave(
    chatId: String,
    user: String,
    replyTo: ActorRef[Reply]
  ) extends Command
}

}
 */

sealed trait ChatRoomEvent {
  def userId: String
}

object ChatRoomEvent {

  final case class UserJoined(userId: String, pubKey: String) extends ChatRoomEvent

  final case class UserTextAdded(
    seqNum: Long,
    userId: String,
    recipient: String,
    content: String,
    when: Long,
    tz: String
  ) extends ChatRoomEvent

  //com.safechat.actors.UserDisconnected
  //com.safechat.actors.ChatRoomEvent.UserDisconnected
  final case class UserDisconnected(userId: String) extends ChatRoomEvent

  final case class ChatRoomHub(sinkHub: Sink[Message, NotUsed], srcHub: Source[Message, NotUsed], ks: UniqueKillSwitch)
}

/*case object Null extends ChatRoomEvent {
  override def originator: String = ""
}*/

//https://doc.akka.io/docs/akka/current/typed/style-guide.html#functional-versus-object-oriented-style
final case class ChatRoomState(
  regUsers: Map[String, String] = Map.empty,
  online: Set[String] = Set.empty,
  recentHistory: RingBuffer[String] = RingBuffer[String](1 << 3),
  hub: Option[ChatRoomHub] = None
) {

  def applyCmd(cmd: Command[Reply]): ReplyEffect[ChatRoomEvent, ChatRoomState] =
    cmd match {
      case c: JoinUser ⇒ Effect.persist(UserJoined(c.user, c.pubKey)).thenNoReply()
      case _: PostText ⇒ Effect.noReply
      case _: Leave    ⇒ Effect.noReply
    }

  def applyEvent(event: ChatRoomEvent): ChatRoomState =
    event match {
      case _: ChatRoomEvent.UserJoined       ⇒ ???
      case _: ChatRoomEvent.UserTextAdded    ⇒ ???
      case _: ChatRoomEvent.UserDisconnected ⇒ ???
      //case Null                ⇒ ???
    }
}
