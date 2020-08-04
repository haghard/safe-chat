// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package akka.io

import java.io.{ByteArrayOutputStream, NotSerializableException}
import java.nio.ByteBuffer
import java.util
import java.util.{TimeZone, UUID}

import akka.actor.ExtendedActorSystem
import akka.serialization.{ByteBufferSerializer, SerializerWithStringManifest}
import com.safechat.actors.{ChatRoomEvent, UserDisconnected, UserJoined, UserTextAdded}
import com.safechat.serializer.AvroSchemaRegistry
import org.apache.avro.Schema
import org.apache.avro.io.{BinaryEncoder, DecoderFactory, EncoderFactory}
import org.apache.avro.specific.{SpecificDatumReader, SpecificDatumWriter}

import scala.reflect.ClassTag
import scala.util.Using
import scala.util.Using.Releasable

/*


Schema evolution allows you to update the schema used to write new data, while maintaining backwards compatibility with the schema(s) of your old data.
Then you can read it all together, as if all of the data has one schema. Of course there are precise rules governing the changes
allowed, to maintain compatibility.

Avro provides full compatibility support.
Backward compatibility is necessary for reading the old version of events.
Forward compatibility is required for rolling updates when at the same time old and new versions of events
can be exchanged between processes.

1.  Backward compatible change - write with V1 and read with V2
2.  Forward compatible change -  write with V2 and read with V1
3.  Fully compatible if your change is Backward and Forward compatible
4.  Breaking is non of those

Advice when writing Avro schema
 * Add field with defaults
 * Remove only fields which have defaults

If you target full compatibility follows these rules:
 * Removing fields with defaults is fully compatible change
 * Adding fields with defaults is fully compatible change

  Enum can't evolve over time.

  When evolving schema, ALWAYS give defaults.

  When evolving schema, NEVER
 * rename fields
 * remove required fields

 */
object JournalEventsSerializer {
  val SEP         = ":"
  val subEventSEP = "/"

  val EVENT_PREF = "EVENT_"
  val STATE_PREF = "STATE_"

  implicit object BinaryEncoderIsReleasable extends Releasable[BinaryEncoder] {
    def release(resource: BinaryEncoder): Unit =
      resource.flush
  }

  def fromAvroBytes[T](bts: Array[Byte], writerSchema: Schema, readerSchema: Schema): T = {
    val reader  = new SpecificDatumReader[T](writerSchema, readerSchema)
    val decoder = DecoderFactory.get.binaryDecoder(bts, null)
    reader.read(null.asInstanceOf[T], decoder)
  }

  def toAvroBytes[T: ClassTag](ev: T, schema: Schema): Array[Byte] =
    Using.resource(new ByteArrayOutputStream()) { baos ⇒
      Using.resource(EncoderFactory.get.binaryEncoder(baos, null)) { enc ⇒
        new SpecificDatumWriter[T](schema).write(ev, enc)
      }
      baos.toByteArray
    }

  def notSerializable(msg: String) = throw new NotSerializableException(msg)

  def illegalArgument(msg: String) = throw new IllegalArgumentException(msg)

  /**
    * Possible inputs:
    *   com.safechat.actors.ChatRoomEvent
    *   com.safechat.actors.ChatRoomState
    *
    *   Mapping between domain events and persistent model
    */
  def manifest(o: AnyRef, activeSchemaHash: String, mapping: Map[String, String]): String =
    //Here we do mapping between domain events and persistent model
    o match {
      case ev: ChatRoomEvent ⇒
        EVENT_PREF + mapping(ev.getClass.getName) + SEP + activeSchemaHash
      case _: com.safechat.actors.ChatRoomState ⇒
        //swap up domain ChatRoomState with persistent ChatRoomState from schema
        STATE_PREF + classOf[com.safechat.persistent.state.ChatRoomState].getName + SEP + activeSchemaHash
    }

  /**
    * Possible inputs:
    *   com.safechat.actors.ChatRoomEvent
    *   com.safechat.actors.ChatRoomState
    */
  def toBinary(o: AnyRef, activeSchemaHash: String, schemaMap: Map[String, Schema]): Array[Byte] = {
    val schema = schemaMap(activeSchemaHash)
    o match {
      case state: com.safechat.actors.ChatRoomState ⇒
        Using.resource(new ByteArrayOutputStream()) { out ⇒
          Using.resource(EncoderFactory.get.binaryEncoder(out, null)) { enc ⇒
            val users = new java.util.HashMap[CharSequence, CharSequence]()
            state.regUsers.foreach {
              case (login, pubKey) ⇒
                users.put(login, pubKey)
            }
            val history = new util.ArrayList[CharSequence]()
            state.recentHistory.entries.foreach(history.add(_))
            new SpecificDatumWriter[com.safechat.persistent.state.ChatRoomState](schema)
              .write(new com.safechat.persistent.state.ChatRoomState(users, history), enc)
          }
          out.toByteArray
        }

      case e: ChatRoomEvent ⇒
        val env = e match {
          case e: UserJoined ⇒
            new com.safechat.persistent.domain.MsgEnvelope(
              UUID.randomUUID.toString,
              System.currentTimeMillis,
              TimeZone.getDefault.getID,
              com.safechat.persistent.domain.UserJoined.newBuilder.setLogin(e.originator).setPubKey(e.pubKey).build()
            )
          case UserTextAdded(originator, receiver, content, when, tz) ⇒
            new com.safechat.persistent.domain.MsgEnvelope(
              UUID.randomUUID.toString,
              when,
              tz,
              new com.safechat.persistent.domain.UserTextAdded(originator, receiver, content)
            )
          case e: UserDisconnected ⇒
            new com.safechat.persistent.domain.MsgEnvelope(
              UUID.randomUUID.toString,
              System.currentTimeMillis,
              TimeZone.getDefault.getID,
              com.safechat.persistent.domain.UserDisconnected.newBuilder.setLogin(e.originator).build
            )
        }
        toAvroBytes[com.safechat.persistent.domain.MsgEnvelope](env, schema)
    }
  }

  def fromBinary(
    bytes: Array[Byte],
    manifest: String,
    activeSchemaHash: String,
    schemaMap: Map[String, Schema]
  ): AnyRef = {
    val writerSchemaKey = manifest.split(SEP)(1)
    //println(s"fromBinary Schemas:[writer:$writerSchemaKey reader:$activeSchemaHash]")

    val writerSchema = schemaMap(writerSchemaKey)
    val readerSchema = schemaMap(activeSchemaHash)

    if (manifest.startsWith(EVENT_PREF)) {
      val envelope = fromAvroBytes[com.safechat.persistent.domain.MsgEnvelope](bytes, writerSchema, readerSchema)
      if (envelope.getPayload.isInstanceOf[com.safechat.persistent.domain.UserJoined]) {
        val event = envelope.getPayload.asInstanceOf[com.safechat.persistent.domain.UserJoined]
        UserJoined(event.getLogin.toString, event.getPubKey.toString)
      } else if (envelope.getPayload.isInstanceOf[com.safechat.persistent.domain.UserTextAdded]) {
        val event = envelope.getPayload.asInstanceOf[com.safechat.persistent.domain.UserTextAdded]
        UserTextAdded(
          event.getUser.toString,
          event.getReceiver.toString,
          event.getText.toString,
          envelope.getWhen,
          envelope.getTz.toString
        )
      } else if (envelope.getPayload.isInstanceOf[com.safechat.persistent.domain.UserDisconnected]) {
        val event = envelope.getPayload.asInstanceOf[com.safechat.persistent.domain.UserDisconnected]
        UserDisconnected(event.getLogin.toString)
      } else
        notSerializable(
          s"Deserialization for event $manifest not supported. Check fromBinary method in ${this.getClass.getName} class."
        )

    } else if (manifest.startsWith(STATE_PREF)) {
      val state    = fromAvroBytes[com.safechat.persistent.state.ChatRoomState](bytes, writerSchema, readerSchema)
      var userKeys = Map.empty[String, String]
      state.getRegisteredUsers.forEach((login, pubKey) ⇒ userKeys = userKeys + (login.toString → pubKey.toString))

      val s = com.safechat.actors.ChatRoomState(regUsers = userKeys)
      state.getRecentHistory.forEach(e ⇒ s.recentHistory.add(e.toString))
      s
    } else
      notSerializable(
        s"Deserialization for $manifest not supported. Check fromBinary method in ${this.getClass.getName} class."
      )
  }
}

//https://doc.akka.io/docs/akka/current/remoting-artery.html#bytebuffer-based-serialization
final class JournalEventsSerializer(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with ByteBufferSerializer {

  override val identifier = 99999

  private val (activeSchemaHash, schemaMap) = AvroSchemaRegistry()

  private val frameSize =
    system.settings.config.getBytes("akka.remote.artery.advanced.maximum-frame-size").toInt

  // Mapping from domain event to avro class that's being used for persistence
  private val mapping =
    AvroSchemaRegistry
      .eventTypesMapping(system.settings.config.getConfig("akka.actor.serialization-bindings"))

  // you need to know the maximum size in bytes of the serialized messages
  private val bufferPool =
    new akka.io.DirectByteBufferPool(defaultBufferSize = frameSize, maxPoolEntries = 64)

  override def manifest(obj: AnyRef): String =
    JournalEventsSerializer.manifest(obj, activeSchemaHash, mapping)

  override def toBinary(o: AnyRef): Array[Byte] = {
    val buf = bufferPool.acquire()
    try {
      toBinary(o, buf)
      buf.flip()
      val bytes = new Array[Byte](buf.remaining)
      buf.get(bytes)
      bytes
    } finally bufferPool.release(buf)
  }

  /*
  override def toBinary(obj: AnyRef): Array[Byte] = {
    //in production code, acquire this from a BufferPool
    val buf = ByteBuffer.allocate(frameSize)
    toBinary(obj, buf)
    buf.flip()
    val bytes = new Array[Byte](buf.remaining)
    buf.get(bytes)
    bytes
  }*/

  override def toBinary(obj: AnyRef, buf: ByteBuffer): Unit = {
    val bts = JournalEventsSerializer.toBinary(obj, activeSchemaHash, schemaMap)
    if (bts.size > frameSize)
      JournalEventsSerializer.illegalArgument(s"Oversized payload: Limit:$frameSize Actual:${bts.size}")
    else buf.put(bts)
  }

  // Implement this method for compatibility with `SerializerWithStringManifest`.
  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinary(ByteBuffer.wrap(bytes), manifest)

  override def fromBinary(buf: ByteBuffer, manifest: String): AnyRef =
    JournalEventsSerializer.fromBinary(buf.array(), manifest, activeSchemaHash, schemaMap)
}