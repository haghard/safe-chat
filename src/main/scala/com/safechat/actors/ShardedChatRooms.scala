// Copyright (c) 2018-19 by Haghard. All rights reserved.

package com.safechat.actors

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardingMessageExtractor}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.sharding.typed.ClusterShardingSettings.StateStoreModeDData
import com.safechat.domain.CassandraHash

import scala.concurrent.Future
import scala.concurrent.duration._
import ShardedChatRooms._
import akka.actor.typed.scaladsl.AskPattern._

object ShardedChatRooms {

  object ChatRoomsMsgExtractor {
    def apply[T <: UserCmd](numberOfShards: Int): ShardingMessageExtractor[T, T] =
      new ShardingMessageExtractor[T, T] {

        def hash3_128(entityId: String): Long = {
          val bts = entityId.getBytes(StandardCharsets.UTF_8)
          CassandraHash.hash3_x64_128(ByteBuffer.wrap(bts), 0, bts.length, 512L)(1)
        }

        override def entityId(env: T): String =
          hash3_128(env.chatId).toHexString

        override def shardId(entityId: String): String =
          (math.abs(hash3_128(entityId)) % numberOfShards).toString

        override def unwrapMessage(env: T): T = env
      }
  }
}

class ShardedChatRooms(implicit system: ActorSystem[Nothing]) {
  implicit val sch        = system.scheduler
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

  val chatShardRegion = sharding.init(
    Entity(
      ChatRoomEntity.entityKey,
      entityCtx ⇒ ChatRoomEntity(entityCtx.entityId)
    ).withMessageExtractor(ChatRoomsMsgExtractor[UserCmd](512)) //ShardingMessageExtractor[UserCmd](512)
      .withSettings(settings)
  )

  //do not use the ChatRoomsMsgExtractor
  //use akka.cluster.sharding.typed.ShardingEnvelope(chatId, JoinUser(chatId, login, pubKey, replyTo))
  /*
  def enter(chatId: String, login: String, pubKey: String): Future[ChatRoomReply] =
    sharding
      .entityRefFor(ChatRoomEntity.entityKey, chatId)
      .ask[ChatRoomReply](JoinUser(chatId, login, pubKey, _))

  def disconnect(chatId: String, user: String): Future[ChatRoomReply] =
    sharding
      .entityRefFor(ChatRoomEntity.entityKey, chatId)
      .ask[ChatRoomReply](DisconnectUser(chatId, user, _))
   */

  def disconnect(chatId: String, user: String): Future[ChatRoomReply] =
    chatShardRegion.ask[ChatRoomReply](DisconnectUser(chatId, user, _))

  def enter(chatId: String, login: String, pubKey: String): Future[ChatRoomReply] =
    chatShardRegion.ask[ChatRoomReply](JoinUser(chatId, login, pubKey, _))

}
