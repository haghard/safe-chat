// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat.rest

import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.FlowShape
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.MergePreferred
import akka.stream.scaladsl.Source

import scala.concurrent.duration._

object WsScaffolding {

  val hbMessage = "hb"

  def flowWithHeartbeat(d: FiniteDuration): Flow[Message, Message, akka.NotUsed] = {
    val hbMsg = TextMessage(hbMessage)
    Flow.fromGraph(
      GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._
        val heartbeats = b.add(Source.tick(d, d, hbMsg))
        // 0 - preferred port reserved for messages from pub-sub
        // 1 - secondary port reserved for heartbeat
        val merge = b.add(MergePreferred[Message](1, eagerComplete = true))
        heartbeats ~> merge.in(0)
        FlowShape(merge.preferred, merge.out)
      }
    )
  }
}
