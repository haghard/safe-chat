// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat

import java.io.File
import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import java.util.TimeZone
import akka.actor.typed.{ActorSystem, Behavior, DispatcherSelector}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.{Cluster, SelfUp, Unsubscribe}
import com.typesafe.config.{Config, ConfigFactory}
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.server.directives.Credentials
import akka.stream.UniqueKillSwitch

import scala.jdk.CollectionConverters._
import com.safechat.actors.{ShardedChatRoomClassic, ShardedChatRooms}
import com.safechat.rest.ChatRoomApi
import com.safechat.serializer.SchemaRegistry

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.collection.{immutable, Map}
import scala.concurrent.{Await, Future}
import scala.io.StdIn
import scala.util.Try
import scala.concurrent.duration._

object Server extends Ops {

  val AkkaSystemName = "safe-chat"

  def guardian(hostName: String, httpPort: Int): Behavior[Nothing] =
    Behaviors
      .setup[SelfUp] { ctx ⇒
        implicit val sys = ctx.system
        val cluster      = Cluster(ctx.system)
        cluster.subscriptions ! akka.cluster.typed.Subscribe(ctx.self, classOf[SelfUp])

        Behaviors.receive { (ctx, _) ⇒
          //ctx.log.info(info)
          cluster.subscriptions ! Unsubscribe(ctx.self)

          val localShards =
            new AtomicReference[scala.collection.immutable.Set[String]](scala.collection.immutable.Set[String]())

          //stable-after * 2 = 10
          //https://doc.akka.io/docs/akka-enhancements/current/split-brain-resolver.html#expected-failover-time
          val totalFailoverTimeout = Duration.fromNanos(
            sys.settings.config
              .getDuration("akka.cluster.split-brain-resolver.stable-after")
              .multipliedBy(2)
              //.plus(java.time.Duration.ofSeconds(4))
              .toNanos
          )

          val kksRef =
            new AtomicReference[scala.collection.immutable.Set[UniqueKillSwitch]](
              scala.collection.immutable.Set[UniqueKillSwitch]()
            )

          Bootstrap(
            ChatRoomApi(
              new ShardedChatRooms(localShards, kksRef, totalFailoverTimeout)(sys),
              totalFailoverTimeout
            ).routes,
            hostName,
            httpPort,
            localShards,
            kksRef
          )(sys.toClassic)
          Behaviors.empty
        }
      }
      .narrow

  def main(args: Array[String]): Unit = {
    val opts: Map[String, String] = argsToOpts(args.toList)
    applySystemProperties(opts)

    val confPath = sys.props.get("CONFIG").getOrElse(throw new Exception("Env var CONFIG is expected"))
    val env      = sys.props.get("ENV").getOrElse(throw new Exception("Env var ENV is expected"))
    val discoveryMethod =
      sys.props.get("DISCOVERY_METHOD").getOrElse(throw new Exception("Env var DISCOVERY_METHOD is expected"))

    val akkaExternalHostName = sys.props
      .get("HOSTNAME")
      .getOrElse(throw new Exception("Env var HOSTNAME is expected"))

    //Inside the Docker container we bind to all available network interfaces
    val dockerAddr = internalAddr.map(_.getHostAddress).getOrElse("0.0.0.0")

    val akkaPort = sys.props
      .get("AKKA_PORT")
      .flatMap(r ⇒ Try(r.toInt).toOption)
      .getOrElse(throw new Exception("Env var AKKA_PORT is expected"))

    val httpPort = sys.props
      .get("HTTP_PORT")
      .flatMap(r ⇒ Try(r.toInt).toOption)
      .getOrElse(throw new Exception("HTTP_PORT is expected"))

    val akkaSeeds = sys.props.get("SEEDS").map { seeds ⇒
      val seedNodesString = seeds
        .split(",")
        .map { node ⇒
          val ap = node.split(":")
          s"""akka.cluster.seed-nodes += "akka://$AkkaSystemName@${ap(0)}:${ap(1)}""""
        }
        .mkString("\n")
      (ConfigFactory parseString seedNodesString).resolve
    }

    val configFile = new File(s"${new File(confPath).getAbsolutePath}/" + env + ".conf")

    val dbPsw  = sys.props.get("cassandra.psw").getOrElse(throw new Exception("cassandra.psw env var is expected"))
    val dbUser = sys.props.get("cassandra.user").getOrElse(throw new Exception("cassandra.user env var is expected"))

    val dbConf = sys.props.get("cassandra.hosts").map { hosts ⇒
      val contactPointsString = hosts
        .split(",")
        .map { hostPort ⇒
          val ap = hostPort.split(":")
          s"""datastax-java-driver.basic.contact-points += "${ap(0)}:${ap(1)}""""
        }
        .mkString("\n")
      (ConfigFactory parseString contactPointsString).resolve
        .withFallback(ConfigFactory.parseString(s"datastax-java-driver.advanced.auth-provider.username=$dbUser"))
        .withFallback(ConfigFactory.parseString(s"datastax-java-driver.advanced.auth-provider.password=$dbPsw"))
    }

    val cfg: Config = {
      //https://doc.akka.io/docs/akka-management/current/akka-management.html
      val managementConf =
        s"""
          |akka.management {
          |  http {
          |     hostname = $akkaExternalHostName
          |     port = 8558
          |
          |     #bind-hostname = $dockerAddr
          |     bind-port = 8558
          |  }
          |  cluster {
          |     bootstrap {
          |       contact-point-discovery {
          |         # config|kubernetes-api
          |         discovery-method = $discoveryMethod
          |       }
          |     }
          |  }
          |}
          |""".stripMargin

      val general = ConfigFactory.empty()
      val local   = dbConf.fold(general)(c ⇒ general.withFallback(c))
      akkaSeeds
        .fold(local)(c ⇒ local.withFallback(c))
        .withFallback(ConfigFactory.parseString(s"akka.remote.artery.canonical.bind.hostname=$dockerAddr"))
        .withFallback(ConfigFactory.parseString(s"akka.remote.artery.canonical.bind.port=$akkaPort"))
        .withFallback(ConfigFactory.parseString(s"akka.remote.artery.canonical.hostname=$akkaExternalHostName"))
        .withFallback(ConfigFactory.parseString(s"akka.remote.artery.canonical.port=$akkaPort"))
        .withFallback(ConfigFactory.parseString(managementConf))
        .withFallback(ConfigFactory.parseFile(configFile).resolve())
        .withFallback(pureconfig.ConfigSource.default.loadOrThrow[Config]) //.at(AkkaSystemName)
      //.withFallback(ConfigFactory.load())
    }

    //check dispatcher name
    //cfg.getObject(Dispatcher)

    val eventMapping =
      SchemaRegistry.journalEvents(cfg.getConfig("akka.actor.serialization-bindings"))

    val system =
      akka.actor.typed.ActorSystem[Nothing](guardian(akkaExternalHostName, httpPort), AkkaSystemName, cfg)

    val greeting = showGreeting(
      cfg,
      httpPort,
      akkaExternalHostName,
      cfg.getStringList("akka.cluster.seed-nodes").asScala.mkString(", "),
      eventMapping
    )
    system.log.info(greeting)

    // Akka Management hosts the HTTP routes used by bootstrap
    //akka.management.scaladsl.AkkaManagement(system).start(_.withAuth(basicAuth(system)))
    akka.management.scaladsl.AkkaManagement(system).start()

    // Starting the bootstrap process needs to be done explicitly
    akka.management.cluster.bootstrap.ClusterBootstrap(system.toClassic).start()

    //TODO: for debug only
    /*
    val _ = StdIn.readLine()
    system.log.warn("Shutting down ...")
    system.terminate()
    Await.result(
      system.whenTerminated,
      cfg.getDuration("akka.coordinated-shutdown.default-phase-timeout", TimeUnit.SECONDS).seconds
    )
     */
  }

  // http 127.0.0.1:8558/cluster/members "Authorization:Basic QWxhZGRpbjpPcGVuU2VzYW1l"
  private def basicAuth(sys: ActorSystem[Nothing])(credentials: Credentials): Future[Option[String]] =
    credentials match {
      case p @ Credentials.Provided(id) ⇒
        Future {
          if ((id == "Aladdin") && p.verify("OpenSesame")) Some(id) else None
        }(sys.executionContext) /*(sys.dispatchers.lookup(DispatcherSelector.fromConfig(Server.HttpDispatcher)))*/
      case _ ⇒ Future.successful(None)
    }

  def showGreeting(
    cfg: Config,
    httpPort: Int,
    host: String,
    seedNodes: String,
    eventMapping: Map[String, String]
  ): String = {
    //cfg.getDuration("akka.http.server.idle-timeout")
    //cfg.getDuration("akka.http.host-connection-pool.idle-timeout")

    val info = new StringBuilder()
      .append('\n')
      .append("=================================================================================================")
      .append('\n')
      .append(
        s"★ ★ ★   Node ${cfg.getString("akka.remote.artery.canonical.hostname")}:${cfg.getInt("akka.remote.artery.canonical.port")}   ★ ★ ★"
      )
      .append('\n')
      .append(s"★ ★ ★   Seed nodes: [$seedNodes]  ★ ★ ★")
      .append('\n')
      .append(
        s"★ ★ ★   Cassandra: ${cfg.getStringList("datastax-java-driver.basic.contact-points").asScala.mkString(",")} "
      )
      .append(
        s"  Journal partition size: ${cfg.getInt("akka.persistence.cassandra.journal.target-partition-size")} ★ ★ ★"
      )
      .append('\n')
      .append("★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★  Persistent events schema mapping ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★")
      .append('\n')
      .append(eventMapping.mkString("\n"))
      .append('\n')
      .append("★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★")
      .append('\n')
      .append(s"★ ★ ★   Environment: [TZ:${TimeZone.getDefault.getID}. Start time:${LocalDateTime.now}]  ★ ★ ★")
      .append('\n')
      .append(s"★ ★ ★   HTTP server is online: http://$host:$httpPort ★ ★ ★ ")
      .append('\n')
      .append(
        """
                                ___  ____   ___  __   __  ___   ___     ______
                               / __| | __| | _ \ \ \ / / | __| | _ \    \ \ \ \
                               \__ \ | _|  |   /  \ V /  | _|  |   /     ) ) ) )
                               |___/ |___| |_|_\   \_/   |___| |_|_\    /_/_/_/
        """
      )
      .append('\n')
      .append(
        s"★ ★ ★  Artery: maximum-frame-size: ${cfg.getBytes("akka.remote.artery.advanced.maximum-frame-size")} bytes  ★ ★ ★"
      )
      .append('\n')
      .append(s"Version:${BuildInfo.version} at ${BuildInfo.builtAtString}")

    val memorySize = ManagementFactory.getOperatingSystemMXBean
      .asInstanceOf[com.sun.management.OperatingSystemMXBean]
      .getTotalMemorySize()
    val runtimeInfo = new StringBuilder()
      .append('\n')
      .append(s"Cores:${Runtime.getRuntime.availableProcessors}")
      .append(" Total Memory:" + Runtime.getRuntime.totalMemory / 1000000 + "Mb")
      .append(" Max Memory:" + Runtime.getRuntime.maxMemory / 1000000 + "Mb")
      .append(" Free Memory:" + Runtime.getRuntime.freeMemory / 1000000 + "Mb")
      .append(" Total :" + memorySize / 1000000 + "Mb")
      .append('\n')
      .append("=================================================================================================")

    info.toString + runtimeInfo.toString
  }
}
