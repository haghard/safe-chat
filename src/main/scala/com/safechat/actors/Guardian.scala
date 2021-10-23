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

import java.util.concurrent.TimeUnit
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

          val chatRoomNames =
            new AtomicReference[scala.collection.immutable.Set[String]](scala.collection.immutable.Set[String]())

          //https://doc.akka.io/docs/akka-enhancements/current/split-brain-resolver.html#expected-failover-time
          val stableAfter = sys.settings.config.getDuration("akka.cluster.split-brain-resolver.stable-after").toSeconds

          //1) failure detection 5 seconds
          //2) stable-after 7 seconds
          //3) akka.cluster.down-removal-margin (by default the same as split-brain-resolver.stable-after) 7 seconds
          val totalFailoverSec     = (5 + stableAfter) + ((stableAfter * 3) / 4) //
          val totalFailoverTimeout = Duration(totalFailoverSec, TimeUnit.SECONDS)

          val kksRef =
            new AtomicReference[immutable.Set[UniqueKillSwitch]](immutable.Set[UniqueKillSwitch]())

          val api = ChatRoomApi(
            new ShardedChatRooms(chatRoomNames, kksRef, totalFailoverTimeout, appCfg)(sys),
            totalFailoverTimeout
          ).routes

          Bootstrap(
            api,
            httpBindHostName,
            httpPort,
            chatRoomNames,
            kksRef
          )(sys.toClassic)

          Behaviors.empty
        }
      }
      .narrow
}
