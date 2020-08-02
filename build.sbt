import sbt._
import sbtdocker.ImageName

val projectName   = "safe-chat"
val Version       = "0.3.0-SNAPSHOT"

val akkaVersion = "2.6.8"
val AkkaManagement = "1.0.8"

val akkaHttpVersion = "10.1.12"

promptTheme := ScalapenosTheme

lazy val commonSettings = Seq(
  name := projectName,
  organization := "haghard",
  version := Version,
  startYear := Some(2019),
  developers := List(
    Developer(
      "haghard",
      "Vadim Bondarev",
      "hagard84@gmail.com",
      url("http://haghard.ru")
    )
  ),

  //sbt headerCreate
  licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
  scalaVersion := "2.13.3",
  headerMappings := headerMappings.value + (HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment),
  headerLicense  := Some(HeaderLicense.Custom("Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved."))
)

lazy val root = project
  .in(file("."))
  .settings(commonSettings)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "com.safechat",

    resolvers ++= Seq("Typesafe Snapshots" at "https://repo.akka.io/snapshots"),

    parallelExecution in Test := false,
    javaOptions ++= Seq("-Xmx1024m", "-XX:MaxMetaspaceSize=900m", "-XX:+UseG1GC"),

    mainClass in assembly := Some("com.safechat.Server"),
    assemblyJarName in assembly := s"$projectName-${version.value}.jar",

    // Resolve duplicates for Sbt Assembly
    /*
    assemblyMergeStrategy in assembly := {
      case PathList(xs @ _*) if xs.last == "io.netty.versions.properties" =>
        MergeStrategy.rename
      //case PathList("io.netty", "netty-common", "4.1.39.Final") => MergeStrategy.discard
      case other => (assemblyMergeStrategy in assembly).value(other)
    },*/

    // Resolve duplicates for Sbt Assembly
    assemblyMergeStrategy in assembly := {
      case PathList("META-INF", xs @ _*) =>
        MergeStrategy.discard
      case PathList(xs@_*) if xs.last == "module-info.class" =>
        MergeStrategy.discard
      case PathList(xs@_*) if xs.last == "io.netty.versions.properties" =>
        MergeStrategy.rename
      case other =>
        //MergeStrategy.first
        (assemblyMergeStrategy in assembly).value(other)
    },

    /*assemblyExcludedJars in assembly := {
      val cp = (fullClasspath in assembly).value
        //netty-all:4.1.39.Final:jar
      cp filter { n => n.data.getName == "netty-all-4.1.39.Final.jar" /*|| n.data.getName == "jersey-core-1.9.jar"*/ }
    },*/

    /*assemblyMergeStrategy in assembly := {
     case PathList("META-INF", xs @ _*) =>
       MergeStrategy.discard
     case PathList("io.netty", xs @ _*) =>
       //pick oldest netty version
       ///io/netty/netty-all/4.1.39.Final/netty-all-4.1.39.Final.jar:io/netty/util/internal/shaded/org/jctools/util/UnsafeAccess.class
       //io/netty/netty-common/4.1.45.Final/netty-common-4.1.45.Final.jar:io/netty/util/internal/shaded/org/jctools/util/UnsafeAccess.class
       MergeStrategy.last
     case other =>
       //MergeStrategy.last
       (assemblyMergeStrategy in assembly).value(other)
    },*/

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

      val avroResourcesDir            = baseDir / "src" / "main" / "avro" / "ChatRoomEventsV1.avsc"
      val avroResourcesDirTargetPath  = s"$imageAppBaseDir/avro/ChatRoomEventsV1.avsc"

      val prodConfigSrc = baseDir / "src" / "main" / "resources" / "production.conf"
      val devConfigSrc  = baseDir / "src" / "main" / "resources" / "development.conf"

      val appProdConfTarget = s"$imageAppBaseDir/$configDir/production.conf"
      val appDevConfTarget  = s"$imageAppBaseDir/$configDir/development.conf"

      new sbtdocker.mutable.Dockerfile {
        from("adoptopenjdk:11")
        //from("adoptopenjdk/openjdk12:x86_64-ubuntu-jre-12.0.2_10")

        //from("adoptopenjdk:11.0.6_10-jdk-hotspot")

        //from("adoptopenjdk/openjdk12")
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
  "com.github.pureconfig"   %% "pureconfig"         % "0.12.3",
  "com.typesafe.akka"       %% "akka-actor-typed"   % akkaVersion,
  "com.typesafe.akka"       %% "akka-stream-typed"  % akkaVersion,
  "com.typesafe.akka"       %% "akka-cluster-typed" % akkaVersion,

  //"com.github.TanUkkii007" %% "akka-cluster-custom-downing" % "0.0.13-SNAPSHOT", //local build that uses CoordinatedShutdown to down self
  //"org.sisioh"        %% "akka-cluster-custom-downing" % "0.1.0",
  //"com.swissborg"    %% "lithium" % "0.11.1", brings cats

  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,

  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,

  "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,

  "com.typesafe.akka" %% "akka-persistence-query"     % akkaVersion,

  ("com.typesafe.akka" %% "akka-persistence-cassandra" % "1.0.1") //-RC1
    .excludeAll(ExclusionRule(organization = "io.netty", name="netty-all")), //to exclude netty-all-4.1.39.Final.jar

  "com.typesafe.akka"             %% "akka-discovery"                    % akkaVersion,
  //"com.lightbend.akka.discovery"  %% "akka-discovery-kubernetes-api"     % AkkaManagement,
  "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagement,
  "com.lightbend.akka.management" %% "akka-management-cluster-http"      % AkkaManagement,

  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,

  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.apache.avro" % "avro" % "1.9.1",

  //https://kwark.github.io/refined-in-practice/#1
  //"eu.timepit" %% "refined"                 % "0.9.14",
  //"eu.timepit" %% "refined-shapeless"       % "0.9.14",

  "commons-codec" % "commons-codec" % "1.11",
  "org.scalatest" %% "scalatest" % "3.2.0" % Test,
  "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,

  // li haoyi ammonite repl embed
  ("com.lihaoyi" % "ammonite" % "2.2.0" % "test").cross(CrossVersion.full)
)

//comment out for test:run

Compile / run / fork := true

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

