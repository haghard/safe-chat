package com.safechat.rest

import akka.event.LoggingAdapter
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.Directives.extractExecutionContext
import akka.http.scaladsl.server.Directives.extractRequestContext
import akka.http.scaladsl.server.Directives.mapInnerRoute
import akka.http.scaladsl.server.Directives.withRequestTimeoutResponse
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.server.RouteResult
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.RouteResult.Rejected
import akka.stream.scaladsl.Flow
import akka.util.ByteString

import scala.util.Failure
import scala.util.Success
import scala.util.Try

//https://blog.softwaremill.com/measuring-response-time-in-akka-http-7b6312ec70cf
object ExtraDirectives {

  val timeoutResponse =
    HttpResponse(
      StatusCodes.NetworkReadTimeout,
      entity = "The server was not able " +
        "to produce a timely response to your request.\r\nPlease try again in a short while!"
    )

  def aroundRequest(onRequest: RequestContext => Try[RouteResult] => Unit): Directive0 =
    (extractRequestContext & extractExecutionContext).tflatMap { tuple =>
      val onDone = onRequest(tuple._1)
      mapInnerRoute { inner =>
        withRequestTimeoutResponse { _ =>
          onDone(Success(Complete(timeoutResponse)))
          timeoutResponse
        } {
          inner.andThen { resultFuture =>
            resultFuture
              .map {
                case c @ Complete(response) =>
                  Complete(
                    response.mapEntity { entity =>
                      if (entity.isKnownEmpty) {
                        onDone(Success(c))
                        entity
                      } else
                        // On an empty entity, `transformDataBytes` unsets `isKnownEmpty`.
                        // Call onDone right away, since there's no significant amount of
                        // data to send, anyway.
                        entity.transformDataBytes(Flow[ByteString].watchTermination() { case (m, f) =>
                          f.map(_ => c)(tuple._2).onComplete(onDone)(tuple._2)
                          m
                        })
                    }
                  )
                case other =>
                  onDone(Success(other))
                  other
              }(tuple._2)
              .andThen { // skip this if you use akka.http.scaladsl.server.handleExceptions, put onDone there
                case Failure(ex) =>
                  onDone(Failure(ex))
              }(tuple._2)
          }
        }
      }
    }

  /** There are three possible outcomes of a request: a) the request completes successfuly, Success(Complete(response))
    * is passed to onDone b) the request is rejected (e.g. because of a non-matching inner route), then
    * Success(Rejected(rejections)) is passed c) producing the response body fails, and hence the request fails as well:
    * Failure is passed to onDone
    */
  def logLatency(log: LoggingAdapter)(ctx: RequestContext): Try[RouteResult] => Unit = {
    val start = System.currentTimeMillis

    {
      case Success(Complete(resp)) =>
        val millis = System.currentTimeMillis - start
        // val params = ctx.request.uri.rawQueryString.fold("")(identity)
        val url = ctx.request.uri.path.toString
        log.info(
          s"""[${resp.status.intValue}] ${ctx.request.method.name} $url?params took:$millis ms""" // params
        )
      case Success(Rejected(_)) =>
      /*
              val msLatency = System.currentTimeMillis - start
              val url       = ctx.request.uri.path.toString
              val params    = ctx.request.uri.rawQueryString.fold("")(identity)
              log.info(s"""Rejected:${ctx.request.method.name} ${url}?${params} took:${msLatency} ms""")*/
      case Failure(ex) =>
        val millis = System.currentTimeMillis - start
        val url    = ctx.request.uri.path.toString
        val params = ctx.request.uri.rawQueryString.fold("")(identity)
        log.error(ex, s"""Failure:${ctx.request.method.name} $url?$params took:$millis ms""")
    }
  }
}
