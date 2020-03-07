import sbt._
import sbtdocker.ImageName

val projectName   = "safe-chat"
val Version       = "0.1.0"

val akkaVersion = "2.6.3"
val akkaHttpVersion = "10.1.10"

promptTheme := ScalapenosTheme

lazy val commonSettings = Seq(
  name := projectName,
  organization := "haghard",
  version := Version,
  startYear := Some(2019),
  //sbt headerCreate
  licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
  scalaVersion := "2.13.1",
  headerMappings := headerMappings.value + (HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment),
  headerLicense  := Some(HeaderLicense.Custom("Copyright (c) 2019 Vadim Bondarev. All rights reserved."))
)

lazy val root = project
  .in(file("."))
  .settings(commonSettings)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),

    resolvers ++= Seq("Typesafe Snapshots" at "https://repo.akka.io/snapshots"),

    parallelExecution in Test := false,
    javaOptions ++= Seq("-Xmx1024m", "-XX:MaxMetaspaceSize=900m", "-XX:+UseG1GC"),

    mainClass in assembly := Some("com.safechat.Server"),
    assemblyJarName in assembly := s"$projectName-${version.value}.jar",

    // Resolve duplicates for Sbt Assembly
    assemblyMergeStrategy in assembly := {
      case PathList(xs @ _*) if xs.last == "io.netty.versions.properties" =>
        MergeStrategy.rename
      case other => (assemblyMergeStrategy in assembly).value(other)
    },

    imageNames in docker := Seq(
      ImageName(
        namespace = Some("haghard"),
        repository = "safe-chat",
        tag = Some(version.value)
      )
    ),

    dockerfile in docker := {
      // development | production
      val APP_ENV = sys.props.getOrElse("env", "production")

      val baseDir        = baseDirectory.value
      val artifact       = assembly.value

      println(s"★ ★ ★   Build Docker image for Env:$APP_ENV $projectName - $artifact ★ ★ ★")

      val imageAppBaseDir    = "/app"
      val configDir          = "conf"
      val artifactTargetPath = s"$imageAppBaseDir/${artifact.name}"

      val dockerResourcesDir        = baseDir / "docker-resources"
      val dockerResourcesTargetPath = s"$imageAppBaseDir/"

      val avroResourcesDir            = baseDir / "src" / "main" / "avro" / "UsersEventsV1.avsc"
      val avroResourcesDirTargetPath  = s"$imageAppBaseDir/avro/UsersEventsV1.avsc"

      val prodConfigSrc = baseDir / "src" / "main" / "resources" / "production.conf"
      val devConfigSrc  = baseDir / "src" / "main" / "resources" / "development.conf"

      val appProdConfTarget = s"$imageAppBaseDir/$configDir/production.conf"
      val appDevConfTarget  = s"$imageAppBaseDir/$configDir/development.conf"

      new sbtdocker.mutable.Dockerfile {
        from("adoptopenjdk/openjdk12")
        //from("adoptopenjdk/openjdk11:jdk-11.0.2.9")
        //from("hseeberger/openjdk-iptables:8u181-slim")
        //adoptopenjdk/openjdk11:latest adoptopenjdk/openjdk11:jdk-11.0.1.13 openjdk:jre-alpine, openjdk:8-jre-alpine, openjdk:10-jre
        maintainer("haghard")

        env("VERSION", version.value)
        env("APP_BASE", imageAppBaseDir)
        env("CONFIG", s"$imageAppBaseDir/$configDir")

        env("ENV", APP_ENV)

        workDir(imageAppBaseDir)

        copy(artifact, artifactTargetPath)
        copy(dockerResourcesDir, dockerResourcesTargetPath)

        copy(avroResourcesDir, avroResourcesDirTargetPath)

        if (prodConfigSrc.exists)
          copy(prodConfigSrc, appProdConfTarget) //Copy the prod config

        if (devConfigSrc.exists)
          copy(devConfigSrc, appDevConfTarget) //Copy the prod config

        runRaw(s"ls $appProdConfTarget")
        runRaw(s"ls $appDevConfTarget")

        runRaw(s"cd $configDir && ls -la && cd ..")

        runRaw("pwd")
        runRaw("ls -la")

        entryPoint(s"${dockerResourcesTargetPath}docker-entrypoint.sh")
      }
    }
  )
  .enablePlugins(sbtdocker.DockerPlugin, BuildInfoPlugin)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,

  //local build for 2.13 /Users/haghard/.ivy2/local/com.github.TanUkkii007/akka-cluster-custom-downing_2.13/0.0.13-SNAPSHOT/jars/akka-cluster-custom-downing_2.13.jar
  //"com.github.TanUkkii007" %% "akka-cluster-custom-downing" % "0.0.12",
  "com.github.TanUkkii007" %% "akka-cluster-custom-downing" % "0.0.13-SNAPSHOT", //local build that uses CoordinatedShutdown to down self

  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,

  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,

  "com.typesafe.akka" %% "akka-persistence-query"     % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-cassandra" % "0.103",

  //a module that provides HTTP endpoints for introspecting and managing Akka clusters
  "com.lightbend.akka.management" %% "akka-management-cluster-http" % "1.0.5",

  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,

  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.apache.avro" % "avro" % "1.9.1",

  //"com.twitter" %% "algebird-core" % "0.13.6",

  "commons-codec" % "commons-codec" % "1.11",
  "org.scalatest" %% "scalatest" % "3.1.1" % Test,
  "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,

  // li haoyi ammonite repl embed
  ("com.lihaoyi" % "ammonite" % "1.9.2" % "test").cross(CrossVersion.full)
)

//workaround for sbt 1.3.0 https://github.com/sbt/sbt/issues/5075
//comment out for test:run
//Compile / run / fork := true

scalafmtOnCompile := true

//AvroConfig / stringType := "String"
AvroConfig / fieldVisibility := "private"
//AvroConfig / createSetters := true
AvroConfig / enableDecimalLogicalType := true

// ammonite repl
// test:run
sourceGenerators in Test += Def.task {
  val file = (sourceManaged in Test).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue

