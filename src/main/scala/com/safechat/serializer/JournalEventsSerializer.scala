// Copyright (c) 2019 Vadim Bondarev. All rights reserved.

package com.safechat
package serializer

import java.io.ByteArrayOutputStream
import java.util

import com.safechat.domain._
import akka.serialization.SerializerWithStringManifest
import com.safechat.actors.FullChatState
import org.apache.avro.io.{BinaryEncoder, DecoderFactory, EncoderFactory}
import org.apache.avro.specific.{SpecificDatumReader, SpecificDatumWriter}

import scala.util.Using
import scala.util.Using.Releasable
import JournalEventsSerializer._
import org.apache.avro.Schema

import scala.reflect.ClassTag

/*

Avro will provide you full compatibility support.
Backward compatibility is necessary for reading the old version of events.
Forward compatibility is required for rolling updates — at the same time old and new version of events can be exchanged
between micro service instances.

1.  Backward compatible change - write with V1 and read with V2
2.  Forward compatible change -  write with V2 and read with V1
3.  Full  if your change is Backward and Forward compatible
4.  Breaking is non of those

Advice when writing Avro schema
1) Add field with defaults
2) Removing only fields which have defaults

If you target full compatibility follows these rules:
  Removing fields with defaults is fully compatible change
  Adding fields with defaults is fully compatible change

  Enum can't evolve over time.

  When evolving schema, ALWAYS give defaults.

  When evolving schema, NEVER
  Rename fields
  Remove required fields

 */

object JournalEventsSerializer {
  val SEP         = ":"
  val subEventSEP = "/"

  implicit object BinaryEncoderIsReleasable extends Releasable[BinaryEncoder] {
    def release(resource: BinaryEncoder): Unit =
      resource.flush
  }
}

final class JournalEventsSerializer extends SerializerWithStringManifest {
  override val identifier: Int = 9999

  val (activeSchemaHash, schemaMap) = AvroSchemaRegistry()

  // Serializer always *writes* the most recent version of the schema
  override def manifest(o: AnyRef): String =
    o match {
      case uEnv: MsgEnvelope ⇒
        o.getClass.getName + subEventSEP + uEnv.getPayload.getClass.getSimpleName + SEP + activeSchemaHash
      case state ⇒
        state.getClass.getName + SEP + activeSchemaHash
    }

  override def toBinary(o: AnyRef): Array[Byte] = {
    val schema = schemaMap(activeSchemaHash)
    o match {
      case state: FullChatState ⇒
        Using.resource(new ByteArrayOutputStream()) { out ⇒
          Using.resource(EncoderFactory.get.binaryEncoder(out, null)) { enc ⇒
            val users = new java.util.HashMap[String, String]()
            state.regUsers.foreach {
              case (login, pubKey) ⇒
                users.put(login, pubKey)
            }
            val history = new util.ArrayList[String]()
            state.recentHistory.entries.foreach(history.add(_))
            new SpecificDatumWriter[ChatState](schema)
              .write(new ChatState(users, history), enc)
          }
          out.toByteArray
        }
      case uEnv: MsgEnvelope ⇒
        toByteArray[MsgEnvelope](uEnv, schema)
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val writerSchemaKey = manifest.split(SEP)(1)
    //println(s"fromBinary Schemas:[writer:$writerSchemaKey reader:$activeSchemaHash]")
    val writerSchema = schemaMap(writerSchemaKey)
    val readerSchema = schemaMap(activeSchemaHash)
    if (manifest.startsWith(classOf[MsgEnvelope].getName)) {
      deserialize[MsgEnvelope](bytes, writerSchema, readerSchema)
    } else if (manifest.startsWith(classOf[FullChatState].getName)) {
      val state = deserialize[ChatState](bytes, writerSchema, readerSchema)

      var userKeys = Map.empty[String, String]
      state.getRegisteredUsers.forEach { (login, pubKey) ⇒
        userKeys = userKeys + (login → pubKey)
      }

      val s = FullChatState(regUsers = userKeys)
      state.getRecentHistory.forEach(s.recentHistory.add(_))
      s
    } else
      throw new IllegalStateException(
        s"Deserialization for $manifest not supported. Check fromBinary method in ${this.getClass.getName} class."
      )
  }

  def deserialize[T](bts: Array[Byte], writerSchema: Schema, readerSchema: Schema): T = {
    val reader  = new SpecificDatumReader[T](writerSchema, readerSchema)
    val decoder = DecoderFactory.get.binaryDecoder(bts, null)
    reader.read(null.asInstanceOf[T], decoder)
  }

  private def toByteArray[T: ClassTag](ev: T, schema: Schema): Array[Byte] =
    Using.resource(new ByteArrayOutputStream()) { out ⇒
      Using.resource(EncoderFactory.get.binaryEncoder(out, null)) { enc ⇒
        new SpecificDatumWriter[T](schema).write(ev, enc)
      }
      out.toByteArray
    }
}
