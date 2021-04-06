// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package akka
package io

import akka.actor.ExtendedActorSystem
import akka.serialization.SerializerWithStringManifest
import com.safechat.actors.ChatRoomEvent
import com.safechat.domain.RingBuffer
import com.safechat.serializer.SchemaRegistry
import org.apache.avro.Schema
import org.apache.avro.io.BinaryEncoder
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import org.apache.avro.specific.SpecificDatumReader
import org.apache.avro.specific.SpecificDatumWriter
import org.apache.avro.util.ByteBufferInputStream

import java.io.ByteArrayOutputStream
import java.io.NotSerializableException
import java.nio.ByteBuffer
import java.util
import java.util.TimeZone
import java.util.UUID
import scala.collection.mutable
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

 Schema-evolution-is-not-that-complex: https://medium.com/data-rocks/schema-evolution-is-not-that-complex-b7cf7eb567ac

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

  def readFromArray[T](bts: Array[Byte], writerSchema: Schema, readerSchema: Schema): T = {
    val decoder = DecoderFactory.get.binaryDecoder(bts, null)
    val reader  = new SpecificDatumReader[T](writerSchema, readerSchema)
    reader.read(null.asInstanceOf[T], decoder)
  }

  def readFromBuffer[T](buf: ByteBuffer, writerSchema: Schema, readerSchema: Schema): T = {
    val reader = new SpecificDatumReader[T](writerSchema, readerSchema)
    val decoder =
      DecoderFactory.get.directBinaryDecoder(new ByteBufferInputStream(util.Collections.singletonList(buf)), null)
    reader.read(null.asInstanceOf[T], decoder)
  }

  def notSerializable(msg: String) = throw new NotSerializableException(msg)

  def illegalArgument(msg: String) = throw new IllegalArgumentException(msg)

  /** Possible inputs:
    *   com.safechat.actors.ChatRoomEvent
    *   com.safechat.actors.ChatRoomState
    *
    *   Mapping between domain events and persistent model
    */
  def manifest(o: AnyRef, activeSchemaHash: String, mapping: Map[String, String]): String =
    //Here we do mapping between domain events and persistent model
    o match {
      case ev: ChatRoomEvent ⇒
        s"$EVENT_PREF${mapping(ev.getClass.getName)}$SEP$activeSchemaHash"
      case _: com.safechat.actors.ChatRoomState ⇒
        //swap up domain ChatRoomState with persistent ChatRoomState from schema
        s"$STATE_PREF${classOf[com.safechat.avro.persistent.state.ChatRoomState].getName}$SEP$activeSchemaHash"
    }

  def toArray(
    o: AnyRef,
    activeSchemaHash: String,
    schemaMap: Map[String, Schema]
  ): Array[Byte] = {
    val schema = schemaMap(activeSchemaHash)
    o match {
      case state: com.safechat.actors.ChatRoomState ⇒
        Using.resource(new ByteArrayOutputStream(256)) { baos ⇒
          Using.resource(EncoderFactory.get.directBinaryEncoder(baos, null)) { enc ⇒
            val users = new java.util.HashMap[CharSequence, CharSequence]()
            state.users.foreach { case (login, pubKey) ⇒
              users.put(login, pubKey)
            }
            val history = new util.ArrayList[CharSequence]()
            state.recentHistory.entries.foreach(history.add(_))
            new SpecificDatumWriter[com.safechat.avro.persistent.state.ChatRoomState](schema)
              .write(new com.safechat.avro.persistent.state.ChatRoomState(users, history), enc)
          }
          baos.toByteArray
        }

      case e: ChatRoomEvent ⇒
        Using.resource(new ByteArrayOutputStream(256)) { baos ⇒
          Using.resource(EncoderFactory.get.directBinaryEncoder(baos, null)) { enc ⇒
            new SpecificDatumWriter(schema).write(withEnvelope(e), enc)
          }
          baos.toByteArray
        }
    }
  }

  def fromArray(
    bts: Array[Byte],
    manifest: String,
    activeSchemaHash: String,
    schemaMap: Map[String, Schema],
    recentHistorySize: Int
  ): AnyRef = {
    val writerSchemaKey = manifest.split(SEP)(1)
    //println(s"fromBinary Schemas:[writer:$writerSchemaKey reader:$activeSchemaHash]")
    val writerSchema = schemaMap(writerSchemaKey)
    val readerSchema = schemaMap(activeSchemaHash)

    if (manifest.startsWith(EVENT_PREF)) {
      val envelope = readFromArray[com.safechat.avro.persistent.domain.EventEnvelope](bts, writerSchema, readerSchema)
      val payload  = envelope.getPayload.asInstanceOf[org.apache.avro.specific.SpecificRecordBase]
      payload match {
        case p: com.safechat.avro.persistent.domain.UserJoined ⇒
          ChatRoomEvent.UserJoined(p.getLogin.toString, p.getSeqNum, p.getPubKey.toString)

        case p: com.safechat.avro.persistent.domain.UserTextAdded ⇒
          ChatRoomEvent.UserTextAdded(
            p.getUser.toString,
            p.getSeqNum,
            p.getReceiver.toString,
            p.getText.toString,
            envelope.getWhen,
            envelope.getTz.toString
          )

        /*
        case p: com.safechat.avro.persistent.domain.UserTextsAdded ⇒
          var c = Vector.empty[Content]
          p.getContent.forEach { line ⇒
            val segs     = line.toString.split(ChatRoomClassic.MSG_SEP)
            val sender   = segs(0)
            val receiver = segs(1)
            val content  = segs(2)
            c = c.:+(Content(sender, receiver, content))
          }
          ChatRoomEvent.UserTextsAdded(p.getSeqNum, c, envelope.getWhen, envelope.getTz.toString)
         */

        case p: com.safechat.avro.persistent.domain.UserDisconnected ⇒
          ChatRoomEvent.UserDisconnected(p.getLogin.toString)
        case _ ⇒
          notSerializable(
            s"Deserialization for event $manifest not supported. Check fromBinary method in ${this.getClass.getName} class."
          )
      }
    } else if (manifest.startsWith(STATE_PREF)) {
      val state    = readFromArray[com.safechat.avro.persistent.state.ChatRoomState](bts, writerSchema, readerSchema)
      val userKeys = mutable.Map.empty[String, String]
      state.getRegisteredUsers.forEach((login, pubKey) ⇒ userKeys.put(login.toString, pubKey.toString))
      val s = com.safechat.actors.ChatRoomState(users = userKeys, recentHistory = RingBuffer(recentHistorySize))
      state.getRecentHistory.forEach(e ⇒ s.recentHistory.:+(e.toString))
      s
    } else
      illegalArgument(
        s"Deserialization for $manifest not supported. Check fromBinary method in ${this.getClass.getName} class."
      )
  }

  private def withEnvelope(e: ChatRoomEvent) =
    e match {
      case e: ChatRoomEvent.UserJoined ⇒
        new com.safechat.avro.persistent.domain.EventEnvelope(
          UUID.randomUUID.toString,
          System.currentTimeMillis,
          TimeZone.getDefault.getID,
          com.safechat.avro.persistent.domain.UserJoined.newBuilder
            .setLogin(e.userId)
            .setPubKey(e.pubKey)
            .setSeqNum(e.seqNum)
            .build()
        )

      case ChatRoomEvent.UserTextAdded(userId, seqNum, receiver, content, when, tz) ⇒
        new com.safechat.avro.persistent.domain.EventEnvelope(
          UUID.randomUUID.toString,
          when,
          tz,
          new com.safechat.avro.persistent.domain.UserTextAdded(seqNum, userId, receiver, content)
        )

      /*
      case e: ChatRoomEvent.UserTextsAdded ⇒
        val content = new util.ArrayList[CharSequence](e.msgs.size)
        e.msgs.foreach(c ⇒
          content.add(s"${c.userId}${ChatRoomClassic.MSG_SEP}${c.recipient}${ChatRoomClassic.MSG_SEP}${c.content}")
        )
        new com.safechat.avro.persistent.domain.EventEnvelope(
          UUID.randomUUID.toString,
          e.when,
          e.tz,
          new com.safechat.avro.persistent.domain.UserTextsAdded(e.seqNum, content)
        )
       */

      case e: ChatRoomEvent.UserDisconnected ⇒
        new com.safechat.avro.persistent.domain.EventEnvelope(
          UUID.randomUUID.toString,
          System.currentTimeMillis,
          TimeZone.getDefault.getID,
          com.safechat.avro.persistent.domain.UserDisconnected.newBuilder.setLogin(e.userId).build
        )

    }
}

/** https://doc.akka.io/docs/akka/current/remoting-artery.html#bytebuffer-based-serialization
  *
  * Artery introduced a new serialization mechanism.
  * This implementation takes advantage of new Artery serialization mechanism
  * which allows the ByteBufferSerializer to directly write into and read from a shared java.nio.ByteBuffer
  * instead of being forced to allocate and return an Array[Byte] for each serialized message.
  *
  * https://blog.softwaremill.com/akka-references-serialization-with-protobufs-up-to-akka-2-5-87890c4b6cb0
  */
final class JournalEventsSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
  val recentHistorySize = system.settings.config.getInt("safe-chat.recent-history-size")

  override val identifier = 99999

  private val (activeSchemaHash, schemaMap) = SchemaRegistry()

  //Mapping from domain events to avro classes that are being used for persistence
  private val mapping =
    SchemaRegistry.journalEvents(system.settings.config.getConfig("akka.actor.serialization-bindings"))

  override def manifest(obj: AnyRef): String =
    obj match {
      case _: ChatRoomEvent ⇒
        JournalEventsSerializer.manifest(obj, activeSchemaHash, mapping)
      case _: com.safechat.actors.ChatRoomState ⇒
        JournalEventsSerializer.manifest(obj, activeSchemaHash, mapping)
    }

  override def toBinary(obj: AnyRef): Array[Byte] =
    JournalEventsSerializer.toArray(obj, activeSchemaHash, schemaMap)

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    JournalEventsSerializer.fromArray(bytes, manifest, activeSchemaHash, schemaMap, recentHistorySize)
}

/*

final class JournalEventsSerializer1(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with ByteBufferSerializer {

  override val identifier = 99999

  private val (activeSchemaHash, schemaMap) = SchemaRegistry()

  private val maxFrameSize = system.settings.config.getBytes("akka.remote.artery.advanced.maximum-frame-size").toInt

  // Mapping from domain event to avro class that's being used for persistence
  private val mapping =
    SchemaRegistry.journalEvents(system.settings.config.getConfig("akka.actor.serialization-bindings"))

  private val bufferPool =
    new akka.io.DirectByteBufferPool(defaultBufferSize = maxFrameSize, maxPoolEntries = 64)

  override def manifest(obj: AnyRef): String = JournalEventsSerializer.manifest(obj, activeSchemaHash, mapping)

  /** Allows the ByteBufferSerializer to directly write into a shared java.nio.ByteBuffer
 * instead of being forced to allocate and return an Array[Byte] for each serialized message.
 * Very useful for apps without persistence.
 */
  override def toBinary(obj: AnyRef): Array[Byte] = {
    val directByteBuffer = bufferPool.acquire()
    try {
      toBinary(obj, directByteBuffer)
      directByteBuffer.flip() //switch to read mode
      //read it back to byte array
      val bytes = new Array[Byte](directByteBuffer.remaining)
      //println("toBinary: " + buf.remaining)
      directByteBuffer.get(bytes)
      bytes
    } finally bufferPool.release(directByteBuffer)
  }

  //Serializes the given object to be sent into the `ByteBuffer`.
  override def toBinary(obj: AnyRef, directByteBuffer: ByteBuffer): Unit =
    JournalEventsSerializer.toBuffer(obj, directByteBuffer, activeSchemaHash, schemaMap)

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    //fromBinary(ByteBuffer.wrap(bytes), manifest)

    //data from journal -> buffer -> jvm object
    val directByteBuffer = bufferPool.acquire()
    try {
      directByteBuffer.put(bytes)
      directByteBuffer.flip()
      fromBinary(directByteBuffer, manifest)
    } finally bufferPool.release(directByteBuffer)
  }

  override def fromBinary(buf: ByteBuffer, manifest: String): AnyRef =
    JournalEventsSerializer.fromBuffer(buf, manifest, activeSchemaHash, schemaMap)
}

final class JournalEventsSerializer2(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with ByteBufferSerializer {

  import one.nio.mem.{DirectMemory, FixedSizeAllocator}

  override val identifier = 99999

  private val (activeSchemaHash, schemaMap) = SchemaRegistry()

  private val maxFrameSize =
    system.settings.config.getBytes("akka.remote.artery.advanced.maximum-frame-size").toInt

  // Mapping from domain event to avro class that's being used for persistence
  private val mapping =
    SchemaRegistry.journalEvents(system.settings.config.getConfig("akka.actor.serialization-bindings"))

  override def manifest(o: AnyRef): String =
    JournalEventsSerializer.manifest(o, activeSchemaHash, mapping)

  private val concurrencyLevel = 1 << 4

  private val extraSpace = 1024 * 2

  //private val allocator  = new MallocMT((maxFrameSize + extraSpace) * concurrencyLevel, concurrencyLevel)

  /*
    A range of tools for managing off-heap memory.
      DirectMemory: allows to allocate memory beyond Java Heap.
      MappedFile: maps and unmaps files to RAM. Supports files larger than 2 GB.
      Malloc: off-heap memory allocator that works in a given memory range.
      MallocMT: a special version of Malloc for multi-threaded applications.
      FixedSizeAllocator: extremely fast lock-free allocator that manages chunks of the fixed size.
      LongHashSet, LongLongHashMap, LongObjectHashMap: off-heap lock-free hash tables with 64-bit keys.
      OffheapMap: an abstraction for building general purpose off-heap hash tables.
      SharedMemory*Map: a generic solution for caching data in shared memory or memory-mapped files.
 */
  //val allocator = new Malloc(maxFrameSize + (1024 * 2))

  //Lock-free allocator that manages chunks of the fixed size.
  private val allocator =
    new FixedSizeAllocator(maxFrameSize + extraSpace, (maxFrameSize + extraSpace) * concurrencyLevel)

  /** Allows the ByteBufferSerializer to directly write into a shared java.nio.ByteBuffer
 * instead of being forced to allocate and return an Array[Byte] for each serialized message.
 */
  override def toBinary(obj: AnyRef): Array[Byte] = {
    //DirectMemory: [used:264192 total:8454144]
    //println(s"DirectMemory: [entry:${allocator.entrySize} total:${allocator.chunkSize} ]")

    //allocate a buffer in direct memory
    val address = allocator.malloc(maxFrameSize)
    //wrap the buffer in ByteBuffer
    val buf = DirectMemory.wrap(address, maxFrameSize)
    try {
      toBinary(obj, buf)
      buf.flip()
      val bytes = new Array[Byte](buf.remaining)
      buf.get(bytes)
      //println(s"toBinary: ${bytes.size}")
      bytes
    } finally {
      buf.clear()
      try allocator.free(address)
      catch {
        case err: Throwable ⇒
          JournalEventsSerializer.illegalArgument("Allocator free error :" + err.getMessage)
      }
    }
  }

  override def toBinary(obj: AnyRef, directByteBuffer: ByteBuffer): Unit =
    JournalEventsSerializer.toBuffer(obj, directByteBuffer, activeSchemaHash, schemaMap)

  override def fromBinary(buf: ByteBuffer, manifest: String): AnyRef =
    ???
  //JournalEventsSerializer.fromBuffer(buf, manifest, activeSchemaHash, schemaMap)

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    //fromBinary(ByteBuffer.wrap(bytes), manifest)
    JournalEventsSerializer.fromArray(bytes, manifest, activeSchemaHash, schemaMap)

  /*
    val address = allocator.malloc(maxFrameSize)
    val buf = DirectMemory.wrap(address, maxFrameSize)
    try fromBinary(buf, manifest)
    finally {
      buf.clear()
      try allocator.free(address)
      catch {
        case err: Throwable ⇒
          JournalEventsSerializer.illegalArgument("Allocator free error :" + err.getMessage)
      }
    }
 */

}
 */

//https://github.com/rsocket/rsocket-transport-akka
//https://www.lightbend.com/blog/implementing-rsocket-ingress-in-cloudflow-part-3-pluggable-transport

//https://www.youtube.com/watch?v=_rqQtkIeNIQ
//https://github.com/netifi/akka-demo

//https://www.lightbend.com/blog/implementing-rsocket-ingress-in-cloudflow-part-3-pluggable-transport
//https://github.com/lightbend/RSocketCloudflow/blob/master/transports/src/main/scala/com/lightbend/rsocket/transport/ipc/RequestResponceIPC.scala
//https://github.com/lightbend/RSocketCloudflow/blob/master/transports/src/main/scala/com/lightbend/rsocket/transport/ipc/RequestStreamIPC.scala

//https://github.com/brendangregg/perf-tools

/*

import io.netty.util.CharsetUtil
import io.netty.buffer.{ByteBufAllocator, ByteBufUtil, PooledByteBufAllocator, Unpooled}

//ByteBufUtil.hexDump(???)
  val nettyAllocator = PooledByteBufAllocator.DEFAULT
  //ByteBufAllocator.DEFAULT

  //if (Integer.bitCount(78) != 1) throw new IllegalArgumentException("Max packets must be a power of 2")

  val byteBuf = nettyAllocator.buffer()
  byteBuf.writeCharSequence("topicName", CharsetUtil.UTF_8)
  byteBuf.toString(CharsetUtil.UTF_8)
  //byteBuf.readBytes()
  byteBuf.release()
  //Unpooled.wrappedBuffer(/*data.asByteBuffer*/)
 */
