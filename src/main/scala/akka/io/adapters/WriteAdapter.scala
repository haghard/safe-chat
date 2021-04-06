package akka.io.adapters

/** WriteEventAdapter - used for backwards compat
  * actor -> write adapter -> serializer -> journal
  * EventAdapter
  */

/*

import akka.io.JournalEventsSerializer
import akka.persistence.journal.WriteEventAdapter
import com.safechat.actors.ChatRoomEvent
import com.safechat.serializer.SchemaRegistry


final class WriteAdapter extends WriteEventAdapter {

  private val (activeSchemaHash, schemaMap) = SchemaRegistry()

  //Mapping from domain events to avro classes that are being used for persistence
    private val mapping =
      SchemaRegistry.journalEvents(system.settings.config.getConfig("akka.actor.serialization-bindings"))

  override def manifest(event: Any): String = {
    event match {
      case _: ChatRoomEvent ⇒
        JournalEventsSerializer.manifest(event, activeSchemaHash, mapping)
      case _: com.safechat.actors.ChatRoomState ⇒
        JournalEventsSerializer.manifest(event, activeSchemaHash, mapping)
    }
  }

  override def toJournal(event: Any): Any = event
}
 */
