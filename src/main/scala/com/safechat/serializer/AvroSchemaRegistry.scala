// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat.serializer

import org.apache.avro.Schema
import java.io.{File, FileInputStream}

import org.apache.commons.codec.digest.DigestUtils

object AvroSchemaRegistry {

  //private val activeSchema: File = new File("./src/main/avro/ChatRoomEventsV1.avsc")
  private val activeSchema: File = new File("avro/ChatRoomEventsV1.avsc")

  private val schemaHistory: List[File] = Nil
  //List(new File("./src/main/avroHistory/ChatRoomEventsV1.avsc")) //, "/avro/ChatRoomEventsV1.avsc")

  private val activeSchemaHash: String = getMD5FromUrl(activeSchema)

  private val schemaMap: Map[String, Schema] = Map(
      activeSchemaHash → getSchemaFromUrl(activeSchema)
    ) ++ schemaHistory.map { schemaVersion ⇒
      //val oldSchema = new FileInputStream(schemaVersion)
      //getClass.getResource(schemaVersion)
      (getMD5FromUrl(schemaVersion), getSchemaFromUrl(schemaVersion))
    }

  def getMD5FromUrl(in: File): String = DigestUtils.md5Hex(new FileInputStream(in))

  def getSchemaFromUrl(in: File): Schema =
    new Schema.Parser().parse(scala.io.Source.fromFile(in).getLines.mkString)

  // return the fingerprint for the current schema, and the map for all schemas
  def apply(): (String, Map[String, Schema]) =
    (activeSchemaHash, schemaMap)
}