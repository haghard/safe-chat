// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat.actors

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

import akka.actor.typed.ActorSystem
import com.safechat.domain.CassandraHash
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.sharding.typed.ClusterShardingSettings.StateStoreModeDData
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardingMessageExtractor}

import scala.concurrent.Future
import scala.concurrent.duration._
import ShardedChatRooms._
import akka.Done
import akka.actor.Address
import akka.actor.typed.scaladsl.AskPattern._
import akka.cluster.sharding.external.scaladsl.ExternalShardAllocationClient
import akka.cluster.sharding.external.{ExternalShardAllocation, ExternalShardAllocationStrategy}

object ShardedChatRooms {

  object ChatRoomsMsgExtractor {
    def apply[T <: UserCmd](numberOfShards: Int): ShardingMessageExtractor[T, T] =
      new ShardingMessageExtractor[T, T] {
        val SEED = 512L
        private def hash3_128(entityId: String): Long = {
          val bts = entityId.getBytes(UTF_8)
          CassandraHash.hash3_x64_128(ByteBuffer.wrap(bts), 0, bts.length, SEED)(1)
        }

        override def entityId(cmd: T): String =
          hash3_128(cmd.chatId).toHexString

        override def shardId(entityId: String): String =
          (math.abs(hash3_128(entityId)) % numberOfShards).toString

        override def unwrapMessage(cmd: T): T = cmd
      }
  }
}

class ShardedChatRooms(implicit system: ActorSystem[Nothing]) {
  implicit val shardingTO = akka.util.Timeout(ChatRoomEntity.hubInitTimeout)
  val numberOfShards      = 1 << 8 //TODO: make it configurable
  val passivationTO       = 10.minutes //TODO: make it configurable
  val sharding            = ClusterSharding(system)
  val settings =
    ClusterShardingSettings(system)
    /*
        rememberEntities == false ensures that a shard entity won't be recreates/restarted automatically on
        a different `ShardRegion` due to rebalance, crash or graceful exit. That is exactly what we want, because we want lazy
        start for each ChatRoomEntity.
       */
      .withRememberEntities(false)
      .withStateStoreMode(StateStoreModeDData)
      .withPassivateIdleEntityAfter(passivationTO)

  val chatShardRegion = sharding.init(
    Entity(ChatRoomEntity.entityKey)(entityCtx ⇒ ChatRoomEntity(entityCtx.entityId))
    //ShardingMessageExtractor[UserCmd](512)
      .withMessageExtractor(ChatRoomsMsgExtractor[UserCmd](numberOfShards))
      .withSettings(settings)
      .withEntityProps(akka.actor.typed.Props.empty.withDispatcherFromConfig("shard-dispatcher"))
  )

  //To make explicit allocations
  //val client = ExternalShardAllocation(system).clientFor(ChatRoomEntity.entityKey.name)
  //val done: Future[Done] = client.updateShardLocation("chat0", Address("akka", "system", "127.0.0.1", 2552))

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
