// Copyright (c) 2018-19 by Haghard. All rights reserved.

package com.safechat
package crypto

import java.security.interfaces.RSAKey

import scala.util.Try

abstract class Base64EncodedBytes {

  def bytes: Array[Byte]

  def size: Int = bytes.size

  final override def toString: String =
    crypto.base64Encode(bytes)

  override def equals(that: Any): Boolean = that match {
    case bs: Base64EncodedBytes ⇒ bs.bytes.sameElements(bytes)
    case _                      ⇒ false
  }

  override def hashCode(): Int =
    java.util.Arrays.hashCode(bytes)
}

class Handle protected (val bytes: Array[Byte]) extends Base64EncodedBytes

object Handle {

  def apply(bs: Array[Byte]): Try[Handle] = Try(new Handle(bs))

  def fromEncoded(s: String): Option[Handle] =
    crypto.base64Decode(s).map(new Handle(_))

  def ofModulus(n: BigInt): Handle =
    new Handle(crypto.sha256(UnsignedBigInt.toBigEndianBytes(n)))

  def ofKey(k: RSAKey): Handle = ofModulus(k.getModulus)
}
