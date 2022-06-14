package com.safechat.actors.common

trait CommandHandler[State, Cmd, Event] {

  def applyCommand: (Cmd, State) => com.safechat.actors.common.Aggregate.AggReply[Event]
}
