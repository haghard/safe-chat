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
import com.safechat.domain.RingBuffer

import scala.collection.mutable

//////////////////////////// Domain  //////////////////////////////////////////////

final case class ChatId(value: String) extends AnyVal
final case class UserId(value: String) extends AnyVal

//newtypes (Tagged types)
object ChatId1 {
  trait Tag

  type T      = String
  type Opaque = Base with Tag
  type Base   = Any { type Bla }

  object Opaque {
    // This casting's happening in compile time, in runtime only Long exists
    def apply(v: T): Opaque                = v.asInstanceOf[Opaque]
    def unapply(userId: Opaque): Option[T] = Option(userId).map(_.value)
  }

  final implicit class Ops(private val chatId: Opaque) extends AnyVal {
    def value: T = chatId.asInstanceOf[T]
  }
}

object UserId1 {
  trait Tag

  type T      = String
  type Opaque = Base with Tag
  type Base   = Any { type Bla }

  object Opaque {
    def apply(v: T): Opaque                = v.asInstanceOf[Opaque]
    def unapply(userId: Opaque): Option[T] = Option(userId).map(_.value)
  }

  final implicit class Ops(private val userId: Opaque) extends AnyVal {
    def value: T = userId.asInstanceOf[T]
  }
}

/*
  Making T contravariant in ActorRef implies that
  for two types JoinReply and Reply where JoinReply is a subtype of Reply, ActorRef[Reply] is a subtype of ActorRef[JoinReply]
  Which means, whenever you see ActorRef[Reply] you can pass ActorRef[JoinReply] in.

  val a: ActorRef[JoinReply] = null.asInstanceOf[ActorRef[Reply]]
  val b: ActorRef[Reply] = null.asInstanceOf[ActorRef[JoinReply]] //error unless .narrow[Reply]
 */

sealed trait Reply {
  def chatId: ChatId
  def seqNum: Long
}

object Reply {

  final case class JoinReply(
    chatId: ChatId,
    user: UserId,
    sinkSourceRef: Option[(SinkRef[Message], SourceRef[Message])],
    seqNum: Long = 0L
  ) extends Reply

  final case class TextPostedReply(chatId: ChatId, seqNum: Long, sender: UserId, recipient: UserId, content: String)
      extends Reply

  final case class LeaveReply(chatId: ChatId, user: UserId, seqNum: Long = 0L) extends Reply

  final case class TextsPostedReply(chatId: ChatId, seqNum: Long) extends Reply

}

sealed trait Command[+T <: Reply] {
  type R <: T
  type Event <: ChatRoomEvent

  def chatId: ChatId
  def replyTo: ActorRef[R]

  //
  def coerce(event: Event): Event = event
}

object Command {

  final case class JoinUser(
    chatId: ChatId,
    user: UserId,
    pubKey: String,
    replyTo: ActorRef[Reply.JoinReply]
  ) extends Command[Reply.JoinReply] {
    type Event = ChatRoomEvent.UserJoined
    override val toString = s"JoinUser($chatId, $user, $pubKey)"
  }

  final case class PostText(
    chatId: ChatId,
    sender: UserId,
    receiver: UserId,
    content: String,
    replyTo: ActorRef[Reply.TextPostedReply]
  ) extends Command[Reply.TextPostedReply] {
    type Event = ChatRoomEvent.UserTextAdded
    override val toString = s"PostText($chatId, $sender, $receiver)"
  }

  final case class Leave(
    chatId: ChatId,
    user: UserId,
    replyTo: ActorRef[Reply.LeaveReply]
  ) extends Command[Reply.LeaveReply] {
    type Event = ChatRoomEvent.UserDisconnected
    override val toString = s"Leave($chatId, $user)"
  }

  // The message that will be sent to entities when they are to be stopped for a re-balance or graceful shutdown of a ShardRegion, e.g. PoisonPill.
  final case class HandOffChatRoom(
    chatId: ChatId = ChatId("null"),
    user: UserId = UserId("null"),
    replyTo: ActorRef[Nothing] = null // akka.actor.ActorRef.noSender.toTyped[Nothing]
  ) extends Command[Nothing] {
    type Event = Nothing
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
  // def userId: UserId
  // def seqNum: Long
}

object ChatRoomEvent {

  final case class UserJoined(userId: UserId, seqNum: Long, pubKey: String) extends ChatRoomEvent

  final case class UserTextAdded(
    userId: UserId,
    seqNum: Long,
    recipient: UserId,
    content: String,
    when: Long,
    tz: String
  ) extends ChatRoomEvent

  final case class UserDisconnected(userId: UserId) extends ChatRoomEvent
}

final case class ChatRoomHub(
  sinkHub: Sink[Message, NotUsed],
  srcHub: Source[Message, NotUsed],
  ks: UniqueKillSwitch
)

//https://doc.akka.io/docs/akka/current/typed/style-guide.html#functional-versus-object-oriented-style
final case class ChatRoomState(
  users: mutable.Map[UserId, String] = mutable.Map.empty, // user -> pubKey
  usersOnline: mutable.Set[UserId] = mutable.Set.empty,
  recentHistory: RingBuffer[String],
  hub: Option[ChatRoomHub] = None,
  commandsWithoutCheckpoint: Int = 0,
  openConnectionsPerUser: mutable.Map[UserId, Short] = mutable.Map.empty // to limit open connections per user
) {

  def applyCmd(cmd: Command[Reply]): ReplyEffect[ChatRoomEvent, ChatRoomState] =
    cmd match {
      case c: JoinUser        => Effect.persist(UserJoined(c.user, -1, c.pubKey)).thenNoReply()
      case _: PostText        => Effect.noReply
      case _: Leave           => Effect.noReply
      case _: HandOffChatRoom => Effect.noReply
    }

  def applyEvent(event: ChatRoomEvent): ChatRoomState =
    event match {
      case _: ChatRoomEvent.UserJoined       => ???
      case _: ChatRoomEvent.UserTextAdded    => ???
      case _: ChatRoomEvent.UserDisconnected => ???
      // case Null                ⇒ ???
    }
}

/*

final case class ChatRoomState(
  users: List[User],
  currentIssue: String,
  issueLastEditBy: Option[UUID]
) {
  def joinUser(user: User): ChatRoomState =
  def vote(userId: UUID, estimation: String): ChatRoomState =
  def clear(): ChatRoomState =
  def leave(userId: UUID): RoomData =
  def editIssue(issue: String, userId: UUID): RoomData =
}

object RoomData {
  val empty: RoomData = RoomData(List.empty[User], "", Option.empty[UUID])
}

 */
