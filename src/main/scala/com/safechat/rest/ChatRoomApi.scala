// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat.rest

import akka.http.scaladsl.server._
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.actor.typed.scaladsl.adapter._
import akka.NotUsed
import akka.stream.scaladsl.{Flow, RestartFlow}
import akka.actor.typed.ActorSystem
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.stream.{ActorAttributes, OverflowStrategy, StreamRefAttributes}
import com.safechat.actors.{ChatRoomEntity, ChatRoomReply, JoinReply, JoinUser, KeepAlive, ShardedChatRooms}

import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.concurrent.Future

class ChatRoomApi(rooms: ShardedChatRooms)(implicit sys: ActorSystem[Nothing]) extends RestApi {
  implicit val cx  = sys.executionContext
  implicit val sch = sys.scheduler

  //wake up ChatRoom shard region using a fake user
  //sharding would start a new entity on first message sent to it.
  sch.scheduleOnce(
    200.millis,
    () ⇒
      rooms
        .joinChatRoom(ChatRoomEntity.wakeUpEntityName, ChatRoomEntity.wakeUpUserName, "fake-pub-key")
        .mapTo[ChatRoomReply]
        .flatMap(_ ⇒ rooms.disconnect(ChatRoomEntity.wakeUpEntityName, ChatRoomEntity.wakeUpUserName))
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
      .joinChatRoom(chatId, user, pubKey)
      .mapTo[JoinReply]
      .recoverWith {
        case NonFatal(_) ⇒
          getChatRoomFlow(rooms, chatId, user, pubKey)
      }

  /**
    * As long as at least one connection is opened to the chat room, the associated persistent entity won't be passivated.
    *
    * Downsides: online users count is wrong ???
    */
  val routes: Route =
    (path("chat" / Segment / "user" / Segment) & parameter("pub".as[String])) { (chatId, user, pubKey) ⇒
      //Maybe smth like Retry form https://www.infoq.com/presentations/squbs/
      val flow = RestartFlow.withBackoff(1.second, 5.second, 0.3) { () ⇒
        val f = getChatRoomFlow(rooms, chatId, user, pubKey)
          .map { reply ⇒
            Flow.fromMaterializer { (mat, attr) ⇒
              //val ec: ExecutionContextExecutor = mat.executionContext
              //val dis                          = attr.get[ActorAttributes.Dispatcher].get
              val buf = attr.get[akka.stream.Attributes.InputBuffer].get
              //println(attr.attributeList.mkString(","))

              Flow
                .fromSinkAndSourceCoupled(reply.sinkRef.sink, reply.sourceRef.source)
                .buffer(buf.max, OverflowStrategy.backpressure)
                .watchTermination() { (_, c) ⇒
                  c.flatMap { _ ⇒
                    sys.log.info("ws-con for {}@{} has been terminated", user, chatId)
                    rooms.disconnect(chatId, user)
                  }
                  NotUsed
                }
            }
          }
        /*
        Flow.fromSinkAndSourceCoupled(
          drainInput,
          RestartSource.withBackoff(2.second, 5.second, 0.3) { () =>
            Source.futureSource(master.ask[SourceRef[String]](Master.ConnectIntoWs(_)).map(_.source.map(TextMessage.Strict)))
          }
        )
         */

        //TODO: check if it works. If not, then replace it with Source.futureSource(???) and Sink.futureSink(???)
        RestartFlow.withBackoff(2.second, 5.second, 0.3)(() ⇒ Flow.futureFlow(f))
      }

      handleWebSocketMessages(flow)
    } ~ ClusterHttpManagementRoutes(akka.cluster.Cluster(sys.toClassic))

  val route0: Route =
    (path("chat" / Segment / "user" / Segment) & parameter("key".as[String])) { (chatId, user, pubKey) ⇒
      //(rooms ? JoinChatRoom(chatId, user)).mapTo[JoinReply]
      val f = getChatRoomFlow(rooms, chatId, user, pubKey)
      onComplete(f) {
        case scala.util.Success(reply) ⇒
          /*Flow.fromMaterializer { (mat, attr) ⇒
            attr.attributeList.mkString(",")
            val ec: ExecutionContextExecutor = mat.executionContext
            val parallelism = mat.system.settings.config.getInt("max-into-parallelism")
            ???
          }*/

          val flow = Flow
            .fromSinkAndSourceCoupled(reply.sinkRef.sink, reply.sourceRef.source)
            .watchTermination() { (_, c) ⇒
              c.flatMap { _ ⇒
                sys.log.info("Flow for {}@{} has been terminated", user, chatId)
                rooms.disconnect(chatId, user)
              }
              NotUsed
            }
          handleWebSocketMessages(flow)
        case scala.util.Failure(err) ⇒
          complete(err.toString)
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
    }*/

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
}
