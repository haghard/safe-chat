// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat

import scala.reflect.ClassTag

package object rest {

  def timeoutMsg[T: ClassTag] =
    s"A request for a ${implicitly[ClassTag[T]].runtimeClass.getName} did not produce a timely response"

  def castErrorMsg[T: ClassTag](other: Any) =
    s"Expected response of type ${implicitly[ClassTag[T]].runtimeClass.getName} instead of ${other.getClass.getName}."
}
