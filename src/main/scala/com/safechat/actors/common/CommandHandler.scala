package com.safechat.actors.common

import com.safechat.actors.common.BasicPersistentActor.ValidationRejection

//Allows testability

trait CommandHandler[State, Cmd, Event] {

  type CreateEvent    = Event
  type DirectResponse = ValidationRejection
  type Result         = Either[DirectResponse, CreateEvent] //you either send rejection back or create an event

  /*
    total function (return an output for every input),
    deterministic (return the same output for the same input),
    free of side effects (only compute the return value, and don’t interact with the outside world)
   */
  def applyCommand: (Cmd, State) ⇒ Result
}
