// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat

import java.io.File
import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import java.util.TimeZone

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.{Cluster, SelfUp, Unsubscribe}
import com.typesafe.config.{Config, ConfigFactory}
import akka.actor.typed.scaladsl.adapter._

import scala.jdk.CollectionConverters._
import com.safechat.actors.ShardedChatRooms
import com.safechat.rest.ChatRoomApi

import scala.collection.Map
import scala.util.Try

object Server extends Ops {

  val Dispatcher     = "akka.actor.default-dispatcher"
  val AkkaSystemName = "safe-chat"

  def guardian(config: Config, hostName: String, httpPort: Int): Behavior[Nothing] =
    Behaviors
      .setup[SelfUp] { ctx ⇒
        implicit val sys = ctx.system
        val cluster      = Cluster(ctx.system)
        cluster.subscriptions ! akka.cluster.typed.Subscribe(ctx.self, classOf[SelfUp])

        Behaviors.receive { (ctx, _) ⇒
          //ctx.log.info(info)
          cluster.subscriptions ! Unsubscribe(ctx.self)

          new Bootstrap(new ChatRoomApi(new ShardedChatRooms).routes, hostName, httpPort)(sys.toClassic)
          Behaviors.empty
        }
      }
      .narrow

  def main(args: Array[String]): Unit = {
    val opts: Map[String, String] = argsToOpts(args.toList)
    applySystemProperties(opts)

    val confPath = Option(System.getProperty("CONFIG")).getOrElse(throw new Exception("CONFIG env var is expected"))
    val env      = Option(System.getProperty("ENV")).getOrElse(throw new Exception("ENV env var is expected"))

    val akkaExternalHostName = Option(System.getProperty("akka.remote.artery.canonical.hostname"))
      .getOrElse(throw new Exception("akka.remote.artery.canonical.hostname is expected"))

    //Inside the Docker container we bind to all available network interfaces
    val dockerAddr = internalAddr.map(_.getHostAddress).getOrElse("0.0.0.0")

    val akkaPort = Try(System.getProperty("akka.remote.artery.canonical.port").toInt)
      .getOrElse(throw new Exception("akka.remote.artery.canonical.port is expected"))

    val httpPort =
      Try(System.getProperty("HTTP_PORT").trim.toInt).getOrElse(throw new Exception("HTTP_PORT is expected"))

    val akkaSeeds = Option(System.getProperty("SEEDS")).map { seeds ⇒
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

    val dbPsw =
      Option(System.getProperty("cassandra.psw")).getOrElse(throw new Exception("cassandra.psw env var is expected"))
    val dbUser =
      Option(System.getProperty("cassandra.user")).getOrElse(throw new Exception("cassandra.user env var is expected"))

    val dbConf = Option(System.getProperty("cassandra.hosts")).map { hs ⇒
      val contactPoints = hs.split(",").map(h ⇒ s""" "$h" """).mkString(",").dropRight(1)
      ConfigFactory.parseString(
        s"""
           |cassandra-journal.contact-points = [ $contactPoints ]
           |cassandra-snapshot-store.contact-points = [ $contactPoints ]
           |
           |cassandra-journal.authentication.username = $dbUser
           |cassandra-snapshot-store.authentication.username = $dbUser
           |
           |cassandra-journal.authentication.password = $dbPsw
           |cassandra-snapshot-store.authentication.password = $dbPsw
           |
          """.stripMargin
      )
    }

    val cfg: Config = {
      val general = ConfigFactory.empty()
      val local   = dbConf.fold(general)(c ⇒ general.withFallback(c))
      akkaSeeds
        .fold(local)(c ⇒ local.withFallback(c))
        .withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.bind-hostname=$dockerAddr"))
        .withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.bind-port=$akkaPort"))
        .withFallback(ConfigFactory.parseString(s"akka.management.cluster.http.host=$akkaExternalHostName"))
        .withFallback(ConfigFactory.parseString(s"akka.management.cluster.http.port=$akkaPort"))
        .withFallback(ConfigFactory.parseFile(configFile).resolve())
        .withFallback(ConfigFactory.load())
    }

    //check dispatcher name
    cfg.getObject(Dispatcher)

    val system =
      akka.actor.typed.ActorSystem[Nothing](guardian(cfg, akkaExternalHostName, httpPort), AkkaSystemName, cfg)

    val greeting = showGreeting(
      cfg,
      httpPort,
      akkaExternalHostName,
      cfg.getStringList("akka.cluster.seed-nodes").asScala.mkString(", ")
    )
    system.log.info(greeting)
  }

  def showGreeting(cfg: Config, httpPort: Int, host: String, seedNodes: String): String = {
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
      .append(s"★ ★ ★   Cassandra: ${cfg.getStringList("cassandra-journal.contact-points").asScala.mkString(",")} ")
      .append('\n')
      .append(s"Partition size: ${cfg.getInt("cassandra-journal.target-partition-size")} ★ ★ ★")
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
      .append(s"Version:${buildinfo.BuildInfo.version}")

    val memorySize = ManagementFactory.getOperatingSystemMXBean
      .asInstanceOf[com.sun.management.OperatingSystemMXBean]
      .getTotalPhysicalMemorySize
    val runtimeInfo = new StringBuilder()
      .append('\n')
      .append(s"Cores:${Runtime.getRuntime.availableProcessors}")
      .append(" Total Memory:" + Runtime.getRuntime.totalMemory / 1000000 + "Mb")
      .append(" Max Memory:" + Runtime.getRuntime.maxMemory / 1000000 + "Mb")
      .append(" Free Memory:" + Runtime.getRuntime.freeMemory / 1000000 + "Mb")
      .append(" RAM:" + memorySize / 1000000 + "Mb")
      .append('\n')
      .append("=================================================================================================")

    info.toString + runtimeInfo.toString
  }
}
