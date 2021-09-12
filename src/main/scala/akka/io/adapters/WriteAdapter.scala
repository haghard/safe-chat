package akka.io.adapters

import akka.persistence.journal.WriteEventAdapter

/** https://github.com/rockthejvm/akka-persistence/blob/master/src/main/scala/part4_practices/EventAdapters.scala
  * https://github.com/calvinlfer/Akka-Persistence-Schema-Evolution-Example/blob/master/src/main/scala/com/experiments/calvin/eventadapters/ShoppingCartEventAdapter.scala
  *
  * WriteEventAdapter - used for backwards compatibility
  *
  * actor -> write event adapter -> serializer -> journal EventAdapter
  */
final class WriteAdapter extends WriteEventAdapter {
  override def manifest(event: Any): String = ""

  //always writes the latest event
  override def toJournal(event: Any): Any = event
}
