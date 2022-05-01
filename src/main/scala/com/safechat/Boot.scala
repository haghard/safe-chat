// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.server.directives.Credentials
import com.safechat.actors.Guardian
import com.safechat.programs.SchemaRegistry
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import pureconfig.generic.auto.exportReader

import java.io.File
import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import java.util.TimeZone
import scala.collection.Map
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object Boot extends Ops {

  val AkkaSystemName = "safe-chat"

  val ENV_VAR            = "ENV"
  val HTTP_PORT_VAR      = "HTTP_PORT"
  val CONFIG_VAR         = "CONFIG"
  val CONTACT_POINTS_VAR = "CONTACT_POINTS"

  val CASSANDRA_VAR = "CASSANDRA"
  val CAS_USR_VAR   = "CAS_USR"
  val CAS_PSW_VAR   = "CAS_PWS"

  val dbDispatcher   = "cassandra-dispatcher"
  val httpDispatcher = "http-dispatcher"

  final case class AppCfg(passivationAfter: FiniteDuration, snapshotEvery: Int, recentHistorySize: Int)

  def main(args: Array[String]): Unit = {
    val opts: Map[String, String] = argsToOpts(args.toList)
    applySystemProperties(opts)

    val env = sys.props.get(ENV_VAR).getOrElse(throw new Exception(s"$ENV_VAR not found"))
    val configFile = sys.props
      .get(CONFIG_VAR)
      .map(confPath ⇒ new File(s"${new File(confPath).getAbsolutePath}/$env.conf"))
      .getOrElse(throw new Exception(s"Env var $CONFIG_VAR not found"))

    val akkaPort = sys.props
      .get("akka.remote.artery.canonical.port") //AKKA_PORT
      .flatMap(_.toIntOption)
      .getOrElse(throw new Exception("akka.remote.artery.canonical.port not found"))

    val httpPort = sys.props
      .get(HTTP_PORT_VAR)
      .flatMap(_.toIntOption)
      .getOrElse(throw new Exception(s"$HTTP_PORT_VAR not found"))

    /*val akkaSeeds = sys.props.get("SEEDS").map { seeds ⇒
      val seedNodesString = seeds
        .split(",")
        .map { node ⇒
          val ap = node.split(":")
          s"""akka.cluster.seed-nodes += "akka://$AkkaSystemName@${ap(0)}:${ap(1)}""""
        }
        .mkString("\n")
      (ConfigFactory parseString seedNodesString).resolve
    }*/

    val contactPoints =
      sys.props
        .get(CONTACT_POINTS_VAR)
        .map { points ⇒
          val array = points.split(",")
          if (array.size > 0) array
          else throw new Exception(s"$CONTACT_POINTS_VAR expected not to be nonEmtpy")
        }
        .getOrElse(throw new Exception(s"$CONTACT_POINTS_VAR not found"))

    val dbConf = {
      val dbUser = sys.props.get(CAS_USR_VAR).getOrElse(throw new Exception(s"$CAS_USR_VAR not found"))
      val dbPsw  = sys.props.get(CAS_PSW_VAR).getOrElse(throw new Exception(s"$CAS_PSW_VAR not found"))
      sys.props.get(CASSANDRA_VAR).map { hosts ⇒
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
    }.getOrElse(throw new Exception(s"$CASSANDRA_VAR | $CAS_USR_VAR | $CAS_PSW_VAR not found"))

    val hostName = sys.props
      .get("akka.remote.artery.canonical.hostname")
      .getOrElse(throw new Exception("akka.remote.artery.canonical.hostname is expected"))

    //https://doc.akka.io/docs/akka/current/remoting-artery.html#akka-behind-nat-or-in-a-docker-container
    //https://youtu.be/EPNBF5PXb84?list=PLbZ2T3O9BuvczX5j03bWMrMFzK5OAs9mZ&t=1075
    //Inside the Docker container we bind to all available network interfaces
    val dockerHostName = internalDockerAddr
      .map(_.getHostAddress)
      // TODO: for local debug !!!!!!!!!!!!!!!!!
      .getOrElse(hostName)
    //.getOrElse("0.0.0.0")

    val httpManagementPort = httpPort - 1

    applySystemProperties(
      Map(
        //-Dakka.remote.artery.canonical.hostname =   192.168.0.3
        //management
        "-Dakka.management.http.hostname" → hostName, //192.168.0.3
        "-Dakka.management.http.port"     → httpManagementPort.toString, //8079
        //internal hostname
        "-Dakka.remote.artery.bind.hostname"   → dockerHostName, //172.17.0.3
        "-Dakka.remote.artery.bind.port"       → akkaPort.toString,
        "-Dakka.management.http.bind-hostname" → dockerHostName, //172.17.0.3
        "-Dakka.management.http.bind-port"     → httpManagementPort.toString
      )
    )

    val appCfg =
      pureconfig.ConfigSource.file(configFile).at(AkkaSystemName).loadOrThrow[Boot.AppCfg]

    val cfg: Config = {
      //https://doc.akka.io/docs/akka-management/current/akka-management.html
      /*val managementConf =
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
          |""".stripMargin*/

      //seed nodes | contact points
      //https://doc.akka.io/docs/akka-management/current/bootstrap/local-config.html
      //https://github.com/akka/akka-management/blob/master/integration-test/local/src/test/scala/akka/management/LocalBootstrapTest.scala
      //Configuration based discovery can be used to set the Cluster Bootstrap process locally within an IDE or from the command line.
      val bootstrapEndpoints = {
        val endpointsList = contactPoints.map(s ⇒ s"{host=$s,port=$httpManagementPort}").mkString(",")
        ConfigFactory
          .parseString(s"akka.discovery.config.services { $AkkaSystemName = { endpoints = [ $endpointsList ] }}")
          .resolve()
      }

      bootstrapEndpoints
        .withFallback(dbConf)
        .withFallback(ConfigFactory.parseFile(configFile).resolve())
        .withFallback(ConfigFactory.load())
      //.withFallback(pureconfig.ConfigSource.default.loadOrThrow[Config]) //.at(AkkaSystemName)
    }

    //check dispatchers
    cfg.getObject(dbDispatcher)
    cfg.getObject(httpDispatcher)

    val eventsSchemaMapping =
      SchemaRegistry.journalEvents(cfg.getConfig("akka.actor.serialization-bindings"))

    val gr = greeting(
      cfg,
      httpPort,
      s"$hostName/$dockerHostName",
      contactPoints.mkString(","),
      eventsSchemaMapping
    )

    val system =
      akka.actor.typed.ActorSystem[Nothing](Guardian(dockerHostName, httpPort, gr, appCfg), AkkaSystemName, cfg)

    //akka.management.scaladsl.AkkaManagement(system).start(_.withAuth(basicAuth(system)))
    // Akka Management hosts the HTTP routes used by bootstrap
    akka.management.scaladsl.AkkaManagement(system).start()

    // Starting the bootstrap process needs to be done explicitly
    akka.management.cluster.bootstrap.ClusterBootstrap(system).start()
    akka.discovery.Discovery(system).loadServiceDiscovery("config")

    /*akka.cluster.Cluster(system).registerOnMemberUp {
      val ts = system.toTyped
      ts.systemActorOf(guardian(dockerHostName, httpPort), "guardian", akka.actor.typed.Props.empty)
      //ts.printTree
      //ts.tell(SpawnProtocol.Spawn(guardian(dockerHostName, httpPort), "guardian", akka.actor.typed.Props.empty, ts.ignoreRef))
    }*/

    //TODO: for debug only

    val _ = scala.io.StdIn.readLine()
    system.log.warn("Shutting down ...")
    system.terminate()
    scala.concurrent.Await.result(
      system.whenTerminated,
      cfg.getDuration("akka.coordinated-shutdown.default-phase-timeout", java.util.concurrent.TimeUnit.SECONDS).seconds
    )
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

  def greeting(
    cfg: Config,
    httpPort: Int,
    host: String,
    contactPoints: String,
    eventSchemaMapping: Map[String, String]
  ): String = {
    //cfg.getDuration("akka.http.server.idle-timeout")
    //cfg.getDuration("akka.http.host-connection-pool.idle-timeout")

    val info = new StringBuilder()
      .append('\n')
      .append("=================================================================================================")
      .append('\n')
      .append(
        s"★ ★ ★   akka-node $host   ★ ★ ★"
      )
      .append('\n')
      .append(s"★ ★ ★   Contact points: [$contactPoints]  ★ ★ ★")
      .append('\n')
      .append(
        s"★ ★ ★   Cassandra: ${cfg.getStringList("datastax-java-driver.basic.contact-points").asScala.mkString(",")} "
      )
      .append('\n')
      .append(s"★ ★ ★   Management host ${cfg.getString("akka.management.http.hostname")}  ★ ★ ★")
      .append('\n')
      .append(
        s"  Journal partition size: ${cfg.getInt("akka.persistence.cassandra.journal.target-partition-size")} ★ ★ ★"
      )
      .append('\n')
      .append("★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★  Persistent events schema mapping ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★")
      .append('\n')
      .append(eventSchemaMapping.mkString("\n"))
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
      .getTotalPhysicalMemorySize
    //.getTotalMemorySize()
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
