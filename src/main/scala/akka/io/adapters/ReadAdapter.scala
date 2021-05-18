package akka.io.adapters

import akka.persistence.journal.EventSeq
import akka.persistence.journal.ReadEventAdapter

/** https://github.com/rockthejvm/akka-persistence/blob/master/src/main/scala/part4_practices/EventAdapters.scala
  *
  * journal -> serializer -> read event adapter ->  actor
  *  (bytes)     (GA)            (GAV2)             (receiveRecover)
  */
final class ReadAdapter extends ReadEventAdapter {

  override def fromJournal(event: Any, manifest: String): EventSeq =
    EventSeq.single(event)
}
