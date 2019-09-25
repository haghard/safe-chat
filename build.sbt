name := "safe-chat"

version := "0.1"

scalaVersion := "2.13.1" //"2.12.8"

//val akkaVersion = "2.5-20190725-210209"

//https://discuss.lightbend.com/t/akka-2-6-0-m6-released/4857
//https://discuss.lightbend.com/t/akka-2-6-0-m7-released/5008
val akkaVersion = "2.6.0-M7"
//"2.5.25"

val akkaHttpVersion = "10.1.10"

resolvers ++= Seq(
  "Typesafe Snapshots" at "https://repo.akka.io/snapshots"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,

  //local build for 2.13 /Users/haghard/.ivy2/local/com.github.TanUkkii007/akka-cluster-custom-downing_2.13/0.0.13-SNAPSHOT/jars/akka-cluster-custom-downing_2.13.jar
  //"com.github.TanUkkii007" %% "akka-cluster-custom-downing" % "0.0.12",
  "com.github.TanUkkii007" %% "akka-cluster-custom-downing" % "0.0.13-SNAPSHOT", //local build that uses CoordinatedShutdown to down self

  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,

  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,

  "com.typesafe.akka" %% "akka-persistence-query"     % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-cassandra" % "0.99",

  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,

  //"com.typesafe.akka" %% "akka-stream-contrib" % "0.10",

  "ch.qos.logback" % "logback-classic" % "1.2.3",

  "org.apache.avro" % "avro" % "1.9.1",

  "commons-codec" % "commons-codec" % "1.11",
  "org.scalatest" %% "scalatest" % "3.0.8" % Test,
  "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,

  // li haoyi ammonite repl embed
  //("com.lihaoyi" % "ammonite" % "1.7.1" % "test").cross(CrossVersion.full)
)

//workaround for sbt 1.3.0 https://github.com/sbt/sbt/issues/5075
//comment out for test:run
Compile / run / fork := true

scalafmtOnCompile := true

// ammonite repl
// test:run
sourceGenerators in Test += Def.task {
  val file = (sourceManaged in Test).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue

