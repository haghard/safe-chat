// Copyright (c) 2018-19 by Haghard. All rights reserved.

package com.safechat.rest

import akka.http.scaladsl.server._
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.actor.typed.scaladsl.adapter._
import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.actor.typed.ActorSystem
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import com.safechat.actors.{ChatRoomEntity, JoinChatRoom, JoinReply, ShardedChats}

import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.concurrent.{Await, Future}

class ChatRoomApi(rooms: ShardedChats)(implicit sys: ActorSystem[Nothing]) extends RestApi {
  implicit val cx  = sys.executionContext
  implicit val sch = sys.scheduler

  //wake up ChatRoom shard region using a fake user
  sch.scheduleOnce(
    200.millis, { () ⇒
      (rooms ? JoinChatRoom(ChatRoomEntity.wakeUpEntityName, ChatRoomEntity.wakeUpUserName))
        .mapTo[JoinReply]
        .flatMap { _ ⇒
          rooms.disconnect(ChatRoomEntity.wakeUpEntityName, ChatRoomEntity.wakeUpUserName)
        }
    }
  )

  private def getChatRoomFlow(rooms: ShardedChats, m: JoinChatRoom): Future[JoinReply] =
    (rooms ? m)
      .mapTo[JoinReply]
      .recoverWith {
        case NonFatal(_) ⇒
          getChatRoomFlow(rooms, m)
      }

  val routes: Route =
    path("chat" / Segment / "user" / Segment) { (chatId, user) ⇒
      /**
        * As long as at least one client connected to the chat room, the associated persistent entity won't be passivated.
        *
        * Downsides:
        *   online user count is currently wrong
        *
        */
      val flow = akka.stream.scaladsl.RestartFlow.withBackoff(1.second, 5.second, 0.3) { () ⇒
        val f = getChatRoomFlow(rooms, JoinChatRoom(chatId, user))
          .map { reply ⇒
            Flow.fromSinkAndSourceCoupled(reply.sinkRef.sink, reply.sourceRef.source)
              .watchTermination() { (_, c) ⇒
                c.flatMap { _ ⇒
                  sys.log.info("Flow for {}@{} has been terminated", user, chatId)
                  rooms.disconnect(chatId, user)
                }
                NotUsed
              }
          }
        //TODO: remove blocking
        Await.result(f, Duration.Inf)
      }
      handleWebSocketMessages(flow)
    } ~ ClusterHttpManagementRoutes(akka.cluster.Cluster(sys.toClassic))

  val route0: Route =
    path("chat" / Segment / "user" / Segment) { (chatId, user) ⇒
      //(rooms ? JoinChatRoom(chatId, user)).mapTo[JoinReply]
      val f = getChatRoomFlow(rooms, JoinChatRoom(chatId, user))
      onComplete(f) {
        case scala.util.Success(reply) ⇒
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
