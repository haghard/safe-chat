package com.safechat.actors

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardCoordinator}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object ShardedChatRoomClassic {

  def apply(system: akka.actor.ActorSystem, to: FiniteDuration): ActorRef[Command[Reply]] = {

    val chatRoomRegion = ClusterSharding(system).start(
      typeName = ChatRoom.entityKey.name, //shared
      entityProps = ChatRoomClassic.props(to),
      settings = ClusterShardingSettings(system).withPassivateIdleAfter(5.minutes), //20.seconds
      extractShardId = ChatRoomClassic.shardExtractor,
      extractEntityId = ChatRoomClassic.idExtractor,
      allocationStrategy = ShardCoordinator.ShardAllocationStrategy.leastShardAllocationStrategy(20, 0.5),
      //allocationStrategy = new external.ExternalShardAllocationStrategy(system, users.serviceName),
      handOffStopMessage = Command.handOffRoom //akka.actor.PoisonPill
    )

    chatRoomRegion.toTyped[Command[Reply]]
  }
}
