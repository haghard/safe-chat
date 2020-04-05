// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat.actors

import akka.NotUsed
import akka.actor.typed.ActorRef
import com.safechat.domain.RingBuffer
import akka.stream.scaladsl.{Sink, Source}
import akka.http.scaladsl.model.ws.Message
import akka.stream.{SinkRef, SourceRef, UniqueKillSwitch}

sealed trait ChatRoomReply {
  def chatId: String
}

case class JoinReply(
  chatId: String,
  user: String,
  sinkRef: SinkRef[Message],
  sourceRef: SourceRef[Message]
) extends ChatRoomReply

case class JoinReplyFailure(chatId: String, user: String) extends ChatRoomReply

case class TextPostedReply(chatId: String, seqNum: Long) extends ChatRoomReply
case class PingReply(chatId: String, msg: String)        extends ChatRoomReply
case class DisconnectReply(chatId: String, user: String) extends ChatRoomReply

sealed trait UserCmd {
  def chatId: String
  def replyTo: ActorRef[ChatRoomReply]
}

case class JoinUser(chatId: String, user: String, pubKey: String, replyTo: ActorRef[ChatRoomReply]) extends UserCmd

case class PostText(chatId: String, sender: String, receiver: String, text: String, replyTo: ActorRef[ChatRoomReply])
    extends UserCmd

case class DisconnectUser(chatId: String, user: String, replyTo: ActorRef[ChatRoomReply]) extends UserCmd

case class ChatRoomHub(sinkHub: Sink[Message, NotUsed], srcHub: Source[Message, NotUsed], ks: UniqueKillSwitch)

case class FullChatState(
  regUsers: Map[String, String] = Map.empty,
  online: Set[String] = Set.empty,
  recentHistory: RingBuffer[String] = new RingBuffer[String](1 << 4),
  hub: Option[ChatRoomHub] = None
) {

  /*def applyCmd(cmd: UserCmd): ReplyEffect[MsgEnvelope, FullChatState] =
    cmd match {
      case m: JoinUser ⇒
        Effect
          .persist(
            new MsgEnvelope(
              UUID.randomUUID.toString,
              System.currentTimeMillis,
              TimeZone.getDefault.getID,
              Joined.newBuilder.setLogin(m.user).setPubKey(m.pubKey).build()
            )
          )
          .thenReply(cmd.replyTo) { _ ⇒
            ???
          }
    }

  def applyEvn(env: MsgEnvelope): FullChatState = ???
 */

}
