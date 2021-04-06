package com.safechat.actors.common

import com.safechat.actors.common.BasicPersistentActor.ValidationRejection

//Allows testability
trait CommandHandler[State, C, Event] {

  type DirectResponse = ValidationRejection
  type CreateEvent    = Event
  type Result         = Either[DirectResponse, CreateEvent] //you either send rejection back or create an event

  //should be implemented using pattern matching to ensure command exhaustion
  def applyCommand: C ⇒ Result
}
