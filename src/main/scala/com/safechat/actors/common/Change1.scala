package com.safechat.actors.common

sealed trait Change1[+Err, State] {

  def ++[Err1 >: Err](that: Change1[Err1, State]): Change1[Err1, State] = ???

}

object Change1 {

  type Effect[+A]

  def empty[State]: Change1[Nothing, State] = ???

}
