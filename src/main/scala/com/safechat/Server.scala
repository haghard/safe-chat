package com.safechat

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.{Cluster, SelfUp, Unsubscribe}
import com.typesafe.config.{Config, ConfigFactory}
import akka.actor.typed.scaladsl.adapter._
import com.safechat.actors.ShardedChats
import com.safechat.rest.ChatRoomApi

object Server {

  val Dispatcher = "akka.actor.default-dispatcher"

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
    val host = "192.168.77.10"
    val port = 9000

    val cfg = ConfigFactory.load()

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
      .append(s"Up and running on $host:$port")
      .toString

    akka.actor.typed.ActorSystem[Nothing](guardian(cfg, host, port, info), "chatter", cfg)
  }
}
