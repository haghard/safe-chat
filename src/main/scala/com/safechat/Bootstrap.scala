// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat

import akka.Done
import akka.actor.Address
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.PhaseActorSystemTerminate
import akka.actor.CoordinatedShutdown.PhaseBeforeServiceUnbind
import akka.actor.CoordinatedShutdown.PhaseServiceRequestsDone
import akka.actor.CoordinatedShutdown.PhaseServiceStop
import akka.actor.CoordinatedShutdown.PhaseServiceUnbind
import akka.actor.CoordinatedShutdown.Reason
import akka.coordination.lease.scaladsl.Lease
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.management.scaladsl.AkkaManagement
import akka.stream.UniqueKillSwitch
import akka.stream.scaladsl.Sink

import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

object Bootstrap {
  final private case object BindFailure extends Reason

  def makeLeaseOwner(classicSystem: akka.actor.ActorSystem, addr: Address) = {
    val sb = new java.lang.StringBuilder().append(classicSystem.name)
    if (addr.host.isDefined) sb.append('@').append(addr.host.get)
    if (addr.port.isDefined) sb.append(':').append(addr.port.get)
    sb.toString
  }
}

final case class Bootstrap(
  routes: Route,
  httpBindHostName: String,
  port: Int,
  localShards: AtomicReference[immutable.Set[String]],
  kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]]
)(implicit classicSystem: akka.actor.ActorSystem) {
  implicit val ec = classicSystem.dispatcher
  val shutdown    = CoordinatedShutdown(classicSystem)
  val terminationDeadline = classicSystem.settings.config
    .getDuration("akka.coordinated-shutdown.default-phase-timeout")
    .getSeconds
    .second

  val f = Http()
    .newServerAt(httpBindHostName, port)
    .connectionSource()
    .to(Sink.foreach { con ⇒
      classicSystem.log.info("Accept connection from {}", con.remoteAddress)
      con.handleWith(routes)
    })
    .run()

  //val f = Http().newServerAt(host, port).bindFlow(routes)

  f.onComplete {
    case Failure(ex) ⇒
      classicSystem.log.error(s"Shutting down because can't bind to $httpBindHostName:$port", ex)
      shutdown.run(Bootstrap.BindFailure)
    case Success(binding) ⇒
      classicSystem.log.info(s"★ ★ ★ Listening for HTTP connections on ${binding.localAddress} * * *")
      shutdown.addTask(PhaseBeforeServiceUnbind, "before-unbind") { () ⇒
        Future {
          classicSystem.log.info("★ ★ ★ CoordinatedShutdown [before-unbind] ★ ★ ★")
          Done
        }
      }

      //PhaseServiceUnbind - stop accepting new req
      shutdown.addTask(PhaseServiceUnbind, "http-api.unbind") { () ⇒
        //No new connections are accepted. Existing connections are still allowed to perform request/response cycles
        binding.unbind().map { done ⇒
          classicSystem.log.info("★ ★ ★ CoordinatedShutdown [http-api.unbind] ★ ★ ★")
          done
        }
      }

      //PhaseServiceRequestsDone - process in-flight requests

      //graceful termination of chatroom hubs
      shutdown.addTask(PhaseServiceRequestsDone, "kss.shutdown") { () ⇒
        Future.successful {
          val kks = kksRef.get()
          classicSystem.log.info(s"★ ★ ★ CoordinatedShutdown [kss.shutdown:${kks.size}]  ★ ★ ★")
          kks.foreach(_.shutdown())
          Done
        }
      }

      //graceful termination of requests being handled on this connection
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

      //forcefully kills connections and kill switches that are still open
      shutdown.addTask(PhaseServiceStop, "close.connections") { () ⇒
        val kks = kksRef.get()
        kks.foreach(_.abort(new Exception("abort")))
        Http().shutdownAllConnectionPools().map { _ ⇒
          classicSystem.log.info("★ ★ ★ CoordinatedShutdown [close.connections] ★ ★ ★")
          Done
        }
      }

      /*cShutdown.addTask(PhaseServiceStop, "stop.smth") { () =>
        smth.stop().map { _ =>
          classicSystem.log.info("CoordinatedShutdown [stop.smth]")
          Done
        }
      }*/

      shutdown.addTask(PhaseServiceUnbind, "akka-management.stop") { () ⇒
        AkkaManagement(classicSystem).stop().map { done ⇒
          classicSystem.log.info("★ ★ ★ CoordinatedShutdown [akka-management.stop]  ★ ★ ★")
          done
        }
      }

      //if akka.sharding.use-lease
      /*shutdown.addTask(PhaseClusterExitingDone, "lease.release") { () ⇒
        val leaseOwner = Bootstrap.makeLeaseOwner(classicSystem, ua.address)
        val rooms      = liveRooms.get()
        if (rooms.nonEmpty) {
          classicSystem.log.info(s"★ ★ ★ CoordinatedShutdown [lease.release] - Rooms [${rooms.mkString(",")}] ★ ★ ★")

          Future
            .traverse(rooms) { roomName ⇒
              val lease = akka.coordination.lease.scaladsl.LeaseProvider(classicSystem).getLease(roomName, CassandraLease.configPath, leaseOwner)
              release(lease, roomName, leaseOwner)
                .recoverWith { case NonFatal(_) ⇒
                  akka.pattern.after(200.millis, classicSystem.scheduler)(release(lease, roomName, leaseOwner))
                }
            }
            .map(_ ⇒ Done)
        } else Future.successful(Done)
      }*/

      shutdown.addTask(PhaseActorSystemTerminate, "system.term") { () ⇒
        Future.successful {
          classicSystem.log.info("★ ★ ★ CoordinatedShutdown [system.term] ★ ★ ★")
          Done
        }
      }
  }

  def release(lease: Lease, leaseName: String, leaseOwner: String) =
    lease
      .release()
      .flatMap { r ⇒
        classicSystem.log.warning(s"CoordinatedShutdown [lease.release $leaseName by $leaseOwner: $r]")
        if (r) Future.successful(Done) else Future.failed(new Exception("Failed to release"))
      }

}
