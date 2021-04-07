// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat.rest

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server._
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.RestartFlow
import akka.stream.scaladsl.Source
import com.safechat.actors._

import scala.concurrent.Future
import scala.concurrent.duration._

import ExtraDirectives._

final case class ChatRoomApi(rooms: ShardedChatRooms, to: FiniteDuration)(implicit
  sys: ActorSystem[Nothing]
) extends Directives {
  implicit val cx         = sys.executionContext
  implicit val sch        = sys.scheduler
  implicit val classicSys = sys.toClassic
  implicit val classicSch = classicSys.scheduler

  /*
    Wake up ChatRoom shard region using a fake user.  We need this because sharding would start a new entity on first message sent to it.
    sch.scheduleOnce(
    500.millis,
    () ⇒
      akka.pattern.retry(
        () ⇒
          rooms
            .join(ChatRoom.wakeUpEntityName, ChatRoom.wakeUpUserName, "fake-pub-key")
            .mapTo[Reply]
            .flatMap(_ ⇒ rooms.leave(ChatRoom.wakeUpEntityName, ChatRoom.wakeUpUserName)),
        3,
        1.second
      )
  )*/

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
  ): Future[Reply.JoinReply] =
    rooms
      .join(chatId, user, pubKey)
      .mapTo[Reply.JoinReply]
  //.recoverWith { case scala.util.control.NonFatal(ex) ⇒ getChatRoomFlow(rooms, chatId, user, pubKey) }

  private def chatRoomWsFlow(
    rooms: ShardedChatRooms,
    chatId: String,
    user: String,
    pubKey: String
  ): Future[Flow[Message, Message, Future[NotUsed]]] =
    getChatRoomFlow(rooms, chatId, user, pubKey).map { case Reply.JoinReply(chatId, user, sinkSourceRef, _) ⇒
      sinkSourceRef match {
        case Some((sinkRef, sourceRef)) ⇒
          Flow.fromMaterializer { (mat, attr) ⇒
            //val ec: ExecutionContextExecutor = mat.executionContext
            //val disp                        = attr.get[ActorAttributes.Dispatcher].get
            //println("attributes: " + attr.attributeList.mkString(","))

            /*
            cannot create top-level actor from the outside on ActorSystem with custom user guardian !!!!!!
            val room = sys.toClassic.actorOf(Room.props(chatId))
            Flow[Message]
              //the websocket spec says that a single msg over web socket can be streamed (multiple chunks)
              .flatMapConcat(_.asTextMessage.getStreamedText.fold("")(_ + _))
              .groupedWithin(128, 100.second)
              .mapAsync(4) { batchOfStr ⇒
                implicit val t = akka.util.Timeout(2.seconds)
                room.ask(batchOfStr).mapTo[akka.Done]
              //Future { /*ask bulk insert*/ () }(mat.executionContext)
              }*/

            /*import akka.actor.typed.scaladsl.AskPattern._
            val f: Future[ActorRef[Int]] = sys
              .narrow[Int]
              .ask { ref: ActorRef[ActorRef[Nothing]] ⇒
                SpawnProtocol.Spawn(Behaviors.same[Int], "room-manager", akka.actor.typed.Props.empty, ref)
              }(akka.util.Timeout(3.seconds), sys.scheduler)

            sys.narrow[Int]
              .tell(SpawnProtocol.Spawn(Behaviors.same[Int], "room-manager", akka.actor.typed.Props.empty, sys.ignoreRef))
             */

            val buf = attr.get[akka.stream.Attributes.InputBuffer].get
            Flow
              .fromSinkAndSourceCoupled(
                sinkRef.sink,
                sourceRef.source
              )
              //
              .mergePreferred(
                Source.tick(30.seconds, 40.seconds, TextMessage.Strict("none:none:commercial")),
                false,
                true
              )
              .buffer(buf.max, OverflowStrategy.backpressure)
              .backpressureTimeout(3.seconds) //automatic cleanup for very slow subscribers.
              .watchTermination() { (_, done) ⇒
                done.flatMap { _ ⇒
                  classicSys.log.info("{}@{} flow has been terminated", user, chatId)
                  rooms.leave(chatId, user)
                }
                NotUsed
              }
          }

        case None ⇒ throw new Exception(s"$chatId join failure !")
      }
    }

  /**  As long as at least one connection is opened to the chat room, the associated persistent entity won't be passivated.
    */
  val routes: Route =
    extractLog { implicit log ⇒
      (path("chat" / Segment / "user" / Segment) & parameter("pub".as[String])) { (chatId, user, pubKey) ⇒
        val flow =
          //When ChatRoom entities get rebalanced, a flow(src, sink) we've got once may no longed be working so we need to restart it transparently for the clients
          RestartFlow.withBackoff(akka.stream.RestartSettings(2.seconds, 4.seconds, 0.4))(() ⇒
            Flow.futureFlow(chatRoomWsFlow(rooms, chatId, user, pubKey))
          )
        //responds with 101 "Switching Protocols"
        aroundRequest(logLatency(log))(handleWebSocketMessages(flow))
      }
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
