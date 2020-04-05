// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat

import akka.Done
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route

import scala.util.{Failure, Success}
import scala.concurrent.duration._
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.{PhaseServiceRequestsDone, PhaseServiceUnbind, Reason}
//import akka.actor.typed.ActorSystem
import akka.stream.scaladsl.Sink
import akka.actor.typed.scaladsl.adapter._

object Bootstrap {
  final private case object BindFailure extends Reason
}

class Bootstrap(routes: Route, host: String, port: Int)(
  implicit system: akka.actor.ActorSystem
) {

  implicit val ex = system.dispatcher

  val terminationDeadline = 4.seconds
  val shutdown            = CoordinatedShutdown(system)

  val f = Http()
    .bind(host, port)
    .to(Sink.foreach { con ⇒
      system.log.info("Accept from {}", con.remoteAddress)
      con.handleWith(routes)
    })
    .run()

  //val f = Http().bindAndHandle(routes, host, port)

  f.onComplete {
    case Failure(ex) ⇒
      system.log.error(s"Shutting down because can't bind to $host:$port", ex)
      shutdown.run(Bootstrap.BindFailure)
    case Success(binding) ⇒
      system.log.info(s"Listening for HTTP connections on ${binding.localAddress}")
      shutdown.addTask(PhaseServiceUnbind, "api.unbind") { () ⇒
        system.log.info("api.unbind")
        // No new connections are accepted
        // Existing connections are still allowed to perform request/response cycles
        binding.terminate(terminationDeadline).map(_ ⇒ Done)
      }

      //graceful termination request being handled on this connection
      shutdown.addTask(PhaseServiceRequestsDone, "http-api.terminate") { () ⇒
        system.log.info("http-api.terminate")
        //It doesn't accept new connection but it drains the existing connections
        //Until the terminationDeadline all the req that have been accepted will be completed
        //and only that the shutdown will continue
        binding.terminate(terminationDeadline).map(_ ⇒ Done)
      }
  }
}
