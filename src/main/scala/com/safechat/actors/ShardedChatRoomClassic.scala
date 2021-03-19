package com.safechat.actors

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardCoordinator
import akka.stream.UniqueKillSwitch
import com.safechat.Server.AppCfg

import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration

object ShardedChatRoomClassic {

  def apply(
    system: akka.actor.ActorSystem,
    totalFailoverTimeout: FiniteDuration,
    kksRef: AtomicReference[immutable.Set[UniqueKillSwitch]],
    appCfg: AppCfg
  ): ActorRef[Command[Reply]] = {

    val chatRoomRegion = ClusterSharding(system).start(
      typeName = ChatRoom.entityKey.name, //shared
      entityProps = ChatRoomClassic.props(totalFailoverTimeout, kksRef, appCfg),
      settings = ClusterShardingSettings(system).withPassivateIdleAfter(appCfg.passivationAfter), //20.seconds 5.minutes
      extractShardId = ChatRoomClassic.shardExtractor,
      extractEntityId = ChatRoomClassic.idExtractor,
      allocationStrategy = ShardCoordinator.ShardAllocationStrategy.leastShardAllocationStrategy(20, 0.5),
      //allocationStrategy = new external.ExternalShardAllocationStrategy(system, users.serviceName),
      handOffStopMessage = Command.handOffChatRoom //akka.actor.PoisonPill
    )

    chatRoomRegion.toTyped[Command[Reply]]
  }
}
