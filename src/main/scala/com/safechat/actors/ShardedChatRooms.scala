// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat.actors

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.sharding.typed.ClusterShardingSettings.StateStoreModeDData
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardingMessageExtractor}

import scala.concurrent.Future
import scala.concurrent.duration._
import ShardedChatRooms._
import akka.actor.typed.scaladsl.AskPattern._

object ShardedChatRooms {

  object ChatRoomsMsgExtractor {
    def apply[T <: Command[Reply]](numberOfShards: Int): ShardingMessageExtractor[T, T] =
      new ShardingMessageExtractor[T, T] {

        /*
        private def hash3_128(entityId: String): Long = {
          val bts = entityId.getBytes(StandardCharsets.UTF_8)
          com.safechat.domain.CassandraHash.hash3_x64_128(ByteBuffer.wrap(bts), 0, bts.length, 512L)(1)
        }*/

        override def entityId(cmd: T): String =
          cmd.chatId
        //hash3_128(cmd.chatId).toHexString

        override def shardId(entityId: String): String =
          //taking the abs value before doing the Modulo can produce a bug if the hashCode happens to be Int.MinValue
          math.abs(entityId.hashCode % numberOfShards).toString
        //math.abs(hash3_128(entityId) % numberOfShards).toString

        override def unwrapMessage(cmd: T): T = cmd
      }
  }
}

class ShardedChatRooms(implicit system: ActorSystem[Nothing]) {

  val numberOfShards     = 1 << 8      //TODO: make it configurable
  val passivationTimeout = 300.seconds //TODO: make it configurable
  val sharding           = ClusterSharding(system)

  implicit val shardAskTimeout = akka.util.Timeout(ChatRoomEntity.hubInitTimeout)

  val settings =
    ClusterShardingSettings(system)
      /*
        rememberEntities == false ensures that a shard entity won't be recreates/restarted automatically on
        a different `ShardRegion` due to rebalance, crash or leave (graceful exit). That is exactly what we want,
        because we want lazy start for each ChatRoomEntity.
       */
      .withRememberEntities(false)
      .withStateStoreMode(StateStoreModeDData)
      .withPassivateIdleEntityAfter(passivationTimeout)

  /** Aa a example of non persistent but sharded `Entity`.
    * Note that since this station is not storing its state anywhere else than in JVM memory, if Akka Cluster Sharding
    * rebalances it - moves it to another node because of cluster nodes added removed etc - it will lose all its state.
    * For a sharded entity to have state that survives being stopped and started again it needs to be persistent,
    * for example by being an EventSourcedBehavior.
    */
  /*ClusterSharding(system).init(
    Entity(ChatRoomEntity.entityKey)(entityCtx ⇒
      Behaviors.setup { ctx ⇒
        ctx.log.info(s"Start sharded entity: ${entityCtx.entityId}")
        Behaviors
          .receiveMessage[UserCmdWithReply] {
            case _: JoinUser       ⇒ Behaviors.same
            case _: PostText       ⇒ Behaviors.same
            case _: DisconnectUser ⇒ Behaviors.same
          }
          .receiveSignal {
            case (_, PostStop) ⇒
              ctx.log.info("Stopping, losing all recorded state for chat room {}", entityCtx.entityId)
              Behaviors.same
          }
      }
    ).withMessageExtractor(ChatRoomsMsgExtractor[UserCmdWithReply](numberOfShards))
      .withEntityProps(akka.actor.typed.Props.empty.withDispatcherFromConfig("shard-dispatcher"))
      .withAllocationStrategy(new ExternalShardAllocationStrategy(system, ChatRoomEntity.entityKey.name))
  )*/

  /*
  Another way to initialize and get ref to shardRegion

  val tags = Vector.tabulate(5)(i => s"room-$i")
  val behaviorFactory: EntityContext[Command] => Behavior[Command] = {
    entityContext =>
      val i = math.abs(entityContext.entityId.hashCode % tags.size)
      val selectedTag = tags(i)
      ChatRoomEntity(entityContext/*, selectedTag*/)
  }
  val chatShardRegion = ClusterSharding(system).init(Entity(ChatRoomEntity.entityKey)(behaviorFactory))
   */

  val entity = Entity(ChatRoomEntity.entityKey)(ChatRoomEntity(_))
    //ShardingMessageExtractor[UserCmd](512)
    .withMessageExtractor(ChatRoomsMsgExtractor[Command[Reply]](numberOfShards))
    .withSettings(settings)
    //https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html

    //For any shardId that has not been allocated it will be allocated to the requesting node (like a sticky session)
    .withAllocationStrategy(
      new akka.cluster.sharding.external.ExternalShardAllocationStrategy(system, ChatRoomEntity.entityKey.name)
    )

  //default AllocationStrategy
  //.withAllocationStrategy(new akka.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy(1, 3))
  //https://doc.akka.io/docs/akka/2.6/typed/cluster-sharding.html?_ga=#shard-allocation
  /*.withAllocationStrategy(
      akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
        .leastShardAllocationStrategy(numberOfShards / 2, 0.5)
    )*/
  //.withEntityProps(akka.actor.typed.Props.empty.withDispatcherFromConfig(disName))

  val chatShardRegion = sharding.init(entity)

  //Example how to use explicit client:  akka.kafka.cluster.sharding.KafkaClusterSharding
  //val client             = akka.cluster.sharding.external.ExternalShardAllocation(system).clientFor(ChatRoomEntity.entityKey.name)
  //val done: Future[akka.Done] = client.updateShardLocation("chat0", akka.actor.Address("akka", Server.AkkaSystemName, "127.0.0.1", 2552))

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

  def leave(chatId: String, user: String): Future[LeaveReply] =
    chatShardRegion.ask[LeaveReply](Command.Leave(chatId, user, _))

  def join(chatId: String, login: String, pubKey: String): Future[JoinReply] =
    chatShardRegion.ask[JoinReply](Command.JoinUser(chatId, login, pubKey, _))
}
