// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat

import akka.Done
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route

import scala.util.{Failure, Success}
import scala.concurrent.duration._
import akka.actor.{Address, CoordinatedShutdown}
import akka.actor.CoordinatedShutdown.{PhaseActorSystemTerminate, PhaseBeforeServiceUnbind, PhaseClusterExitingDone, PhaseServiceRequestsDone, PhaseServiceStop, PhaseServiceUnbind, Reason}
import akka.coordination.lease.cassandra.CassandraLease
import akka.coordination.lease.scaladsl.{Lease, LeaseProvider}
import akka.management.scaladsl.AkkaManagement

import scala.concurrent.Future
import akka.stream.scaladsl.Sink

import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

object Bootstrap {
  final private case object BindFailure extends Reason

  def makeLeaseOwner(classicSystem: akka.actor.ActorSystem, addr: Address) = {
    val sb = new java.lang.StringBuilder().append(classicSystem.name)
    if (addr.host.isDefined) sb.append('@').append(addr.host.get)
    if (addr.port.isDefined) sb.append(':').append(addr.port.get)
    sb.toString
  }

}

case class Bootstrap(
  routes: Route,
  host: String,
  port: Int,
  liveRooms: AtomicReference[scala.collection.immutable.Set[String]]
)(implicit
  classicSystem: akka.actor.ActorSystem
) {
  implicit val ec = classicSystem.dispatcher
  val ua          = akka.cluster.Cluster(classicSystem).selfUniqueAddress
  val shutdown    = CoordinatedShutdown(classicSystem)
  val terminationDeadline = classicSystem.settings.config
    .getDuration("akka.coordinated-shutdown.default-phase-timeout")
    .getSeconds
    .second

  //safe-chat@127.0.0.1:2550

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

      shutdown.addTask(PhaseServiceUnbind, "akka-management.stop") { () ⇒
        AkkaManagement(classicSystem).stop().map { done ⇒
          classicSystem.log.info("★ ★ ★ CoordinatedShutdown [akka-management.stop]  ★ ★ ★")
          done
        }
      }

      shutdown.addTask(PhaseClusterExitingDone, "lease.release") { () ⇒
        val leaseOwner = Bootstrap.makeLeaseOwner(classicSystem, ua.address)
        val rooms      = liveRooms.get()
        if (rooms.nonEmpty) {
          classicSystem.log.info(s"★ ★ ★ CoordinatedShutdown [lease.release] - Rooms [${rooms.mkString(",")}] ★ ★ ★")

          Future
            .traverse(rooms) { roomName ⇒
              val lease = LeaseProvider(classicSystem).getLease(roomName, CassandraLease.configPath, leaseOwner)
              release(lease, roomName, leaseOwner)
                .recoverWith { case NonFatal(_) ⇒
                  akka.pattern.after(200.millis, classicSystem.scheduler)(release(lease, roomName, leaseOwner))
                }
            }
            .map(_ ⇒ Done)
        } else Future.successful(Done)
      }

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
