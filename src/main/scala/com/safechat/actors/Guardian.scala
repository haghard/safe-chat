package com.safechat.actors

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.cluster.typed.SelfUp
import akka.cluster.typed.Unsubscribe
import akka.stream.UniqueKillSwitch
import com.safechat.Boot.AppCfg
import com.safechat.Bootstrap
import com.safechat.rest.ChatRoomApi

import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable
import scala.concurrent.duration.Duration

object Guardian {

  def apply(httpBindHostName: String, httpPort: Int, greeting: String, appCfg: AppCfg): Behavior[Nothing] =
    Behaviors
      .setup[SelfUp] { ctx ⇒
        implicit val sys = ctx.system
        val cluster      = akka.cluster.typed.Cluster(ctx.system)
        cluster.subscriptions ! akka.cluster.typed.Subscribe(ctx.self, classOf[SelfUp])

        Behaviors.receive { (ctx, _) ⇒
          //ctx.log.info(info)
          cluster.subscriptions ! Unsubscribe(ctx.self)

          ctx.log.info(greeting)

          ctx.log.info(ctx.system.printTree)

          val localShards =
            new AtomicReference[scala.collection.immutable.Set[String]](scala.collection.immutable.Set[String]())

          //stable-after * 2 = 10
          //https://doc.akka.io/docs/akka-enhancements/current/split-brain-resolver.html#expected-failover-time
          val totalFailoverTimeout = Duration.fromNanos(
            sys.settings.config
              .getDuration("akka.cluster.split-brain-resolver.stable-after")
              .multipliedBy(2)
              .toNanos
          )

          val kksRef =
            new AtomicReference[immutable.Map[String, UniqueKillSwitch]](immutable.Map[String, UniqueKillSwitch]())

          val api = ChatRoomApi(
            new ShardedChatRooms(localShards, kksRef, totalFailoverTimeout, appCfg)(sys),
            totalFailoverTimeout
          ).routes

          Bootstrap(
            api,
            httpBindHostName,
            httpPort,
            localShards,
            kksRef
          )(sys.toClassic)

          Behaviors.empty
        }
      }
      .narrow
}
