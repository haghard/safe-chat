package com.safechat.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._

object KeepAlive {

  case object Probe

  def apply(chatShardRegion: ActorRef[UserCmd]): Behavior[Probe.type] =
    Behaviors.setup { ctx ⇒
      Behaviors.withTimers { timers ⇒
        timers.startTimerWithFixedDelay(ChatRoomEntity.wakeUpEntityName + "_timer", Probe, 5.seconds)
        Behaviors.receiveMessage {
          case Probe ⇒
            chatShardRegion.tell(PingShard(ChatRoomEntity.wakeUpEntityName, ctx.self))
            Behaviors.same
        }
      }
    }
}
