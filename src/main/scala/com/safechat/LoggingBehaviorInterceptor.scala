// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat

import akka.actor.typed.{Behavior, BehaviorInterceptor, Signal, TypedActorContext}
import akka.actor.typed.scaladsl.Behaviors
import org.slf4j.Logger

import scala.reflect.ClassTag

object LoggingBehaviorInterceptor {
  def apply[T: ClassTag](logger: Logger)(behavior: Behavior[T]): Behavior[T] = {
    val interceptor = new LoggingBehaviorInterceptor[T](logger)
    Behaviors.intercept(() ⇒ interceptor)(behavior)
  }
}

final class LoggingBehaviorInterceptor[T: ClassTag] private (logger: Logger) extends BehaviorInterceptor[T, T] {

  import BehaviorInterceptor._

  override def aroundReceive(ctx: TypedActorContext[T], msg: T, target: ReceiveTarget[T]): Behavior[T] = {
    logger.info(s"Intercepted msg: $msg")
    val ret = target(ctx, msg)
    if (Behavior.isUnhandled(ret))
      logger.warn(s"Intercepted unhandled message: $msg")
    ret
  }

  override def aroundSignal(ctx: TypedActorContext[T], signal: Signal, target: SignalTarget[T]): Behavior[T] = {
    logger.info(s"Intercepted signal: $signal")
    target(ctx, signal)
  }

  override def toString: String = "LoggingBehaviorInterceptor"

  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean =
    other match {
      case _: LoggingBehaviorInterceptor[_] ⇒ true
      case _                                ⇒ super.isSame(other)
    }
}
