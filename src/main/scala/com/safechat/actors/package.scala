package com.safechat

import akka.actor.ActorSystem
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.cluster.sharding.ClusterSharding
import akka.stream.Attributes
import akka.stream.UniqueKillSwitch
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.RestartFlow
import akka.stream.typed.scaladsl.ActorFlow
import akka.util.Timeout
import com.safechat.actors.ChatRoom.MSG_SEP
import com.safechat.actors.Command.PostText

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration.DurationInt

package object actors {

  def persist(chatId: String)(implicit
    classicSystem: ActorSystem,
    persistTimeout: Timeout
  ): Flow[String, Reply, akka.NotUsed] = {
    def persistFlow = {

      @tailrec
      def lookup(f: ⇒ ActorRef[PostText], n: Int): ActorRef[PostText] =
        scala.util.Try(f) match {
          case scala.util.Success(r) ⇒ r
          case scala.util.Failure(ex) ⇒
            if (n > 0) {
              Thread.sleep(1000) //
              lookup(f, n - 1)
            } else throw ex
        }

      val shardRegion =
        lookup(ClusterSharding(classicSystem).shardRegion(ChatRoom.entityKey.name).toTyped[PostText], 20)

      ActorFlow
        .ask[String, PostText, Reply](1)(shardRegion) { (msg: String, reply: ActorRef[Reply]) ⇒
          val segs = msg.split(MSG_SEP)
          if (segs.size == 3) PostText(chatId, segs(0).trim, segs(1).trim, segs(2).trim, reply)
          else PostText(chatId, "", "", s"Message error.Wrong format: $msg", reply)
        }
        .withAttributes(Attributes.inputBuffer(0, 0)) //ActorAttributes.maxFixedBufferSize(1))
    }
    //ActorAttributes.supervisionStrategy({ case _ => Supervision.Resume }).and(Attributes.inputBuffer(1, 1))

    //TODO: maybe would be better to fail instead of retrying
    RestartFlow.withBackoff(
      akka.stream.RestartSettings(1.second, 3.seconds, 0.3)
    )(() ⇒ persistFlow)
  }

  @tailrec final def registerChatRoom(
    liveShards: AtomicReference[immutable.Set[String]],
    persistenceId: String
  ): Unit = {
    val cur = liveShards.get()
    if (liveShards.compareAndSet(cur, cur + persistenceId)) () else registerChatRoom(liveShards, persistenceId)
  }

  @tailrec final def registerKS(
    kssRef: AtomicReference[immutable.Set[UniqueKillSwitch]],
    ks: UniqueKillSwitch
  ): Unit = {
    val kss = kssRef.get()
    if (kssRef.compareAndSet(kss, kss + ks)) () else registerKS(kssRef, ks)
  }

  @tailrec final def unregisterKS(
    kssRef: AtomicReference[immutable.Set[UniqueKillSwitch]],
    ks: UniqueKillSwitch
  ): Unit = {
    val kss = kssRef.get()
    if (kss.contains(ks))
      if (kssRef.compareAndSet(kss, kss - ks)) () else unregisterKS(kssRef, ks)
    else ()
  }
}
