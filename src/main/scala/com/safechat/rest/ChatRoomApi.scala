// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat.rest

import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server._
import akka.actor.typed.scaladsl.adapter._
import akka.NotUsed
import akka.stream.scaladsl.{Flow, RestartFlow}
import akka.actor.typed.ActorSystem
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.stream.{ActorAttributes, OverflowStrategy}
import com.safechat.actors.{ChatRoomEntity, ChatRoomReply, JoinReply, ShardedChatRooms}

import scala.concurrent.duration._
import scala.concurrent.Future

case class ChatRoomApi(rooms: ShardedChatRooms)(implicit sys: ActorSystem[Nothing]) extends Directives {
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
            .enter(ChatRoomEntity.wakeUpEntityName, ChatRoomEntity.wakeUpUserName, "fake-pub-key")
            .mapTo[ChatRoomReply]
            .flatMap(_ ⇒ rooms.disconnect(ChatRoomEntity.wakeUpEntityName, ChatRoomEntity.wakeUpUserName)),
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
      .enter(chatId, user, pubKey)
      .mapTo[JoinReply]
      .recoverWith(_ ⇒ getChatRoomFlow(rooms, chatId, user, pubKey))

  private def chatRoomWsFlow(
    rooms: ShardedChatRooms,
    chatId: String,
    user: String,
    pubKey: String
  ): Future[Flow[Message, Message, Future[NotUsed]]] =
    getChatRoomFlow(rooms, chatId, user, pubKey)
      .map { reply ⇒
        Flow.fromMaterializer { (mat, attr) ⇒
          //val ec: ExecutionContextExecutor = mat.executionContext
          //val disp                        = attr.get[ActorAttributes.Dispatcher].get
          val buf = attr.get[akka.stream.Attributes.InputBuffer].get
          //println("attributes: " + attr.attributeList.mkString(","))

          Flow
            .fromSinkAndSourceCoupled(reply.sinkRef.sink, reply.sourceRef.source)
            .buffer(buf.max, OverflowStrategy.backpressure)
            .backpressureTimeout(3.seconds) //automatic cleanup for very slow subscribers.
            .watchTermination() { (_, c) ⇒
              c.flatMap { _ ⇒
                sys.log.info("{}@{}: ws-con has been terminated", user, chatId)
                rooms.disconnect(chatId, user)
              }
              NotUsed
            }
        }
      }

  /**
    * As long as at least one connection is opened to the chat room, the associated persistent entity won't be passivated.
    *
    * Downsides: online users count is wrong ???
    */
  val routes: Route =
    (path("chat" / Segment / "user" / Segment) & parameter("pub".as[String])) { (chatId, user, pubKey) ⇒
      val flow =
        //When ChatRoomEntities get rebalanced, a flow(src, sink) we got once may no longed be valid so we need to restart that transparently for users
        //TODO: When reconnect, filter out recent chat history
        RestartFlow.withBackoff(ChatRoomEntity.hubInitTimeout, ChatRoomEntity.hubInitTimeout, 0.4)(() ⇒
          Flow.futureFlow(chatRoomWsFlow(rooms, chatId, user, pubKey))
        )
      handleWebSocketMessages(flow)
    } ~ ClusterHttpManagementRoutes(akka.cluster.Cluster(sys.toClassic))
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
//TODO: try it out

/*get {
  extractRequest { req ⇒
    //authorizeAsync(reqCtx ⇒ ???)
    authenticateOrRejectWithChallenge(auth(_)) { user ⇒
      //user
      handleWebSocketMessages(???)
    }
  }
}*/
