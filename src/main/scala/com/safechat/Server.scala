package com.safechat

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.{Cluster, SelfUp, Unsubscribe}
import com.typesafe.config.{Config, ConfigFactory}
import akka.actor.typed.scaladsl.adapter._
import com.safechat.actors.ShardedChats
import com.safechat.rest.ChatRoomApi
import scala.collection.Map

import scala.util.Try

object Server extends Ops {

  val Dispatcher     = "akka.actor.default-dispatcher"
  val AkkaSystemName = "echatter"

  def guardian(config: Config, hostName: String, httpPort: Int, info: String): Behavior[Nothing] =
    Behaviors
      .setup[SelfUp] { ctx ⇒
        implicit val sys = ctx.system
        val cluster      = Cluster(ctx.system)
        cluster.subscriptions ! akka.cluster.typed.Subscribe(ctx.self, classOf[SelfUp])

        Behaviors.receive { (ctx, _) ⇒
          ctx.log.info(info)
          cluster.subscriptions ! Unsubscribe(ctx.self)

          new Bootstrap(new ChatRoomApi(new ShardedChats).route, hostName, httpPort)(sys.toClassic)
          Behaviors.empty
        }
      }
      .narrow

  def main(args: Array[String]): Unit = {
    val opts: Map[String, String] = argsToOpts(args.toList)
    applySystemProperties(opts)

    val akkaExternalHostName = Option(System.getProperty("akka.remote.artery.canonical.hostname"))
      .getOrElse(throw new Exception("akka.remote.artery.canonical.hostname is expected"))

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
      (ConfigFactory parseString seedNodesString).resolve()
    }

    val dbConf = Option(System.getProperty("cassandra.hosts")).map { hs ⇒
      val contactPoints = hs.split(",").map(h ⇒ s""" "$h" """).mkString(",").dropRight(1)
      ConfigFactory.parseString(
        s"""
             |cassandra-journal.contact-points = [ $contactPoints ]
             |cassandra-snapshot-store.contact-points = [ $contactPoints ]
            """.stripMargin
      )
    }

    val cfg: Config = {
      val general = ConfigFactory.empty()
      val local   = dbConf.fold(general)(c ⇒ general.withFallback(c))
      akkaSeeds
        .fold(local)(c ⇒ local.withFallback(c))
        .withFallback(ConfigFactory.load())
    }

    //check dispatcher name
    cfg.getObject(Dispatcher)

    val info = new StringBuilder()
      .append('\n')
      .append("==============================================================================")
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
      .append("==============================================================================")
      .append('\n')
      .append(s"Up and running on $akkaExternalHostName:$akkaPort")
      .toString

    akka.actor.typed.ActorSystem[Nothing](guardian(cfg, akkaExternalHostName, httpPort, info), AkkaSystemName, cfg)
  }
}
