package com.safechat.actors.stages

import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.stream.stage.StageLogging

import scala.concurrent.Future
import scala.concurrent.Promise

final class LastConsumed[T] extends GraphStageWithMaterializedValue[FlowShape[T, T], Future[Option[T]]] {
  override val shape = FlowShape(Inlet[T]("in"), Outlet[T]("out"))

  override def createLogicAndMaterializedValue(
    inheritedAttributes: Attributes
  ): (GraphStageLogic, Future[Option[T]]) = {
    val p = Promise[Option[T]]
    val logic = new GraphStageLogic(shape) with StageLogging {

      import shape._

      private var current = Option.empty[T]

      setHandler(
        in,
        new InHandler {
          override def onPush(): Unit = {
            val element = grab(in)
            current = Some(element)
            push(out, element)
          }

          override def onUpstreamFinish(): Unit = {
            // log.info("upstream finish")
            p.success(current)
            super.onUpstreamFinish()
          }

          override def onUpstreamFailure(ex: Throwable): Unit = {
            // log.info("upstream failure")
            p.success(current)

            // don't fail here intentionally
            // super.onUpstreamFailure(LastSeenException(ex, current))
            super.onUpstreamFinish()
          }
        }
      )

      setHandler(
        out,
        new OutHandler {
          override def onPull(): Unit = pull(in)
        }
      )
    }
    (logic, p.future)
  }
}
