// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat.serializer

import org.apache.avro.Schema
import java.io.{File, FileInputStream}

import com.typesafe.config.Config
import org.apache.commons.codec.digest.DigestUtils

object AvroSchemaRegistry {

  //private val activeSchema: File = new File("./src/main/avro/ChatRoomEventsV1.avsc")
  private val activeSchema: File = new File("avro/ChatRoomEventsV1.avsc")

  private val schemaHistory: List[File] = Nil
  //List(new File("./src/main/avro/prev/ChatRoomEventsV1.avsc")) //, "/avro/ChatRoomEventsV1.avsc")

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

  def validateSerializationBindings(cfg: Config): Unit = {
    //import eu.timepit.refined._
    //import eu.timepit.refined.string.MatchesRegex

    val classes2Persist = getSchemaFromUrl(activeSchema)
        .getTypes()
        .toArray(Array.ofDim[Schema](2))
        .map(sch ⇒ sch.getNamespace + "." + sch.getName)
        .toSet + classOf[com.safechat.actors.ChatRoomState].getName - classOf[com.safechat.domain.ChatState].getName

    //type ClassesToPersist = MatchesRegex[W.`"com.safechat.domain.MsgEnvelope|com.safechat.actors.ChatRoomState"`.T]

    var bindings: Set[String] = Set.empty

    val iter = cfg.getConfig("akka.actor.serialization-bindings").entrySet().iterator()
    while (iter.hasNext) {
      val kv = iter.next()
      if (kv.getValue.render() == "\"journalSerializer\"")
        bindings = bindings + kv.getKey.replace("\"", "")
      /*refineV[ClassesToPersist](kv.getKey.replace("\"", "")).getOrElse(
          throw new Exception(s"Unexpected serialization bindings ${kv.getKey}")
        )*/
    }

    if (classes2Persist != bindings)
      throw new Exception(
        s"Serialization bindings error. Should be:[${classes2Persist.mkString(",")}] Actual:[${bindings.mkString(",")}]"
      )

  }
}
