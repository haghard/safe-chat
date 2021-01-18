// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat.actors

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.ws.Message
import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{SinkRef, SourceRef, UniqueKillSwitch}
import com.safechat.actors.Command.{DisconnectUser, JoinUser, PostText}
import com.safechat.domain.RingBuffer

sealed trait Reply {
  def chatId: String
}

sealed trait JoinReply extends Reply

final case class ReconnectReply(
  chatId: String,
  user: String,
  sinkRef: SinkRef[Message]
) extends Reply

final case class JoinReplySuccess(
  chatId: String,
  user: String,
  sinkRef: SinkRef[Message],
  sourceRef: SourceRef[Message]
) extends JoinReply

final case class JoinReplyFailure(chatId: String, user: String) extends JoinReply

final case class TextPostedReply(chatId: String, seqNum: Long, content: String) extends Reply

final case class DisconnectedReply(chatId: String, user: String) extends Reply

/*
Option 1.

sealed trait Command0[+R <: Reply] {
  def chatId: String
  def replyTo: ActorRef[Reply]
}

final case class JoinUser0(
  chatId: String, user: String,
  pubKey: String, replyTo: ActorRef[Reply]
) extends Command0[JoinReply]

 */

/*
Option 2.

trait ReplyModule {
  type Reply
}

sealed trait Command0[M <: ReplyModule] {
  def chatId: String
  def replyTo: ActorRef[M#Reply]
}

abstract sealed trait JRM extends ReplyModule {
  override type Reply = JoinReply
}

final case class JoinUser0(
  chatId: String,
  user: String,
  pubKey: String,
  replyTo: ActorRef[JRM#Reply]
) extends Command0[JRM]
 */

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

sealed trait ChatRoomEvent {
  def originator: String
}

final case class UserJoined(originator: String, pubKey: String) extends ChatRoomEvent
final case class UserTextAdded(originator: String, recipient: String, content: String, when: Long, tz: String)
    extends ChatRoomEvent
final case class UserDisconnected(originator: String) extends ChatRoomEvent

final case class ChatRoomHub(sinkHub: Sink[Message, NotUsed], srcHub: Source[Message, NotUsed], ks: UniqueKillSwitch)

case object Null extends ChatRoomEvent {
  override def originator: String = ""
}

final case class ChatRoomState(
  regUsers: Map[String, String] = Map.empty,
  online: Set[String] = Set.empty,
  recentHistory: RingBuffer[String] = new RingBuffer[String](1 << 4),
  hub: Option[ChatRoomHub] = None
) {

  def applyCmd(cmd: Command): ReplyEffect[ChatRoomEvent, ChatRoomState] =
    cmd match {
      case c: JoinUser       ⇒ Effect.persist(UserJoined(c.user, c.pubKey)).thenNoReply()
      case _: PostText       ⇒ Effect.noReply
      case _: DisconnectUser ⇒ Effect.noReply
    }

  def applyEvn(event: ChatRoomEvent): ChatRoomState =
    event match {
      case _: UserJoined       ⇒ ???
      case _: UserTextAdded    ⇒ ???
      case _: UserDisconnected ⇒ ???
      case Null                ⇒ ???
    }
}
