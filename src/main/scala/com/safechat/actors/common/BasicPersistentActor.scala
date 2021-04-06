package com.safechat.actors.common

import akka.actor.ActorLogging
import akka.persistence.PersistentActor
import akka.persistence.RecoveryCompleted
import akka.persistence.SaveSnapshotFailure
import akka.persistence.SaveSnapshotSuccess
import akka.persistence.SnapshotOffer
import com.safechat.actors.common.BasicPersistentActor._

import scala.reflect.ClassTag

object BasicPersistentActor {
  case object Snapshot

  final case class ValidationRejection(msg: String)

  val NoEvent: Left.type       = Left
  val PersistEvent: Right.type = Right
}

//https://gitlab.com/makeorg/platform/core-api/-/blob/preproduction/api/src/main/scala/org/make/api/technical/MakePersistentActor.scala
abstract class BasicPersistentActor[State, C, Event](var state: State, snapshotEvery: Int)(implicit
  stateTag: ClassTag[State],
  cmdTag: ClassTag[C],
  eventTag: ClassTag[Event]
) extends PersistentActor
    with ActorLogging { self: CommandHandler[State, C, Event] with EventHandler[State, C, Event] ⇒

  override def receiveRecover: Receive = {
    case e: Event ⇒
      state = applyEvent(state, e)
    case SnapshotOffer(_, snapshot: State) ⇒
      state = snapshot
    case RecoveryCompleted ⇒
      onRecoveryCompleted(state)
    case other ⇒
      log.error("Unable to handle {} during recovery", other)
  }

  def onRecoveryCompleted(state: State)

  override def receiveCommand: Receive = {
    val PF = PartialFunction.fromFunction(applyCommand)

    {
      case cmd: C ⇒
        if (PF.isDefinedAt(cmd)) {
          applyCommand(cmd) match {
            case NoEvent(reply) ⇒
              sender() ! reply
            case PersistEvent(event) ⇒
              persist(event) { ev ⇒
                state = applyEvent(state, ev)
                if (lastSequenceNr % snapshotEvery == 0)
                  maybeSnapshot(state)
              }
          }
        } else log.error("Unable to validate cmd {}, ignoring it", cmd)

      // snapshot-related messages
      case SaveSnapshotSuccess(metadata) ⇒
        log.info(s"Saving snapshot succeeded: $metadata")

      case SaveSnapshotFailure(metadata, reason) ⇒
        log.warning(s"Saving snapshot $metadata failed because of $reason")

      case other ⇒
        log.error("Unable to validate unknown cmd {}, ignoring it", other)
    }
  }

  def maybeSnapshot(state: State): Unit =
    if (lastSequenceNr % snapshotEvery == 0) {
      saveSnapshot(state)
    }
}
