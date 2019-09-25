package com.safechat

import java.util.Base64
import java.security.MessageDigest

import scala.util.Try

package object crypto {

  def base64Encode(bs: Array[Byte]): String =
    new String(Base64.getUrlEncoder.withoutPadding.encode(bs))

  def base64Decode(s: String): Option[Array[Byte]] =
    Try(Base64.getUrlDecoder.decode(s)).toOption

  def sha256(bts: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(bts)

  object UnsignedBigInt {
    def ofBigEndianBytes(bs: Array[Byte]): Option[BigInt] =
      if (bs.isEmpty) None else Some(BigInt(0.toByte +: bs))

    def toBigEndianBytes(bi: BigInt): Array[Byte] = {
      val bs = bi.toByteArray
      if (bs.length > 1 && bs.head == 0.toByte) bs.tail else bs
    }
  }
}
