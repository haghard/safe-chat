package akka.io

import akka.actor.ExtendedActorSystem
import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.cluster.ddata.protobuf.SerializationSupport
import akka.io.CommandsSerializer._
import akka.remote.WireFormats.ActorRefData
import akka.remote.serialization.ProtobufSerializer
import akka.serialization.ByteBufferSerializer
import akka.serialization.SerializerWithStringManifest
import com.safechat.actors.Command
import com.safechat.actors.Reply
import com.safechat.avro.command.CmdEnvelope
import org.apache.avro.Schema
import org.apache.avro.io.BinaryEncoder
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import org.apache.avro.specific.SpecificDatumReader
import org.apache.avro.specific.SpecificDatumWriter

import java.io.NotSerializableException
import java.nio.ByteBuffer
import scala.util.Using
import scala.util.Using.Releasable

/** https://doc.akka.io/docs/akka/current/remoting-artery.html#bytebuffer-based-serialization
  *
  * Artery introduced a new serialization mechanism.
  * This implementation takes advantage of new Artery serialization mechanism
  * which allows the ByteBufferSerializer to directly write into and read from a shared java.nio.ByteBuffer
  * instead of being forced to allocate and return an Array[Byte] for each serialized message.
  *
  * https://blog.softwaremill.com/akka-references-serialization-with-protobufs-up-to-akka-2-5-87890c4b6cb0
  */
object CommandsSerializer {

  def notSerializable(msg: String) = throw new NotSerializableException(msg)

  def illegalArgument(msg: String) = throw new IllegalArgumentException(msg)

  implicit object BinaryEncoderIsReleasable extends Releasable[BinaryEncoder] {
    def release(resource: BinaryEncoder): Unit =
      resource.flush
  }

  def withEnvelope(cmd: com.safechat.actors.Command[_]) = {
    val replyTo = ProtobufSerializer.serializeActorRef(cmd.replyTo.toClassic).toByteString.asReadOnlyByteBuffer()
    cmd match {
      case Command.JoinUser(chatId, user, pubKey, _) ⇒
        new com.safechat.avro.command.CmdEnvelope(
          chatId,
          replyTo,
          new com.safechat.avro.command.JoinUser(user, pubKey)
        )
      case Command.PostText(chatId, sender, receiver, content, _) ⇒
        new com.safechat.avro.command.CmdEnvelope(
          chatId,
          replyTo,
          new com.safechat.avro.command.PostText(sender, receiver, content)
        )
      case Command.Leave(chatId, user, _) ⇒
        new com.safechat.avro.command.CmdEnvelope(
          chatId,
          replyTo,
          new com.safechat.avro.command.Leave(user)
        )
      case _: Command.StopChatRoom ⇒
        new com.safechat.avro.command.CmdEnvelope(
          Command.handOffRoom.chatId,
          null,
          new com.safechat.avro.command.StopChatRoom(Command.handOffRoom.chatId, Command.handOffRoom.user)
        )
    }
  }

  def read[T](buf: ByteBuffer, writerSchema: Schema, readerSchema: Schema): T = {
    val reader  = new SpecificDatumReader[T](writerSchema, readerSchema)
    val decoder = DecoderFactory.get.directBinaryDecoder(new ByteBufferInputStream(buf), null)
    reader.read(null.asInstanceOf[T], decoder)
  }

  def toDomainCmd(env: CmdEnvelope, system: ExtendedActorSystem) =
    env.getPayload.asInstanceOf[org.apache.avro.specific.SpecificRecordBase] match {
      case c: com.safechat.avro.command.JoinUser ⇒
        Command.JoinUser(
          env.getChatId.toString,
          c.getUser.toString,
          c.getPubKey.toString,
          ProtobufSerializer
            .deserializeActorRef(system, ActorRefData.parseFrom(env.getReplyTo))
            .toTyped[Reply.JoinReply]
        )
      case c: com.safechat.avro.command.PostText ⇒
        Command.PostText(
          env.getChatId.toString,
          c.getSender.toString,
          c.getReceiver.toString,
          c.getContent.toString,
          ProtobufSerializer
            .deserializeActorRef(system, ActorRefData.parseFrom(env.getReplyTo))
            .toTyped[Reply.TextPostedReply]
        )
      case c: com.safechat.avro.command.Leave ⇒
        Command.Leave(
          env.getChatId.toString,
          c.getUser.toString,
          ProtobufSerializer
            .deserializeActorRef(system, ActorRefData.parseFrom(env.getReplyTo))
            .toTyped[Reply.LeaveReply]
        )
      case _: com.safechat.avro.command.StopChatRoom ⇒
        Command.handOffRoom
    }

  def toBytes(cmd: com.safechat.actors.Command[_], schema: Schema): Array[Byte] =
    Using.resource(new java.io.ByteArrayOutputStream()) { baos ⇒
      Using.resource(EncoderFactory.get.directBinaryEncoder(baos, null)) { enc ⇒
        new SpecificDatumWriter(schema).write(withEnvelope(cmd), enc)
      }
      baos.toByteArray
    }

  /*def toBytes1(cmd: com.safechat.actors.Command[_], schema: Schema): Array[Byte] = {
    val bb = ByteBuffer.allocate(1024)
    Using.resource(new ByteBufferOutputStream(bb)) { baos ⇒
      Using.resource(EncoderFactory.get.directBinaryEncoder(baos, null)) { enc ⇒
        new SpecificDatumWriter(schema).write(withEnvelope(cmd), enc)
      }
      bb.array()
    }
  }*/

}

final class CommandsSerializer(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with ByteBufferSerializer
    with SerializationSupport {

  override val identifier = 99998

  private val SEP                           = ":"
  private val (activeSchemaHash, schemaMap) = com.safechat.serializer.SchemaRegistry()

  override def manifest(o: AnyRef): String =
    s"$activeSchemaHash$SEP${o.getClass.getName}"

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case cmd: com.safechat.actors.Command[_] ⇒
        //system.log.warning(s"*** toBinary ${o.getClass.getName}")
        toBytes(cmd, schemaMap(activeSchemaHash))
      case _ ⇒
        illegalArgument(
          s"Serialization for ${o.getClass.getName} isn't supported. Check toBinary method in ${this.getClass.getName} class."
        )
    }

  override def toBinary(o: AnyRef, directBuf: ByteBuffer): Unit =
    o match {
      case cmd: com.safechat.actors.Command[_] ⇒
        //system.log.warning(s"*** toBinary0 ${o.getClass.getName}")
        Using.resource(new akka.io.ByteBufferOutputStream(directBuf)) { baos ⇒
          Using.resource(EncoderFactory.get.directBinaryEncoder(baos, null)) { enc ⇒
            new SpecificDatumWriter(schemaMap(activeSchemaHash)).write(withEnvelope(cmd), enc)
          }
        }

      case _ ⇒
        illegalArgument(
          s"Serialization for ${o.getClass.getName} isn't supported. Check toBinary(buf) method in ${this.getClass.getName} class."
        )
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinary(ByteBuffer.wrap(bytes), manifest)

  override def fromBinary(buf: ByteBuffer, manifest: String): AnyRef = {
    val seqments = manifest.split(SEP)
    if (seqments.size != 2)
      illegalArgument(
        s"Deserialization of $manifest failed. Wrong message format in ${this.getClass.getName} fromBinary(buf)."
      )
    else {
      val writerSchemaKey = manifest.split(SEP)(0)
      val cmdEnvelope     = read[CmdEnvelope](buf, schemaMap(writerSchemaKey), schemaMap(activeSchemaHash))
      toDomainCmd(cmdEnvelope, system)
    }
  }
}
