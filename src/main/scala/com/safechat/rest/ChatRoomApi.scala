// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat.rest

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server._
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, RestartFlow}
import com.safechat.actors._

import scala.concurrent.Future
import scala.concurrent.duration._

final case class ChatRoomApi(rooms: ShardedChatRooms, to: FiniteDuration)(implicit sys: ActorSystem[Nothing])
    extends Directives {
  implicit val cx         = sys.executionContext
  implicit val sch        = sys.scheduler
  implicit val classicSch = sys.toClassic.scheduler

  //Wake up ChatRoom shard region using a fake user
  //sharding would start a new entity on first message sent to it.
  sch.scheduleOnce(
    500.millis,
    () ⇒
      akka.pattern.retry(
        () ⇒
          rooms
            .join(ChatRoomEntity.wakeUpEntityName, ChatRoomEntity.wakeUpUserName, "fake-pub-key")
            .mapTo[Reply]
            .flatMap(_ ⇒ rooms.leave(ChatRoomEntity.wakeUpEntityName, ChatRoomEntity.wakeUpUserName)),
        3,
        1.second
      )
  )

  //web socket flow
  /*
  Flow[Message]
    .flatMapConcat(_.asTextMessage.getStreamedText.fold("")(_+_)) //the websocket spec says that a single msg over web socket can be streamed (multiple chunks)
    .groupedWithin(1000, 1.second)
    .mapAsync(1) { msg => Future { /*bulk insert*/ 1 }  }
   */

  private def getChatRoomFlow(
    rooms: ShardedChatRooms,
    chatId: String,
    user: String,
    pubKey: String
  ): Future[JoinReply] =
    rooms
      .join(chatId, user, pubKey)
      .mapTo[JoinReply]
  //.recoverWith { case scala.util.control.NonFatal(ex) ⇒ getChatRoomFlow(rooms, chatId, user, pubKey) }

  private def chatRoomWsFlow(
    rooms: ShardedChatRooms,
    chatId: String,
    user: String,
    pubKey: String
  ): Future[Flow[Message, Message, Future[NotUsed]]] =
    getChatRoomFlow(rooms, chatId, user, pubKey).map { case JoinReply(chatId, user, sinkSourceRef) ⇒
      sinkSourceRef match {
        case Some((sinkRef, sourceRef)) ⇒
          Flow.fromMaterializer { (mat, attr) ⇒
            //val ec: ExecutionContextExecutor = mat.executionContext
            //val disp                        = attr.get[ActorAttributes.Dispatcher].get
            val buf = attr.get[akka.stream.Attributes.InputBuffer].get
            //println("attributes: " + attr.attributeList.mkString(","))

            Flow
              .fromSinkAndSourceCoupled(sinkRef.sink, sourceRef.source)
              .buffer(buf.max, OverflowStrategy.backpressure)
              .backpressureTimeout(3.seconds) //automatic cleanup for very slow subscribers.
              .watchTermination() { (_, c) ⇒
                c.flatMap { _ ⇒
                  sys.log.info("{}@{} flow has been terminated", user, chatId)
                  rooms.leave(chatId, user)
                }
                NotUsed
              }
          }
        case None ⇒
          throw new Exception("JoinReplyFailure !!!")
      }
    }

  /** As long as at least one connection is opened to the chat room, the associated persistent entity won't be passivated.
    *
    * Downsides: online users count is wrong ???
    */
  val routes: Route =
    (path("chat" / Segment / "user" / Segment) & parameter("pub".as[String])) { (chatId, user, pubKey) ⇒
      val flow =
        //When ChatRoomEntities get rebalanced, a flow(src, sink) we got once may no longed be valid so we need to restart it transparently for the clients
        //TODO: When reconnect, filter out recent chat history
        //to, to + 1.seconds
        RestartFlow.withBackoff(akka.stream.RestartSettings(2.seconds, 4.seconds, 0.4))(() ⇒
          Flow.futureFlow(chatRoomWsFlow(rooms, chatId, user, pubKey))
        )
      handleWebSocketMessages(flow)
    }
}

/*def auth(credentials: Option[HttpCredentials]): Future[AuthenticationResult[User]] =
  Future {
    credentials.fold[AuthenticationResult[User]](AuthenticationResult.failWithChallenge(HttpChallenge("", ???))) {
      cr ⇒
        cr.token()
        cr.params
        AuthenticationResult.success[User](User("11111", "haghard"))
    }
}
 */

//https://gist.github.com/johanandren/964672acc37b84caca40
//https://discuss.lightbend.com/t/authentication-in-websocket-connections/4174
//https://stackoverflow.com/questions/22383089/is-it-possible-to-use-bearer-authentication-for-websocket-upgrade-requests
/*get {
  extractRequest { req ⇒
    //authorizeAsync(reqCtx ⇒ ???)
    authenticateOrRejectWithChallenge(auth(_)) { user ⇒
      //user
      handleWebSocketMessages(???)
    }
  }
}*/
