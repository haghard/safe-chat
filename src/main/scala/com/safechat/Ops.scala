// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat

import java.net.{InetAddress, NetworkInterface}

import scala.collection.{Map, Seq}
import scala.jdk.CollectionConverters._

trait Ops {
  val Opt = """(\S+)=(\S+)""".r

  val ethName      = "eth0"
  val ipExpression = """\d{1,3}.\d{1,3}.\d{1,3}.\d{1,3}"""

  def internalAddr: Option[InetAddress] =
    NetworkInterface.getNetworkInterfaces.asScala.toList
      .find(_.getName == ethName)
      .flatMap(x ⇒ x.getInetAddresses.asScala.toList.find(i ⇒ i.getHostAddress.matches(ipExpression)))

  def argsToOpts(args: Seq[String]): Map[String, String] =
    args.collect { case Opt(key, value) ⇒ key → value }.toMap

  def applySystemProperties(options: Map[String, String]): Unit =
    for ((key, value) ← options if key startsWith "-D") {
      println(s"Config override: $key = $value")
      System.setProperty(key.substring(2), value)
    }

  def applyProperties(args: Array[String]) = {
    val Opt = """-D(\S+)=(\S+)""".r
    args.toList.foreach { case Opt(key, value) ⇒
      println(s"Config override: $key = $value")
      System.setProperty(key, value)
    }
  }

}
