package com.safechat.actors

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.sharding.typed.ClusterShardingSettings.StateStoreModeDData
import com.safechat.domain.CassandraHash

import scala.concurrent.Future
import scala.concurrent.duration._

class ShardedChats(implicit system: ActorSystem[Nothing]) {

  implicit val shardingTO = akka.util.Timeout(ChatRoomEntity.hubInitTimeout)

  val passivationTO = 1.minutes
  val sharding      = ClusterSharding(system)
  val settings =
    ClusterShardingSettings(system)
    /*
        rememberEntities == false ensures that a shard entity won't be recreates/restarted automatically on
        a different `ShardRegion` due to rebalance, crash or graceful exit. That is exactly what we want, cause we want lazy
        ChatRoomHub initialization.
       */
      .withRememberEntities(false)
      .withStateStoreMode(StateStoreModeDData)
      .withPassivateIdleEntitiesAfter(passivationTO)

  def hash3_128(entityId: String): String = {
    val bts = entityId.getBytes(StandardCharsets.UTF_8)
    CassandraHash.hash3_x64_128(ByteBuffer.wrap(bts), 0, bts.length, 512L)(1).toHexString
  }

  val chatShardRegion = sharding.init(
    Entity(
      ChatRoomEntity.entityKey,
      entityCtx ⇒ ChatRoomEntity(entityCtx.entityId)
    ).withSettings(settings)
  )

  def ?(cmd: JoinChatRoom): Future[ChatRoomReply] =
    sharding
      .entityRefFor(ChatRoomEntity.entityKey, hash3_128(cmd.chatId))
      .ask[ChatRoomReply](JoinUser(cmd.chatId, cmd.user, _))

  def disconnect(chatId: String, user: String): Future[ChatRoomReply] =
    sharding
      .entityRefFor(ChatRoomEntity.entityKey, hash3_128(chatId))
      .ask[ChatRoomReply](DeactivateUser(chatId, user, _))

  /*
  import akka.actor.typed.scaladsl.AskPattern._
  chatShardRegion.ask[UserReply] { replyTo: ActorRef[UserReply] ⇒
    akka.cluster.sharding.typed.ShardingEnvelope(cmd.chatId, JoinUser(cmd.chatId, cmd.user, replyTo))
  }
 */
}
