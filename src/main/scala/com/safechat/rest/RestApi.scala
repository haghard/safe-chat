// Copyright (c) 2018-19 by Haghard. All rights reserved.

package com.safechat.rest

import akka.event.LoggingAdapter
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.HttpEntity.Strict
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route}
import akka.util.ByteString
import StatusCodes._

import scala.concurrent.{Future, TimeoutException}
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

import spray.json._

trait RestApi extends Directives {

  def completeFromFuture[T: ClassTag](
    responseFuture: Future[T]
  )(f: T ⇒ Route)(implicit log: LoggingAdapter): Route =
    onComplete(responseFuture) {
      case Success(t: T) ⇒
        f(t)
      case Success(other) ⇒
        val errorMsg = castErrorMsg[T](other)
        log.error(errorMsg)
        complete(
          HttpResponse(
            InternalServerError,
            entity = Strict(`application/json`, ByteString(ServerError0(errorMsg).toJson.compactPrint))
          )
        )
      case Failure(e: TimeoutException) ⇒
        val errorMsg = timeoutMsg[T]
        log.error(e, errorMsg)
        complete(
          HttpResponse(
            ServiceUnavailable,
            entity = Strict(`application/json`, ByteString(ServerError0(errorMsg).toJson.compactPrint))
          )
        )
      case Failure(e) ⇒
        log.error(e, s"A request cannot not be completed")
        complete(
          HttpResponse(
            StatusCodes.InternalServerError,
            entity = Strict(`application/json`, ByteString(ServerError0(e.getMessage).toJson.compactPrint))
          )
        )
    }
}
