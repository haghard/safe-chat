package com.safechat.actors

import akka.actor.Actor
import akka.cluster.sharding.ShardRegion
import akka.persistence.PersistentActor
import com.safechat.actors.Command.HandOffChatRoom

trait Passivation extends Actor { _: PersistentActor =>

  protected def withPassivation(receive: Receive): Receive =
    receive.orElse(
      {
        case akka.actor.ReceiveTimeout =>
          context.parent ! ShardRegion.Passivate(stopMessage = HandOffChatRoom())

        case _: HandOffChatRoom =>
          context.stop(self)
      }: PartialFunction[Any, Unit]
    )
}
