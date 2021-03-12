// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat.serializer

import com.typesafe.config.Config
import org.apache.avro.Schema
import org.apache.commons.codec.digest.DigestUtils

import java.io.File
import java.io.FileInputStream
import scala.jdk.CollectionConverters._

//https://medium.com/data-rocks/schema-evolution-is-not-that-complex-b7cf7eb567ac
object SchemaRegistry {

  //private val activeSchema: File = new File("./src/main/avro/ChatRoomV1.avsc")
  private val activeSchema: File = new File("avro/ChatRoomV1.avsc")

  private val schemaHistory: List[File] = Nil
  //List(new File("./src/main/avro/prev/ChatRoomV1.avsc")) //, "/avro/ChatRoomV1.avsc")

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

  private val serializerName = "journalSerializer"

  def journalEvents(cfg: Config, fieldName: String = "payload"): Map[String, String] = {
    val typesFromSchema = getSchemaFromUrl(activeSchema).getTypes()
    val topLevelRecords = typesFromSchema.asScala

    var avroSchemaMapping: Map[String, String] = Map.empty
    topLevelRecords
      .filter(_.getName.contains("EventEnvelope"))
      .map { eventSchema ⇒
        val it = eventSchema.getField(fieldName).schema().getTypes.iterator()
        while (it.hasNext) {
          val sch = it.next()
          avroSchemaMapping =
            avroSchemaMapping + (sch.getDoc.replaceAll("Refers to", "").trim → s"${sch.getNamespace}.${sch.getName}")
        }
      }

    var domainEvents: Set[String] = Set.empty
    val iter                      = cfg.entrySet().iterator()
    while (iter.hasNext) {
      val kv = iter.next()
      if (kv.getValue.render().contains(serializerName)) {
        val k = kv.getKey.replace("\"", "")
        if (k.contains("User"))
          domainEvents = domainEvents + k
      }
    }

    domainEvents.map(domainEvent ⇒ (domainEvent, avroSchemaMapping(domainEvent))).toMap
  }

  /*
  def validateSerializationBindings(cfg: Config): Unit = {

    //import eu.timepit.refined._
    //import eu.timepit.refined.string.MatchesRegex
    //type ClassesToPersist = MatchesRegex[W.`"com.safechat.domain.MsgEnvelope|com.safechat.actors.ChatRoomState"`.T]
    //refineV[ClassesToPersist](kv.getKey.replace("\"", "")).getOrElse(throw new Exception(s"Unexpected serialization bindings ${kv.getKey}"))

    val typesFromSchema = getSchemaFromUrl(activeSchema).getTypes()
    val classes2Persist = typesFromSchema
        .toArray(Array.ofDim[Schema](typesFromSchema.size()))
        .map(sch ⇒ sch.getNamespace + "." + sch.getName)
        //I add those additions|removals because we do not have one to one mapping between what we persist and bindings in the config
        .toSet +
      classOf[com.safechat.actors.ChatRoomState].getName -         //this is what we pass in Effect.persist()
      classOf[com.safechat.persistent.state.ChatRoomState].getName //this is what goes into the journal

    var bindingsFromConfig: Set[String] = Set.empty
    val iter                            = cfg.getConfig(sectionName).entrySet().iterator()
    while (iter.hasNext) {
      val kv = iter.next()
      if (kv.getValue.render() == serializerName)
        bindingsFromConfig = bindingsFromConfig + kv.getKey.replace("\"", "")
    }

    if (classes2Persist != bindingsFromConfig)
      throw new Exception(
        s"Serialization bindings error. Should be:[${classes2Persist.mkString(",")}] Actual:[${bindingsFromConfig.mkString(",")}]"
      )

  }*/
}
