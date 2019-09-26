// Copyright (c) 2018-19 by Haghard. All rights reserved.

package com.safechat

import spray.json.DefaultJsonProtocol._

import scala.reflect.ClassTag

package object rest {

  implicit val errorFormat = jsonFormat1(ServerError0)

  case class ServerError0(error: String)

  def timeoutMsg[T: ClassTag] =
    s"A request for a ${implicitly[ClassTag[T]].runtimeClass.getName} did not produce a timely response"

  def castErrorMsg[T: ClassTag](other: Any) =
    s"Expected response of type ${implicitly[ClassTag[T]].runtimeClass.getName} instead of ${other.getClass.getName}."
}
