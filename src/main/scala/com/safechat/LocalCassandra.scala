package com.safechat

import java.io.File
import java.util.concurrent.CountDownLatch

import akka.persistence.cassandra.testkit.CassandraLauncher

//https://github.com/akka/akka-samples/blob/52054a5f8b8244e2cf37aa58085f362bba0f808e/akka-sample-persistence-dc-scala/src/main/scala/sample/persistence/multidc/ThumbsUpApp.scala

//runMain com.safechat.LocalCassandra
object LocalCassandra {

  def main(args: Array[String]): Unit = {
    startCassandraDatabase()
    println("Started Cassandra, press Ctrl + C to kill")
    new CountDownLatch(1).await()
  }

  def startCassandraDatabase(): Unit = {
    val databaseDirectory = new File("target/cassandra-db")
    CassandraLauncher.start(databaseDirectory, CassandraLauncher.DefaultTestConfigResource, clean = false, port = 9042)
  }
}