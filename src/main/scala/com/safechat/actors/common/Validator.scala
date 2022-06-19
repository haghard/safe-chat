package com.safechat.actors.common

object Validator {

  val empty: Validator[Any, Any, Nothing, Nothing] =
    succeed[Nothing]()

  def fail[E](error: E): Validator[Any, Any, E, Nothing] = Validator((_, _) => Left(error))

  def succeed[Event](events: Event*): Validator[Any, Any, Nothing, Event] =
    Validator((_, _) => Right(events.head))

  def validate[C, S, E, V](error: E)(pf: PartialFunction[(C, S), V]): Validator[C, S, E, V] =
    Validator((c, s) => pf.lift((c, s)).map(Right(_)).getOrElse(Left(error)))

}

final case class Validator[-Command, -State, +Err, +Event](
  run: (Command, State) => Either[Err, Event]
) {

  def ++[C1 <: Command, S1 <: State, E1 >: Error, V1 >: Event](
    that: Validator[C1, S1, E1, V1]
  ): Validator[C1, S1, E1, V1] = ???

  // Allows partiality: You can take one term in you sum type and you can stitch all of them together to handle all terms in your sum type
  def ||[C1 <: Command, S1 <: State, E1 >: Error, V1 >: Event](
    that: Validator[C1, S1, E1, V1]
  ): Validator[C1, S1, E1, V1] = ???

  // a validator that works on a part of your state and lift it up to work on another part of state
  def embed[State2](f: State2 => State): Validator[Command, State2, Err, Event] = ???
}
