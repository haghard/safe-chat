// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.
/*

package com.safechat.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._

object KeepAlive {

  sealed trait Probe
  object Probe {
    case object Ping extends Probe
    case object Stop extends Probe
  }

  def apply(chatShardRegion: ActorRef[Command[Reply]]): Behavior[Probe] =
    Behaviors.setup { ctx ⇒
      Behaviors.withTimers { timers ⇒
        timers.startTimerWithFixedDelay(ChatRoom.wakeUpEntityName + "_timer", Probe.Ping, 15.seconds)

        Behaviors.receiveMessage {
          case Probe.Ping ⇒
            chatShardRegion.tell(PingShard(ChatRoom.wakeUpEntityName, ctx.self))
            Behaviors.same
          case Probe.Stop ⇒
            Behaviors.stopped
        }
      }
    }
}
 */
