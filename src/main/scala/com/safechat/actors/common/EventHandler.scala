package com.safechat.actors.common

//Allows testability
trait EventHandler[State, C, Event] {
  def applyEvent: (State, Event) ⇒ State
}
