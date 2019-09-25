package com.safechat.actors

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.ws.Message
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{SinkRef, SourceRef, UniqueKillSwitch}
import com.safechat.domain.RingBuffer

sealed trait ChatRoomReply {
  def chatId: String
}

case class JoinReply(
  chatId: String,
  user: String,
  sinkRef: SinkRef[Message],
  sourceRef: SourceRef[Message]
) extends ChatRoomReply

case class TextPostedReply(chatId: String, seqNum: Long) extends ChatRoomReply
case class DisconnectReply(chatId: String, user: String) extends ChatRoomReply

sealed trait UserCmd {
  def chatId: String
  def replyTo: ActorRef[ChatRoomReply]
}

case class JoinUser(chatId: String, user: String, replyTo: ActorRef[ChatRoomReply]) extends UserCmd

case class PostText(chatId: String, user: String, text: String, replyTo: ActorRef[ChatRoomReply]) extends UserCmd

case class DeactivateUser(chatId: String, user: String, replyTo: ActorRef[ChatRoomReply]) extends UserCmd

case class ChatRoomHub(sinkHub: Sink[Message, NotUsed], srcHub: Source[Message, NotUsed], ks: UniqueKillSwitch)

case class FullChatState(
  regUsers: Set[String] = Set.empty,
  online: Set[String] = Set.empty,
  //recentHistory: RingBuffer[String],
  hub: Option[ChatRoomHub] = None
)
