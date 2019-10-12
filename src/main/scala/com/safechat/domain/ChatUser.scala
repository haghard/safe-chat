// Copyright (c) 2019 Vadim Bondarev. All rights reserved.

package com.safechat
package domain

import java.math.BigInteger
import java.security.{KeyPairGenerator, SecureRandom}
import java.security.interfaces.{RSAPrivateCrtKey, RSAPublicKey}
import java.security.spec.RSAKeyGenParameterSpec
import com.safechat.crypto.Handle
import spray.json._

case class ChatUserSnapshot(e: BigInt, n: BigInt, d: BigInt, p: BigInt, q: BigInt, dp: BigInt, dq: BigInt, qi: BigInt)

object ChatUserSnapshot extends DefaultJsonProtocol {

  implicit object UserSnapshotJsonFormat extends JsonFormat[ChatUserSnapshot] {
    override def write(c: ChatUserSnapshot) = JsObject(
      "e"  → JsNumber(c.e.toString),
      "n"  → JsNumber(c.n.toString),
      "d"  → JsNumber(c.d.toString),
      "p"  → JsNumber(c.p.toString),
      "q"  → JsNumber(c.q.toString),
      "dp" → JsNumber(c.dp.toString),
      "dq" → JsNumber(c.dq.toString),
      "qi" → JsNumber(c.qi.toString)
    )

    override def read(json: JsValue): ChatUserSnapshot =
      json.asJsObject.getFields("e", "n", "d", "p", "q", "dp", "dq", "qi") match {
        case Seq(
            JsNumber(e),
            JsNumber(n),
            JsNumber(d),
            JsNumber(p),
            JsNumber(q),
            JsNumber(dp),
            JsNumber(dq),
            JsNumber(qi)
            ) ⇒
          ChatUserSnapshot(
            e.toBigInt,
            n.toBigInt,
            d.toBigInt,
            p.toBigInt,
            q.toBigInt,
            dp.toBigInt,
            dq.toBigInt,
            qi.toBigInt
          )
      }
  }
}

case class ChatUser(val pub: RSAPublicKey, val priv: RSAPrivateCrtKey) {
  val handle = Handle.ofKey(pub)

  //public key
  val asX509 = crypto.base64Encode(pub.getEncoded)

  //private key
  val asPKCS8: Array[Byte] = priv.getEncoded

  override def toString =
    ChatUserSnapshot(
      pub.getPublicExponent,
      pub.getModulus,
      priv.getPrivateExponent,
      priv.getPrimeP,
      priv.getPrimeQ,
      priv.getPrimeExponentP,
      priv.getPrimeExponentQ,
      priv.getCrtCoefficient
    ).toJson.prettyPrint
}

object ChatUser {
  val msgClaim                            = "text"
  private val ALG                         = "RSA"
  private val PublicExponentUsedByArweave = new BigInteger("65537")

  def generate(keySize: Int = 2048): ChatUser = {
    val kpg = KeyPairGenerator.getInstance(ALG)
    kpg.initialize(new RSAKeyGenParameterSpec(keySize, PublicExponentUsedByArweave), new SecureRandom())
    val kp = kpg.generateKeyPair
    ChatUser(kp.getPublic.asInstanceOf[RSAPublicKey], kp.getPrivate.asInstanceOf[RSAPrivateCrtKey])
  }
}
