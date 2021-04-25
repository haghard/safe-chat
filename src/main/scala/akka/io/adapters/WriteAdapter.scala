package akka.io.adapters

import akka.persistence.journal.WriteEventAdapter

/** https://github.com/rockthejvm/akka-persistence/blob/master/src/main/scala/part4_practices/EventAdapters.scala
  *
  * WriteEventAdapter - used for backwards compatibility
  *
  * actor -> write event adapter -> serializer -> journal EventAdapter
  */
final class WriteAdapter extends WriteEventAdapter {
  override def manifest(event: Any): String = ???

  override def toJournal(event: Any): Any = event
}
