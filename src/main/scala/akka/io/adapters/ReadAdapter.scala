package akka.io.adapters

import akka.persistence.journal.EventSeq
import akka.persistence.journal.ReadEventAdapter

/** https://github.com/rockthejvm/akka-persistence/blob/master/src/main/scala/part4_practices/EventAdapters.scala
  *
  * journal -> serializer -> read event adapter ->  actor
  *  (bytes)     (GA)            (GAV2)             (receiveRecover)
  */
final class ReadAdapter extends ReadEventAdapter {

  override def fromJournal(event: Any, manifest: String): EventSeq = {
    /*
    Psevdo code
    case ev: EventV1 =>
      println("Reading V1 from journal and doing promotion to V3")
      EventSeq.single(ItemV3(each.id, each.name)))

    case ev: EventV2 =>
      println("Reading V2 from journal and doing promotion to V3")
      EventV3(each.id, each.name))

    case ev: EventV3 =>
      println("V3 event, no promotion needed")
      EventSeq.single(ev)
    */
    EventSeq.single(event)
  }
}
