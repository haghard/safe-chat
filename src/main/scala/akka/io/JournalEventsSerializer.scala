// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package akka
package io

import java.io.{ByteArrayOutputStream, NotSerializableException}
import java.nio.ByteBuffer
import java.util
import java.util.{TimeZone, UUID}
import akka.actor.ExtendedActorSystem
import akka.io.JournalEventsSerializer.{withEnvelope, BinaryEncoderIsReleasable}
import akka.serialization.{ByteBufferSerializer, SerializationExtension, SerializerWithStringManifest}
import akka.stream.impl.streamref.StreamRefsProtocol
import akka.stream.serialization.StreamRefSerializer
import com.safechat.actors.{ChatRoomEvent, UserDisconnected, UserJoined, UserTextAdded}
import com.safechat.serializer.SchemaRegistry
import org.apache.avro.Schema
import org.apache.avro.io.{BinaryEncoder, DecoderFactory, EncoderFactory}
import org.apache.avro.specific.{SpecificDatumReader, SpecificDatumWriter}
import org.apache.avro.util.ByteBufferInputStream

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

 Schema-evolution-is-not-that-complex:
  https://medium.com/data-rocks/schema-evolution-is-not-that-complex-b7cf7eb567ac

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

  def writeToBuffer[T](ev: T, buf: ByteBuffer, schema: Schema): Unit = {
    val bOut = new org.apache.avro.util.ByteBufferOutputStream()
    bOut.write(buf)
    val writer = new SpecificDatumWriter[T](schema)
    Using.resource(bOut /*new ByteBufferOutputStream(buf)*/ /*new ByteArrayOutputStream()*/ ) { out ⇒
      Using.resource(EncoderFactory.get.binaryEncoder(out, null)) { enc ⇒
        writer.write(ev, enc)
      }
    }
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
    streamRefSerializer: StreamRefSerializer,
    activeSchemaHash: String,
    schemaMap: Map[String, Schema]
  ): Array[Byte] = {
    val schema = schemaMap(activeSchemaHash)
    o match {
      case state: com.safechat.actors.ChatRoomState ⇒
        Using.resource(new ByteArrayOutputStream()) { out ⇒
          Using.resource(EncoderFactory.get.binaryEncoder(out, null)) { enc ⇒
            val users = new java.util.HashMap[CharSequence, CharSequence]()
            state.regUsers.foreach { case (login, pubKey) ⇒
              users.put(login, pubKey)
            }
            val history = new util.ArrayList[CharSequence]()
            state.recentHistory.entries.foreach(history.add(_))
            new SpecificDatumWriter[com.safechat.avro.persistent.state.ChatRoomState](schema)
              .write(new com.safechat.avro.persistent.state.ChatRoomState(users, history), enc)
          }
          out.toByteArray
        }

      case e: ChatRoomEvent ⇒
        Using.resource(new ByteArrayOutputStream()) { baos ⇒
          Using.resource(EncoderFactory.get.binaryEncoder(baos, null)) { enc ⇒
            new SpecificDatumWriter(schema).write(withEnvelope(e), enc)
          }
          baos.toByteArray
        }

      case _ ⇒ streamRefSerializer.toBinary(o)
    }
  }

  def fromArray(
    bts: Array[Byte],
    manifest: String,
    activeSchemaHash: String,
    schemaMap: Map[String, Schema]
  ): AnyRef = {
    val writerSchemaKey = manifest.split(SEP)(1)
    //println(s"fromBinary Schemas:[writer:$writerSchemaKey reader:$activeSchemaHash]")

    val writerSchema = schemaMap(writerSchemaKey)
    val readerSchema = schemaMap(activeSchemaHash)

    if (manifest.startsWith(EVENT_PREF)) {
      val envelope = readFromArray[com.safechat.avro.persistent.domain.EventEnvelope](bts, writerSchema, readerSchema)
      if (envelope.getPayload.isInstanceOf[com.safechat.avro.persistent.domain.UserJoined]) {
        val event = envelope.getPayload.asInstanceOf[com.safechat.avro.persistent.domain.UserJoined]
        UserJoined(event.getLogin.toString, event.getPubKey.toString)
      } else if (envelope.getPayload.isInstanceOf[com.safechat.avro.persistent.domain.UserTextAdded]) {
        val event = envelope.getPayload.asInstanceOf[com.safechat.avro.persistent.domain.UserTextAdded]
        UserTextAdded(
          event.getUser.toString,
          event.getReceiver.toString,
          event.getText.toString,
          envelope.getWhen,
          envelope.getTz.toString
        )
      } else if (envelope.getPayload.isInstanceOf[com.safechat.avro.persistent.domain.UserDisconnected]) {
        val event = envelope.getPayload.asInstanceOf[com.safechat.avro.persistent.domain.UserDisconnected]
        UserDisconnected(event.getLogin.toString)
      } else
        notSerializable(
          s"Deserialization for event $manifest not supported. Check fromBinary method in ${this.getClass.getName} class."
        )

    } else if (manifest.startsWith(STATE_PREF)) {
      val state    = readFromArray[com.safechat.avro.persistent.state.ChatRoomState](bts, writerSchema, readerSchema)
      var userKeys = Map.empty[String, String]
      state.getRegisteredUsers.forEach((login, pubKey) ⇒ userKeys = userKeys + (login.toString → pubKey.toString))

      val s = com.safechat.actors.ChatRoomState(regUsers = userKeys)
      state.getRecentHistory.forEach(e ⇒ s.recentHistory.:+(e.toString))
      s
    } else {
      //notSerializable(s"Deserialization for $manifest not supported. Check fromBinary method in ${this.getClass.getName} class.")

      ???
    }
  }

  private def withEnvelope(e: ChatRoomEvent) =
    e match {
      case e: UserJoined ⇒
        new com.safechat.avro.persistent.domain.EventEnvelope(
          UUID.randomUUID.toString,
          System.currentTimeMillis,
          TimeZone.getDefault.getID,
          com.safechat.avro.persistent.domain.UserJoined.newBuilder.setLogin(e.originator).setPubKey(e.pubKey).build()
        )
      case UserTextAdded(originator, receiver, content, when, tz) ⇒
        new com.safechat.avro.persistent.domain.EventEnvelope(
          UUID.randomUUID.toString,
          when,
          tz,
          new com.safechat.avro.persistent.domain.UserTextAdded(originator, receiver, content)
        )
      case e: UserDisconnected ⇒
        new com.safechat.avro.persistent.domain.EventEnvelope(
          UUID.randomUUID.toString,
          System.currentTimeMillis,
          TimeZone.getDefault.getID,
          com.safechat.avro.persistent.domain.UserDisconnected.newBuilder.setLogin(e.originator).build
        )
    }

  /** Possible inputs:
    *   com.safechat.actors.ChatRoomEvent
    *   com.safechat.actors.ChatRoomState
    *
    * Serializes the given object into the given `ByteBuffer`
    */
  def toBuffer(
    o: AnyRef,
    directByteBuffer: ByteBuffer,
    activeSchemaHash: String,
    schemaMap: Map[String, Schema]
  ): Unit = {
    val schema = schemaMap(activeSchemaHash)
    o match {
      case state: com.safechat.actors.ChatRoomState ⇒
        val out = new org.apache.avro.util.ByteBufferOutputStream()
        out.write(directByteBuffer)
        Using.resource(out /*new ByteBufferOutputStream(directByteBuffer)*/ /*new ByteArrayOutputStream()*/ ) { out ⇒
          Using.resource(EncoderFactory.get.binaryEncoder(out, null)) { enc ⇒
            val users = new java.util.HashMap[CharSequence, CharSequence]()
            state.regUsers.foreach { case (login, pubKey) ⇒
              users.put(login, pubKey)
            }
            val history = new util.ArrayList[CharSequence]()
            state.recentHistory.entries.foreach(history.add(_))
            new SpecificDatumWriter[com.safechat.avro.persistent.state.ChatRoomState](schema)
              .write(new com.safechat.avro.persistent.state.ChatRoomState(users, history), enc)
          }
        }

      case e: ChatRoomEvent ⇒
        writeToBuffer[com.safechat.avro.persistent.domain.EventEnvelope](withEnvelope(e), directByteBuffer, schema)
    }
  }

  def fromBuffer(
    buf: ByteBuffer,
    manifest: String,
    activeSchemaHash: String,
    schemaMap: Map[String, Schema]
  ): AnyRef = {
    val writerSchemaKey = manifest.split(SEP)(1)
    val writerSchema    = schemaMap(writerSchemaKey)
    val readerSchema    = schemaMap(activeSchemaHash)

    if (manifest.startsWith(EVENT_PREF)) {
      val envelope =
        readFromBuffer[com.safechat.avro.persistent.domain.EventEnvelope](buf, writerSchema, readerSchema)
      if (envelope.getPayload.isInstanceOf[com.safechat.avro.persistent.domain.UserJoined]) {
        val event = envelope.getPayload.asInstanceOf[com.safechat.avro.persistent.domain.UserJoined]
        UserJoined(event.getLogin.toString, event.getPubKey.toString)
      } else if (envelope.getPayload.isInstanceOf[com.safechat.avro.persistent.domain.UserTextAdded]) {
        val event = envelope.getPayload.asInstanceOf[com.safechat.avro.persistent.domain.UserTextAdded]
        UserTextAdded(
          event.getUser.toString,
          event.getReceiver.toString,
          event.getText.toString,
          envelope.getWhen,
          envelope.getTz.toString
        )
      } else if (envelope.getPayload.isInstanceOf[com.safechat.avro.persistent.domain.UserDisconnected]) {
        val event = envelope.getPayload.asInstanceOf[com.safechat.avro.persistent.domain.UserDisconnected]
        UserDisconnected(event.getLogin.toString)
      } else
        notSerializable(
          s"Deserialization for event $manifest not supported. Check fromBinary method in ${this.getClass.getName} class."
        )

    } else if (manifest.startsWith(STATE_PREF)) {
      val state =
        readFromBuffer[com.safechat.avro.persistent.state.ChatRoomState](buf, writerSchema, readerSchema)
      var userKeys = Map.empty[String, String]
      state.getRegisteredUsers.forEach((login, pubKey) ⇒ userKeys = userKeys + (login.toString → pubKey.toString))

      val s = com.safechat.actors.ChatRoomState(regUsers = userKeys)
      state.getRecentHistory.forEach(e ⇒ s.recentHistory.:+(e.toString))
      s
    } else {
      //notSerializable(s"Deserialization for $manifest not supported. Check fromBinary method in ${this.getClass.getName} class.")
      1.asInstanceOf[AnyRef]
    }
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
final class JournalEventsSerializer(system: ExtendedActorSystem)
    extends /*StreamRefSerializer(system)*/ SerializerWithStringManifest
    with ByteBufferSerializer {

  lazy val streamRefSerializer =
    SerializationExtension(system)
      .serializerByIdentity(30)
      .asInstanceOf[akka.stream.serialization.StreamRefSerializer] //SerializerWithStringManifest

  override val identifier = 99999

  private val (activeSchemaHash, schemaMap) = SchemaRegistry()

  //Mapping from domain event to avro classes that are being used for persistence
  private val mapping =
    SchemaRegistry.journalEvents(system.settings.config.getConfig("akka.actor.serialization-bindings"))

  override def manifest(obj: AnyRef): String =
    obj match {
      case _: ChatRoomEvent ⇒
        JournalEventsSerializer.manifest(obj, activeSchemaHash, mapping)
      case _: com.safechat.actors.ChatRoomState ⇒
        JournalEventsSerializer.manifest(obj, activeSchemaHash, mapping)
      case _ ⇒ streamRefSerializer.manifest(obj)
    }

  override def toBinary(obj: AnyRef): Array[Byte] =
    JournalEventsSerializer.toArray(obj, streamRefSerializer, activeSchemaHash, schemaMap)

  override def toBinary(obj: AnyRef, directByteBuffer: ByteBuffer): Unit = {
    val schema = schemaMap(activeSchemaHash)
    obj match {
      case e: ChatRoomEvent ⇒
        system.log.warning(
          "toBinary:{} IsDirect:{}:{}",
          e.getClass.getSimpleName,
          directByteBuffer.isDirect,
          streamRefSerializer.identifier
        )

        //val outputStream = new org.apache.avro.util.ByteBufferOutputStream()
        //outputStream.write(directByteBuffer)

        Using.resource( /*outputStream*/ new ByteBufferOutputStream(directByteBuffer)) { out ⇒
          Using.resource(EncoderFactory.get.directBinaryEncoder(out, null)) { enc ⇒ //binaryEncoder
            new SpecificDatumWriter(schema).write(withEnvelope(e), enc)
          }(BinaryEncoderIsReleasable)
        }

      case state: com.safechat.actors.ChatRoomState ⇒
        system.log.warning("toBinary:{} IsDirect:{}", state.getClass.getSimpleName, directByteBuffer.isDirect)

        //val outputStream = new org.apache.avro.util.ByteBufferOutputStream()
        //outputStream.write(directByteBuffer)

        Using.resource( /*outputStream*/ new ByteBufferOutputStream(directByteBuffer)) { out ⇒
          Using.resource(EncoderFactory.get.directBinaryEncoder(out, null)) { enc ⇒
            val users = new java.util.HashMap[CharSequence, CharSequence]()
            state.regUsers.foreach { case (login, pubKey) ⇒ users.put(login, pubKey) }
            val history = new util.ArrayList[CharSequence]()
            state.recentHistory.entries.foreach(history.add(_))
            new SpecificDatumWriter[com.safechat.avro.persistent.state.ChatRoomState](schema)
              .write(new com.safechat.avro.persistent.state.ChatRoomState(users, history), enc)
          }
        }

      case _ ⇒
        //serialization.findSerializerFor(obj).toBinary(obj)
        //super.toBinary(obj)
        //val outputStream = new org.apache.avro.util.ByteBufferOutputStream()
        //outputStream.write(directByteBuffer)
        Using.resource(new ByteBufferOutputStream(directByteBuffer)) { out ⇒
          val bytes = streamRefSerializer.toBinary(obj)
          system.log.error("toBinary-other:{} : {} : {}", obj, bytes.size, streamRefSerializer.identifier)
          //directByteBuffer.put(bytes)
          out.write(bytes)
        }
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinary(ByteBuffer.wrap(bytes), manifest)
  //JournalEventsSerializer.fromArray(bytes, manifest, activeSchemaHash, schemaMap)

  override def fromBinary(directByteBuffer: ByteBuffer, manifest: String): AnyRef = {
    val r = JournalEventsSerializer.fromBuffer(directByteBuffer, manifest, activeSchemaHash, schemaMap)

    if (r.isInstanceOf[Int] && r.asInstanceOf[Int] == 1) {
      system.log.warning(
        "fromBinary-other:{} size:{} IsDirect:{}:{}",
        manifest,
        directByteBuffer.remaining,
        directByteBuffer.isDirect,
        streamRefSerializer.identifier
      )

      //allocate extra array
      val bytes = new Array[Byte](directByteBuffer.remaining)
      directByteBuffer.get(bytes)
      streamRefSerializer.fromBinary(bytes, manifest)
      //serialization.deserializeByteBuffer(directByteBuffer, ???, manifest)
      //super.fromBinary(bytes, manifest)
    } else {
      system.log.warning(
        "fromBinary:{} size:{} IsDirect:{}:{}",
        manifest,
        directByteBuffer.remaining,
        directByteBuffer.isDirect,
        streamRefSerializer.identifier
      )

      r
    }
  }
}

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
