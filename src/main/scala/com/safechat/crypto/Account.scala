// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat
package crypto

import spray.json._

import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec._
import scala.io.Source
import scala.util.Try

case class AccountSnapshot(e: BigInt, n: BigInt, d: BigInt, p: BigInt, q: BigInt, dp: BigInt, dq: BigInt, qi: BigInt)

object AccountSnapshot extends DefaultJsonProtocol {

  implicit object AccountBackupJsonFormat extends JsonFormat[AccountSnapshot] {
    override def write(c: AccountSnapshot) =
      JsObject(
        "e"  -> JsNumber(c.e.toString),
        "n"  -> JsNumber(c.n.toString),
        "d"  -> JsNumber(c.d.toString),
        "p"  -> JsNumber(c.p.toString),
        "q"  -> JsNumber(c.q.toString),
        "dp" -> JsNumber(c.dp.toString),
        "dq" -> JsNumber(c.dq.toString),
        "qi" -> JsNumber(c.qi.toString)
      )

    override def read(json: JsValue): AccountSnapshot =
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
            ) =>
          AccountSnapshot(
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

case class Account(private val pub: RSAPublicKey, priv: RSAPrivateCrtKey) {
  val handle = Handle.ofKey(pub)

  // public key
  val asX509 = crypto.base64Encode(pub.getEncoded)

  // private key
  val asPKCS8: Array[Byte] = priv.getEncoded

  override def toString =
    AccountSnapshot(
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

object Account {
  val ALG                         = "RSA"
  val PublicExponentUsedByArweave = new BigInteger("65537")

  val settings = JsonParserSettings.default.withMaxNumberCharacters(1500)

  def generate(sr: SecureRandom = new SecureRandom(), keySize: Int = 2048): Account = {
    val kpg = KeyPairGenerator.getInstance(ALG)
    kpg.initialize(new RSAKeyGenParameterSpec(keySize, PublicExponentUsedByArweave), sr)
    val kp = kpg.generateKeyPair()
    Account(kp.getPublic.asInstanceOf[RSAPublicKey], kp.getPrivate.asInstanceOf[RSAPrivateCrtKey])
  }

  private def parse(a: AccountSnapshot): Try[Account] =
    Try {
      val kf = KeyFactory.getInstance(ALG)
      Account(
        kf.generatePublic(new RSAPublicKeySpec(a.n.bigInteger, a.e.bigInteger)).asInstanceOf[RSAPublicKey],
        kf.generatePrivate(
          new RSAPrivateCrtKeySpec(
            a.n.bigInteger,
            a.e.bigInteger,
            a.d.bigInteger,
            a.p.bigInteger,
            a.q.bigInteger,
            a.dp.bigInteger,
            a.dq.bigInteger,
            a.qi.bigInteger
          )
        ).asInstanceOf[RSAPrivateCrtKey]
      )
    }

  private def load(s: Source): Option[Account] =
    for {
      str ← Try(s.mkString).toOption
      a   ← parse(str.parseJson(settings).convertTo[AccountSnapshot]).toOption
    } yield a

  def recoverFromBackup(filename: String): Option[Account] =
    for {
      s ← Try(Source.fromFile(filename)).toOption
      w ← load(s)
    } yield w

  def backup(a: Account, filename: String): Try[Path] =
    Try(Files.write(Paths.get(filename), a.toString.getBytes(UTF_8)))

  def recoverFromPrivKey(bs: Array[Byte]): Try[Account] =
    Try {
      val kf   = KeyFactory.getInstance(ALG)
      val priv = kf.generatePrivate(new PKCS8EncodedKeySpec(bs)).asInstanceOf[RSAPrivateCrtKey]
      val pub = kf
        .generatePublic(new RSAPublicKeySpec(priv.getModulus, priv.getPublicExponent))
        .asInstanceOf[RSAPublicKey]
      Account(pub, priv)
    }

  def recoverFromPubKey(bs: String): Option[RSAPublicKey] =
    crypto
      .base64Decode(bs)
      .map(bts => KeyFactory.getInstance(ALG).generatePublic(new X509EncodedKeySpec(bts)).asInstanceOf[RSAPublicKey])
}
