// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat.actors

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.ws.Message
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.ReplyEffect
import akka.stream.SinkRef
import akka.stream.SourceRef
import akka.stream.UniqueKillSwitch
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import com.safechat.actors.ChatRoomEvent.UserJoined
import com.safechat.actors.Command.HandOffChatRoom
import com.safechat.actors.Command.JoinUser
import com.safechat.actors.Command.Leave
import com.safechat.actors.Command.PostText
import com.safechat.actors.Command.PostTexts
import com.safechat.domain.RingBuffer

import scala.collection.mutable

/*
  Making T contravariant in ActorRef implies that
  for two types JoinReply and Reply where JoinReply is a subtype of Reply, ActorRef[Reply] is a subtype of ActorRef[JoinReply]
  Which means, whenever you see ActorRef[Reply] you can pass ActorRef[JoinReply] in.

  val a: ActorRef[JoinReply] = null.asInstanceOf[ActorRef[Reply]]
  val b: ActorRef[Reply] = null.asInstanceOf[ActorRef[JoinReply]] //error unless .narrow[Reply]
 */

sealed trait Reply {
  def chatId: String
  def seqNum: Long
}

object Reply {

  final case class JoinReply(
    chatId: String,
    user: String,
    sinkSourceRef: Option[(SinkRef[Message], SourceRef[Message])],
    seqNum: Long = 0L
  ) extends Reply

  final case class TextPostedReply(chatId: String, seqNum: Long, userId: String, recipient: String, content: String)
      extends Reply

  final case class LeaveReply(chatId: String, user: String, seqNum: Long = 0L) extends Reply

  final case class TextsPostedReply(chatId: String, seqNum: Long) extends Reply

}

sealed trait Command[+T <: Reply] {
  type R <: T
  type Event <: ChatRoomEvent

  def chatId: String
  def replyTo: ActorRef[R]
  def correspondingEvent(event: Event): Event = event
}

object Command {

  final case class JoinUser(
    chatId: String,
    user: String,
    pubKey: String,
    replyTo: ActorRef[Reply.JoinReply]
  ) extends Command[Reply.JoinReply] {
    override type Event = ChatRoomEvent.UserJoined
    override val toString = s"JoinUser($chatId, $user, $pubKey)"
  }

  final case class PostText(
    chatId: String,
    sender: String,
    receiver: String,
    content: String,
    replyTo: ActorRef[Reply.TextPostedReply]
  ) extends Command[Reply.TextPostedReply] {
    override type Event = ChatRoomEvent.UserTextAdded
    override val toString = s"PostText($chatId, $sender, $receiver)"
  }

  final case class PostTexts(
    chatId: String,
    content: Seq[Content],
    replyTo: ActorRef[Reply.TextsPostedReply]
  ) extends Command[Reply.TextsPostedReply] {
    override type Event = ChatRoomEvent.UserTextsAdded
    override val toString = s"PostTexts($chatId, ${content.size})"
  }

  final case class Leave(
    chatId: String,
    user: String,
    replyTo: ActorRef[Reply.LeaveReply]
  ) extends Command[Reply.LeaveReply] {
    override type Event = ChatRoomEvent.UserDisconnected
    override val toString = s"Leave($chatId, $user)"
  }

  //The message that will be sent to entities when they are to be stopped for a rebalance or graceful shutdown of a ShardRegion, e.g. PoisonPill.
  final case class HandOffChatRoom(
    chatId: String = null,
    user: String = null,
    replyTo: ActorRef[Nothing] = null //akka.actor.ActorRef.noSender.toTyped[Nothing]
  ) extends Command[Nothing] {
    override type Event = Nothing
    override val toString = "HandOffChatRoom"
  }

  val handOffChatRoom = HandOffChatRoom()
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
  //def userId: String
  //def seqNum: Long
}

object ChatRoomEvent {

  final case class UserJoined(userId: String, seqNum: Long, pubKey: String) extends ChatRoomEvent

  final case class UserTextAdded(
    userId: String,
    seqNum: Long,
    recipient: String,
    content: String,
    when: Long,
    tz: String
  ) extends ChatRoomEvent

  final case class UserTextsAdded(
    seqNum: Long,
    msgs: Seq[Content],
    when: Long,
    tz: String
  ) extends ChatRoomEvent

  final case class UserDisconnected(userId: String) extends ChatRoomEvent
}

final case class ChatRoomHub(sinkHub: Sink[Message, NotUsed], srcHub: Source[Message, NotUsed], ks: UniqueKillSwitch)

final case class Content(userId: String, recipient: String, content: String)

//https://doc.akka.io/docs/akka/current/typed/style-guide.html#functional-versus-object-oriented-style
final case class ChatRoomState(
  users: mutable.Map[String, String] = mutable.Map.empty,
  usersOnline: mutable.Set[String] = mutable.Set.empty,
  recentHistory: RingBuffer[String],
  hub: Option[ChatRoomHub] = None,
  commandsWithoutCheckpoint: Int = 0
) {

  def applyCmd(cmd: Command[Reply]): ReplyEffect[ChatRoomEvent, ChatRoomState] =
    cmd match {
      case c: JoinUser        ⇒ Effect.persist(UserJoined(c.user, -1, c.pubKey)).thenNoReply()
      case _: PostText        ⇒ Effect.noReply
      case _: PostTexts       ⇒ Effect.noReply
      case _: Leave           ⇒ Effect.noReply
      case _: HandOffChatRoom ⇒ Effect.noReply
    }

  def applyEvent(event: ChatRoomEvent): ChatRoomState =
    event match {
      case _: ChatRoomEvent.UserJoined       ⇒ ???
      case _: ChatRoomEvent.UserTextAdded    ⇒ ???
      case _: ChatRoomEvent.UserDisconnected ⇒ ???
      //case Null                ⇒ ???
    }
}
