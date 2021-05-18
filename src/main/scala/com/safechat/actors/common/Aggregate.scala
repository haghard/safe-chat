package com.safechat.actors.common

import akka.actor.ActorLogging
import akka.persistence.PersistentActor
import akka.persistence.RecoveryCompleted
import akka.persistence.SaveSnapshotFailure
import akka.persistence.SaveSnapshotSuccess
import akka.persistence.SnapshotOffer
import com.safechat.actors.common.Aggregate._

import scala.reflect.ClassTag

object Aggregate {
  case object Snapshot

  final case class ValidationRejection(msg: String) extends AnyVal

  sealed trait AggReply[+Event]
  final case class RejectCmd(r: ValidationRejection) extends AggReply[Nothing]
  final case class PersistEvent[E](event: E)         extends AggReply[E]

}

/** https://github.com/RBMHTechnology/eventuate/blob/2d6c93ea400822047e702c46ddce22f261898342/eventuate-core/src/main/scala/com/rbmhtechnology/eventuate/VersionedAggregate.scala
  * https://gitlab.com/makeorg/platform/core-api/-/blob/preproduction/api/src/main/scala/org/make/api/technical/MakePersistentActor.scala
  */
abstract class Aggregate[State, Cmd, Event](
  var state: State,
  snapshotEvery: Int
)(implicit stateTag: ClassTag[State], cmdTag: ClassTag[Cmd], eventTag: ClassTag[Event])
    extends PersistentActor
    with ActorLogging { self: CommandHandler[State, Cmd, Event] with EventHandler[State, Event] ⇒

  override def receiveRecover: Receive = {
    case e: Event ⇒
      state = applyEvent(e, state)
    case SnapshotOffer(_, snapshot: State) ⇒
      state = snapshot
    case RecoveryCompleted ⇒
      onRecoveryCompleted(state)
    case other ⇒
      log.error("Unable to handle {} during recovery", other)
  }

  def onRecoveryCompleted(state: State)

  override def receiveCommand: Receive = {
    case cmd: Cmd ⇒
      applyCommand(cmd, state) match {
        case RejectCmd(reply) ⇒
          sender() ! reply
        case PersistEvent(event) ⇒
          persist(event) { ev ⇒
            state = applyEvent(ev, state)
            if (lastSequenceNr % snapshotEvery == 0)
              maybeSnapshot(state)
          }
      }

    // snapshot-related messages
    case SaveSnapshotSuccess(metadata) ⇒
      log.info(s"Saving snapshot succeeded: $metadata")

    case SaveSnapshotFailure(metadata, reason) ⇒
      log.warning(s"Saving snapshot $metadata failed because of $reason")

    case other ⇒
      log.error("Unable to validate unknown cmd {}, ignoring it", other)
  }

  def maybeSnapshot(state: State): Unit =
    if (lastSequenceNr % snapshotEvery == 0) {
      saveSnapshot(state)
    }
}
