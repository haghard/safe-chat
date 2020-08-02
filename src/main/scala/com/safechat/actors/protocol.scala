// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat.actors

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.stream.scaladsl.{Sink, Source}
import akka.http.scaladsl.model.ws.Message
import akka.stream.{SinkRef, SourceRef, UniqueKillSwitch}
import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}
import com.safechat.domain.RingBuffer

sealed trait ChatRoomReply {
  def chatId: String
}

final case class JoinReply(
  chatId: String,
  user: String,
  sinkRef: SinkRef[Message],
  sourceRef: SourceRef[Message]
) extends ChatRoomReply

final case class ReconnectReply(
  chatId: String,
  user: String,
  sinkRef: SinkRef[Message]
) extends ChatRoomReply

final case class JoinReplyFailure(chatId: String, user: String) extends ChatRoomReply

final case class TextPostedReply(chatId: String, seqNum: Long, content: String) extends ChatRoomReply
final case class PingReply(chatId: String, msg: String)                         extends ChatRoomReply
final case class DisconnectedReply(chatId: String, user: String)                extends ChatRoomReply

sealed trait UserCmd {
  def chatId: String
}

final case class PingShard(chatId: String, replyTo: ActorRef[KeepAlive.Probe]) extends UserCmd

//sealed trait UserCmdWithReply[-T <: ChatRoomReply] extends UserCmd {
sealed trait UserCmdWithReply extends UserCmd {
  def replyTo: ActorRef[ChatRoomReply]
}

final case class JoinUser(chatId: String, user: String, pubKey: String, replyTo: ActorRef[ChatRoomReply])
    extends UserCmdWithReply

final case class PostText(
  chatId: String,
  sender: String,
  receiver: String,
  content: String,
  replyTo: ActorRef[ChatRoomReply]
) extends UserCmdWithReply

final case class DisconnectUser(chatId: String, user: String, replyTo: ActorRef[ChatRoomReply]) extends UserCmdWithReply

final case class ChatRoomHub(sinkHub: Sink[Message, NotUsed], srcHub: Source[Message, NotUsed], ks: UniqueKillSwitch)

final case class ChatRoomState(
  regUsers: Map[String, String] = Map.empty,
  online: Set[String] = Set.empty,
  recentHistory: RingBuffer[String] = new RingBuffer[String](1 << 4),
  hub: Option[ChatRoomHub] = None
) {

  import com.safechat.persistent.domain.MsgEnvelope
  def applyCmd(cmd: UserCmd): ReplyEffect[MsgEnvelope, ChatRoomState] =
    cmd match {
      case m: JoinUser       ⇒ Effect.noReply
      case m: PostText       ⇒ Effect.noReply
      case m: DisconnectUser ⇒ Effect.noReply
      case m: PingShard      ⇒ Effect.noReply
    }

  def applyEvn(env: MsgEnvelope): ChatRoomState = ???

}
