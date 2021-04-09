package com.safechat.actors.common

trait EventHandler[State, Cmd, Event] {

  /*
    total function (return an output for every input),
    deterministic (return the same output for the same input),
    free of side effects (only compute the return value, and don’t interact with the outside world)
   */
  def applyEvent: (Event, State) ⇒ State
}
