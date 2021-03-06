package com.safechat.sb

import com.datastax.oss.driver.api.core.uuid.Uuids._

object CorruptedJournalExample {

  // 1 concurrent event
  val a = unixTimestamp(java.util.UUID.fromString("6dd5c410-7d00-11eb-a117-d906864e73ae"))

  val b0 = unixTimestamp(java.util.UUID.fromString("7861a7f0-7d00-11eb-946a-0ded85fba6d6"))
  val a0 = unixTimestamp(java.util.UUID.fromString("7c4300d0-7d00-11eb-a117-d906864e73ae"))

  val c = unixTimestamp(java.util.UUID.fromString("78637cb0-7d00-11eb-946a-0ded85fba6d6"))

  // 3 concurrent events
  val a83a = unixTimestamp(java.util.UUID.fromString("1e9923a0-7c07-11eb-968c-ed6f87b8049e"))

  val a84b = unixTimestamp(java.util.UUID.fromString("2becda10-7c07-11eb-b141-7f65a26e8259"))
  val a84a = unixTimestamp(java.util.UUID.fromString("381f6910-7c07-11eb-968c-ed6f87b8049e"))

  val a85b = unixTimestamp(java.util.UUID.fromString("319efb50-7c07-11eb-b141-7f65a26e8259"))
  val a85a = unixTimestamp(java.util.UUID.fromString("38229d60-7c07-11eb-968c-ed6f87b8049e"))

  val a86b = unixTimestamp(java.util.UUID.fromString("32190d50-7c07-11eb-b141-7f65a26e8259"))
  val a86a = unixTimestamp(java.util.UUID.fromString("38244b10-7c07-11eb-968c-ed6f87b8049e"))

  val a87 = unixTimestamp(java.util.UUID.fromString("39bc5030-7c07-11eb-b141-7f65a26e8259"))
}
