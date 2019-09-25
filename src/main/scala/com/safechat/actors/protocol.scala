package com.safechat.actors

import akka.http.scaladsl.model.ws.Message
import akka.stream.{SinkRef, SourceRef}
import akka.stream.scaladsl.Flow

sealed trait ChatCmd {
  def chatId: String
}

case class JoinChatRoom(chatId: String, user: String)   extends ChatCmd
case class AddText(chatId: String, text: String)        extends ChatCmd
case class DisconnectUser(chatId: String, user: String) extends ChatCmd

sealed trait Reply
case class WSReply(flow: Flow[Message, Message, akka.NotUsed])                   extends Reply
case class ChatHandler(sinkRef: SinkRef[Message], sourceRef: SourceRef[Message]) extends Reply
case class Disconnected(chatId: String, user: String)                            extends Reply

case class ChatRoomState(registredUsers: Set[String] = Set.empty)
