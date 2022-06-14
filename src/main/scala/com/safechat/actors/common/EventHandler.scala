package com.safechat.actors.common

trait EventHandler[State, Event] {

  def applyEvent: (Event, State) => State
}
