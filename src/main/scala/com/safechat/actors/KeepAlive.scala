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

  def apply(chatShardRegion: ActorRef[UserCmd]): Behavior[Probe] =
    Behaviors.setup { ctx ⇒
      Behaviors.withTimers { timers ⇒
        timers.startTimerWithFixedDelay(ChatRoomEntity.wakeUpEntityName + "_timer", Probe.Ping, 15.seconds)
        Behaviors.receiveMessage {
          case Probe.Ping ⇒
            chatShardRegion.tell(PingShard(ChatRoomEntity.wakeUpEntityName, ctx.self))
            Behaviors.same
          case Probe.Stop ⇒
            Behaviors.stopped
        }
      }
    }
}
 */
