// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

/*

package com.safechat.programs

import akka.io.JournalEventsSerializer
import com.safechat.avro.persistent.domain.EventEnvelope
import com.safechat.avro.persistent.domain.UserJoined
import org.apache.avro.Schema
import org.apache.avro.file.DataFileStream
import org.apache.avro.file.DataFileWriter
import org.apache.avro.specific.SpecificDatumReader
import org.apache.avro.specific.SpecificDatumWriter

import java.io.File
import java.io.FileInputStream
import java.util.UUID
import scala.util.Using
import scala.util.control.NonFatal

//runMain com.safechat.programs.SerializerProgram
object SerializerProgram {

  val (activeSchemaHash, schemaMap) = SchemaRegistry()

  def main(args: Array[String]): Unit =
    try {
      val uEnv = new EventEnvelope(
        UUID.randomUUID.toString,
        System.currentTimeMillis,
        "tz",
        UserJoined.newBuilder.setLogin("user").setPubKey("pubKey").build()
      )

      val schema = schemaMap(activeSchemaHash)
      val bts    = JournalEventsSerializer.toArray(uEnv, activeSchemaHash, schemaMap)
      println(uEnv + ":" + bts.size)
      val ref = JournalEventsSerializer.readFromArray(bts, schema, schema)
      println(ref)
    } catch {
      case NonFatal(ex) ⇒
        ex.printStackTrace()
    }

  def writeToFile(ev: EventEnvelope, writerSchema: Schema): Unit =
    Using.resource(new DataFileWriter[EventEnvelope](new SpecificDatumWriter[EventEnvelope](classOf[EventEnvelope]))) {
      writer ⇒
        writer.create(writerSchema, new File("fileName"))
        writer.append(ev)
        println(s"*** writeToFile: $ev")
        writer.flush
    }

  def readFromFile(in: FileInputStream, writerSchema: Schema, readerSchema: Schema): Unit =
    Using.resource(in) { input ⇒
      Using.resource(new DataFileStream(input, new SpecificDatumReader[EventEnvelope](writerSchema, readerSchema))) {
        datumReader ⇒
          //println(s"*** readFromFile: ${DigestUtils.md5Hex(writerSchema.toString)} / ${DigestUtils.md5Hex(readerSchema.toString)}")
          if (datumReader.hasNext) {
            val ev = datumReader.next
            println(s"read it back: $ev")
          }
      }
    }

}

 */
