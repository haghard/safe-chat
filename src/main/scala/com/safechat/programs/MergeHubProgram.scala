package com.safechat.programs

import akka.stream.KillSwitches
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.MergeHub
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.TestSubscriber
import com.typesafe.config.ConfigFactory

import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

/** https://github.com/akka/akka/issues/30057
  * https://github.com/akka/akka/blob/318b9614a31d7e1850702fd0231f53c804402bff/akka-stream-tests/src/test/scala/akka/stream/scaladsl/HubSpec.scala#L191
  *
  *  runMain com.safechat.programs.MergeHubProgram
  */
object MergeHubProgram {

  val qSize               = 1 << 4
  val numOfElements: Long = qSize + 10L

  implicit val sys = akka.actor.ActorSystem("test", ConfigFactory.empty())
  implicit val ec  = sys.dispatcher

  val mid: Long = numOfElements / 2

  def main(args: Array[String]): Unit = {

    println("★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★")
    println(s"Expected num of elements: $numOfElements  [ src1: (1...$mid), src2:(${mid + 1} to $numOfElements) ]")
    println("★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★")

    val downstream = TestSubscriber.probe[Long]()

    //val (((sink, dc), ks), p) =
    val ((sink, dc), _) =
      MergeHub
        .sourceWithDraining[Long](perProducerBufferSize = 2)
        .via(
          Flow[Long]
            .buffer(qSize, OverflowStrategy.backpressure)
            .mapAsync(4) { i ⇒
              Future {
                val d = ThreadLocalRandom.current().nextLong(10L, 50L)
                Thread.sleep(d)
                i
              }
            }
        )
        //.take(numOfElements)
        .viaMat(KillSwitches.single)(Keep.both)
        //.toMat(BroadcastHub.sink[Message](bufferSize = recentHistorySize))(Keep.both)
        .toMat(Sink.fromSubscriber(downstream))(Keep.left)
        //.toMat(Sink.asPublisher(false))(Keep.both)
        .run()

    //Source.fromPublisher(p)

    /*
    val (sink, ks) =
      MergeHub
        .source[Int](perProducerBufferSize = 4)
        //.take(numOfElements)
        .viaMat(KillSwitches.single)(Keep.both)
        .toMat(Sink.fromSubscriber(downstream))(Keep.left)
        .run()
     */

    //.toMat(Sink.fromSubscriber(downstream))(Keep.both).run()

    Source(1L to mid).throttle(1, 5.millis).runWith(sink)
    Source(mid + 1 to numOfElements).throttle(1, 5.millis).runWith(sink)

    //Will cancel any new producer and will complete as soon as all the currently connected producers complete.
    dc.drainAndComplete()

    //cancel new producers while draining
    val upstream3 = TestPublisher.probe[Long]()
    Source.fromPublisher(upstream3).runWith(sink)
    upstream3.expectCancellation()

    //But works
    //Source.repeat(100).throttle(1, 5.millis).runWith(sink)

    downstream.request(mid)
    println(downstream.expectNextN(mid).mkString(","))

    downstream.request(mid)
    println(downstream.expectNextN(mid).mkString(","))

    //Stop the whole stream
    downstream.cancel()

    downstream.expectComplete()
    Await.result(sys.terminate(), Duration.Inf)
  }
}
