package com.safechat

import akka.actor.ActorSystem
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.cluster.sharding.ClusterSharding
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.{Attributes, UniqueKillSwitch}
import akka.stream.scaladsl.{Flow, RestartFlow}
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
    system: ActorSystem,
    persistTimeout: Timeout
  ): Flow[Message, Reply, akka.NotUsed] = {
    def persistFlow = {

      //to deal with Idle timeout
      val entity =
        ClusterSharding(system).shardRegion(ChatRoom.entityKey.name).toTyped[PostText]

      ActorFlow
        .ask[Message, PostText, Reply](1)(entity) { (msg: Message, reply: ActorRef[Reply]) ⇒
          msg match {
            case TextMessage.Strict(text) ⇒
              val segs = text.split(MSG_SEP)
              if (text.split(MSG_SEP).size == 3)
                PostText(chatId, segs(0).trim, segs(1).trim, segs(2).trim, reply)
              else
                PostText(
                  chatId,
                  "null",
                  "null",
                  s"Message error. Wrong format $text",
                  reply
                )

            case other ⇒
              throw new Exception(s"Unexpected message type ${other.getClass.getName}")
          }
        }
        .withAttributes(Attributes.inputBuffer(0, 0)) //ActorAttributes.maxFixedBufferSize(1))
    }
    //ActorAttributes.supervisionStrategy({ case _ => Supervision.Resume }).and(Attributes.inputBuffer(1, 1))

    persistFlow

    //TODO: maybe would be better to fail instead of retrying
    RestartFlow.withBackoff(
      akka.stream.RestartSettings(1.second, 3.seconds, 0.3)
    )(() ⇒ persistFlow)
  }

  //if akka.sharding.use-lease
  @tailrec final def registerChatRoom(
    liveShards: AtomicReference[immutable.Set[String]],
    persistenceId: String
  ): Unit = {
    val cur = liveShards.get()
    if (liveShards.compareAndSet(cur, cur + persistenceId)) () else registerChatRoom(liveShards, persistenceId)
  }

  @tailrec final def registerKS(
    kss: AtomicReference[immutable.Set[UniqueKillSwitch]],
    ks: UniqueKillSwitch
  ): Unit = {
    val set = kss.get()
    if (kss.compareAndSet(set, set + ks)) () else registerKS(kss, ks)
  }
}
