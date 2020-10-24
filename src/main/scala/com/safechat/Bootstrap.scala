// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat

import akka.Done
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route

import scala.util.{Failure, Success}
import scala.concurrent.duration._
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.{PhaseActorSystemTerminate, PhaseBeforeServiceUnbind, PhaseServiceRequestsDone, PhaseServiceStop, PhaseServiceUnbind, Reason}
import akka.management.scaladsl.AkkaManagement

import scala.concurrent.Future
import akka.stream.scaladsl.Sink

object Bootstrap {
  final private case object BindFailure extends Reason
}

case class Bootstrap(routes: Route, host: String, port: Int)(implicit
  classicSystem: akka.actor.ActorSystem
) {
  implicit val ec = classicSystem.dispatcher
  val shutdown    = CoordinatedShutdown(classicSystem)
  val terminationDeadline = classicSystem.settings.config
    .getDuration("akka.coordinated-shutdown.default-phase-timeout")
    .getSeconds
    .second

  val f = Http()
    .newServerAt(host, port)
    .connectionSource()
    .to(Sink.foreach { con ⇒
      classicSystem.log.info("Accept connection from {}", con.remoteAddress)
      con.handleWith(routes)
    })
    .run()

  //val f = Http().newServerAt(host, port).bindFlow(routes)

  f.onComplete {
    case Failure(ex) ⇒
      classicSystem.log.error(s"Shutting down because can't bind to $host:$port", ex)
      shutdown.run(Bootstrap.BindFailure)
    case Success(binding) ⇒
      classicSystem.log.info(s"★ ★ ★ Listening for HTTP connections on ${binding.localAddress} * * *")
      shutdown.addTask(PhaseBeforeServiceUnbind, "before-unbind") { () ⇒
        Future {
          classicSystem.log.info("★ ★ ★ CoordinatedShutdown [before-unbind] ★ ★ ★")
          Done
        }
      }

      shutdown.addTask(PhaseServiceUnbind, "http-api.unbind") { () ⇒
        //No new connections are accepted. Existing connections are still allowed to perform request/response cycles
        binding.unbind().map { done ⇒
          classicSystem.log.info("★ ★ ★ CoordinatedShutdown [http-api.unbind] ★ ★ ★")
          done
        }
      }

      shutdown.addTask(PhaseServiceUnbind, "akka-management.stop") { () ⇒
        AkkaManagement(classicSystem).stop().map { done ⇒
          classicSystem.log.info("★ ★ ★ CoordinatedShutdown [akka-management.stop]  ★ ★ ★")
          done
        }
      }

      //graceful termination request being handled on this connection
      shutdown.addTask(PhaseServiceRequestsDone, "http-api.terminate") { () ⇒
        /** It doesn't accept new connection but it drains the existing connections
          * Until the `terminationDeadline` all the req that have been accepted will be completed
          * and only than the shutdown will continue
          */
        binding.terminate(terminationDeadline).map { _ ⇒
          classicSystem.log.info("★ ★ ★ CoordinatedShutdown [http-api.terminate]  ★ ★ ★")
          Done
        }
      }

      //forcefully kills connections that are still open
      shutdown.addTask(PhaseServiceStop, "close.connections") { () ⇒
        Http().shutdownAllConnectionPools().map { _ ⇒
          classicSystem.log.info("★ ★ ★ CoordinatedShutdown [close.connections] ★ ★ ★")
          Done
        }
      }

      shutdown.addTask(PhaseActorSystemTerminate, "system.term") { () ⇒
        Future.successful {
          classicSystem.log.info("★ ★ ★ CoordinatedShutdown [system.term] ★ ★ ★")
          Done
        }
      }
  }
}
