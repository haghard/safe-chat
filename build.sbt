import sbt._
import sbtdocker.ImageName

val projectName   = "safe-chat"
val Version       = "0.5.1"


val akkaVersion     = "2.6.19"

//val akkaVersion     = "2.6.19+92-a81ab6dc-SNAPSHOT"

//https://oss.sonatype.org/content/repositories/snapshots/com/typesafe/akka/akka-actor-typed_2.13/
/*
2.6.18+98-331db9b8-SNAPSHOT" //"2.6.18"
2.6.19+89-462b4738-SNAPSHOT/	Sun Jun 05 16:48:52 UTC 2022
2.6.19+9-3e1a43f9-SNAPSHOT/	Thu Mar 24 15:29:51 UTC 2022
2.6.19+90-3f61dfdb-SNAPSHOT/	Mon Jun 13 08:11:31 UTC 2022
2.6.19+91-2c85b86b-SNAPSHOT/	Mon Jun 13 09:07:44 UTC 2022
2.6.19+92-a81ab6dc-SNAPSHOT/	Tue Jun 14 07:47:46 UTC 2022
*/


val akkaHttpVersion = "10.2.9"
val AkkaManagement  = "1.1.3"
val AkkaPersistenceCassandraVersion = "1.0.5"

val AkkaProjectionVersion = "1.2.3"

promptTheme := ScalapenosTheme

lazy val scalacSettings = Seq(
  scalacOptions ++= Seq(
    //"-deprecation",                            // Emit warning and location for usages of deprecated APIs.
    "-Xsource:2.13",
    "-target:jvm-11",
    //"-target:jvm-14",
    "-explaintypes",                             // Explain type errors in more detail.
    "-feature",                                  // Emit warning and location for usages of features that should be imported explicitly.
    "-language:existentials",                    // Existential types (besides wildcard types) can be written and inferred
    "-language:experimental.macros",             // Allow macro definition (besides implementation and application)
    "-language:higherKinds",                     // Allow higher-kinded types
    "-language:implicitConversions",             // Allow definition of implicit functions called views
    "-unchecked",                                // Enable additional warnings where generated code depends on assumptions.
    "-Xcheckinit",                               // Wrap field accessors to throw an exception on uninitialized access.
    //"-Xfatal-warnings",                          // Fail the compilation if there are any warnings.
    "-Xlint:adapted-args",                       // Warn if an argument list is modified to match the receiver.
    "-Xlint:constant",                           // Evaluation of a constant arithmetic expression results in an error.
    "-Xlint:delayedinit-select",                 // Selecting member of DelayedInit.
    "-Xlint:doc-detached",                       // A Scaladoc comment appears to be detached from its element.
    "-Xlint:inaccessible",                       // Warn about inaccessible types in method signatures.
    "-Xlint:infer-any",                          // Warn when a type argument is inferred to be `Any`.
    "-Xlint:missing-interpolator",               // A string literal appears to be missing an interpolator id.
    "-Xlint:nullary-unit",                       // Warn when nullary methods return Unit.
    "-Xlint:option-implicit",                    // Option.apply used implicit view.
    "-Xlint:package-object-classes",             // Class or object defined in package object.
    "-Xlint:poly-implicit-overload",             // Parameterized overloaded implicit methods are not visible as view bounds.
    "-Xlint:private-shadow",                     // A private field (or class parameter) shadows a superclass field.
    "-Xlint:stars-align",                        // Pattern sequence wildcard must align with sequence component.
    "-Xlint:type-parameter-shadow",              // A local type parameter shadows a type already in scope.
    "-Ywarn-dead-code",                          // Warn when dead code is identified.
    "-Ywarn-extra-implicit",                     // Warn when more than one implicit parameter section is defined.
    "-Ywarn-numeric-widen",                      // Warn when numerics are widened.
    "-Ywarn-unused:implicits",                   // Warn if an implicit parameter is unused.
    "-Ywarn-unused:imports",                     // Warn if an import selector is not referenced.
    "-Ywarn-unused:locals",                      // Warn if a local definition is unused.
    "-Ywarn-unused:params",                      // Warn if a value parameter is unused.
    "-Ywarn-unused:patvars",                     // Warn if a variable bound in a pattern is unused.
    "-Ywarn-unused:privates",                    // Warn if a private member is unused.
    "-Ycache-plugin-class-loader:last-modified", // Enables caching of classloaders for compiler plugins
    "-Ycache-macro-class-loader:last-modified"   // and macro definitions. This can lead to performance improvements.
  )
/*
  scalacOptions ++= Seq(
    //"-deprecation",             // Emit warning and location for usages of deprecated APIs.
    "-unchecked",               // Enable additional warnings where generated code depends on assumptions.
    "-encoding", "UTF-8",       // Specify character encoding used by source files.
    "-Ywarn-dead-code",         // Warn when dead code is identified.
    "-Ywarn-extra-implicit",    // Warn when more than one implicit parameter section is defined.
    "-Ywarn-numeric-widen",     // Warn when numerics are widened.
    "-Ywarn-unused:implicits",  // Warn if an implicit parameter is unused.
    "-Ywarn-unused:imports",    // Warn if an import selector is not referenced.
    "-Ywarn-unused:locals",     // Warn if a local definition is unused.
    "-Ywarn-unused:params",     // Warn if a value parameter is unused.
    "-Ywarn-unused:patvars",    // Warn if a variable bound in a pattern is unused.
    "-Ywarn-unused:privates",   // Warn if a private member is unused.
    "-Ywarn-value-discard"      // Warn when non-Unit expression results are unused.
  )*/
)

lazy val commonSettings = Seq(
  name := projectName,
  organization := "haghard",
  version := Version,
  startYear := Some(2019),
  developers := List(Developer("haghard", "Vadim Bondarev", "hagard84@gmail.com", url("https://github.com/haghard"))),

  //sbt headerCreate
  licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
  scalaVersion := "2.13.8",
  headerMappings := headerMappings.value + (HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment),
  headerLicense  := Some(HeaderLicense.Custom("Copyright (c) 2019-2022 Vadim Bondarev. All rights reserved."))
)

lazy val root = project
  .in(file("."))
  .settings(commonSettings)
  .settings(scalacSettings)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "com.safechat",
    buildInfoOptions += BuildInfoOption.BuildTime,

    resolvers ++= Seq(
      Resolver.typesafeRepo("releases")
      //Resolver.typesafeRepo("snapshots"),
      //Resolver.mavenLocal
    ),

    Test / parallelExecution := false,

    //These setting is used when
    // Compile / run / fork := true and you run one of the aliases,
    //overwise use
    // sbt -J-Xmx1024M -J-XX:MaxMetaspaceSize=850M -J-XX:+UseG1GC -J-XX:+PrintCommandLineFlags -J-XshowSettings
    //javaOptions ++= Seq("-Xmx1024M", "-XX:MaxMetaspaceSize=850m", "-XX:+UseG1GC", "-XX:+PrintCommandLineFlags", "-XshowSettings"),

    //TODO: Check this out
    //https://github.com/zhao258147/personalization-demo/blob/d94eb38766a1ce374b7762a6ec26de2074af1a87/build.sbt#L75
    /*javaOptions in Universal ++= Seq(
      "-XX:+UnlockExperimentalVMOptions",
      "-XX:+UseCGroupMemoryLimitForHeap",
      "-XshowSettings:vm"
    ),*/


    assembly / mainClass := Some("com.safechat.Boot"),
    assembly / assemblyJarName := s"$projectName-${version.value}.jar",

    // Resolve duplicates for Sbt Assembly
    /*
    assemblyMergeStrategy in assembly := {
      case PathList(xs @ _*) if xs.last == "io.netty.versions.properties" =>
        MergeStrategy.rename
      //case PathList("io.netty", "netty-common", "4.1.39.Final") => MergeStrategy.discard
      case other => (assemblyMergeStrategy in assembly).value(other)
    },*/

    // Resolve duplicates for Sbt Assembly
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case PathList(xs@_*) if xs.last == "module-info.class" => MergeStrategy.discard
      case PathList(xs@_*) if xs.last == "io.netty.versions.properties" => MergeStrategy.rename
      case other => (assembly / assemblyMergeStrategy).value(other)
      //MergeStrategy.first
    },

    docker / imageNames := Seq(ImageName(namespace = Some("haghard"), repository = "safe-chat", tag = Some(version.value))),

    docker / dockerfile := {
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

      val avroResourcesDir            = baseDir / "src" / "main" / "avro" / "ChatRoomV1.avsc"
      val avroResourcesDirTargetPath  = s"$imageAppBaseDir/avro/ChatRoomV1.avsc"

      val prodConfigSrc = baseDir / "src" / "main" / "resources" / "production.conf"
      val devConfigSrc  = baseDir / "src" / "main" / "resources" / "development.conf"

      val appProdConfTarget = s"$imageAppBaseDir/$configDir/production.conf"
      val appDevConfTarget  = s"$imageAppBaseDir/$configDir/development.conf"

      new sbtdocker.mutable.Dockerfile {
        from("adoptopenjdk:14")
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

        //-v /Volumes/dev/github/safe-chat/scylla/chat:/var/lib/scylla
        //volume("./logs", "/opt/docker/logs")

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
  "com.typesafe.akka"       %% "akka-slf4j"             % akkaVersion,
  "com.github.pureconfig"   %% "pureconfig"             % "0.17.1",

  "com.typesafe.akka"       %% "akka-stream-typed"      % akkaVersion,
  "com.typesafe.akka"       %% "akka-cluster-typed"     % akkaVersion,
  "com.typesafe.akka"       %% "akka-cluster-metrics"   % akkaVersion,

  //"com.github.TanUkkii007" %% "akka-cluster-custom-downing" % "0.0.13-SNAPSHOT", //local build that uses CoordinatedShutdown to down self
  //"org.sisioh"        %% "akka-cluster-custom-downing" % "0.1.0",
  //"com.swissborg"    %% "lithium" % "0.11.1", brings cats !!!

  "com.typesafe.akka" %% "akka-coordination"   % akkaVersion,

  "com.typesafe.akka" %% "akka-cluster-sharding-typed"  % akkaVersion,

  "com.typesafe.akka"  %% "akka-persistence-typed"       % akkaVersion,
  "com.typesafe.akka"  %% "akka-persistence-query"       % akkaVersion,

  //https://doc.akka.io/docs/akka-projection/current/eventsourced.html
  "com.lightbend.akka" %% "akka-projection-eventsourced" % AkkaProjectionVersion,

  //Offset in Cassandra  https://doc.akka.io/docs/akka-projection/current/cassandra.html
  "com.lightbend.akka" %% "akka-projection-cassandra"    % AkkaProjectionVersion,

  //"com.lightbend.akka" %% "akka-projection-jdbc"         % AkkaProjectionVersion,


  ("com.typesafe.akka" %% "akka-persistence-cassandra"  % AkkaPersistenceCassandraVersion) //-RC1
    .excludeAll(ExclusionRule(organization = "io.netty", name="netty-all")), //to exclude netty-all-4.1.39.Final.jar

  "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % AkkaPersistenceCassandraVersion,

  //https://github.com/lightbend/akka-cluster-operator
  //https://developer.lightbend.com/guides/openshift-deployment/lagom/forming-a-cluster.html#akka-management-http
  //Akka management HTTP provides an HTTP API for querying the status of the Akka cluster, used both by the bootstrap process,
  // as well as health checks to ensure requests don’t get routed to your pods until the pods have joined the cluster.

  "com.typesafe.akka"             %% "akka-discovery"                    % akkaVersion,

  //"com.lightbend.akka.discovery"  %% "akka-discovery-kubernetes-api"     % AkkaManagement,
  //"com.lightbend.akka.management" %% "akka-lease-kubernetes"             % AkkaManagement,

  "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagement,
  "com.lightbend.akka.management" %% "akka-management-cluster-http"      % AkkaManagement,
  "com.lightbend.akka.discovery"  %% "akka-discovery-kubernetes-api"     % AkkaManagement,

  "com.typesafe.akka" %% "akka-http"            % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,

  "ch.qos.logback" % "logback-classic" % "1.2.11",

  "org.apache.avro" %   "avro"         %   "1.11.0",

  "com.softwaremill.quicklens" %% "quicklens" % "1.8.8",

  //"com.twitter"     %%  "bijection-avro"  %   "0.9.6",  // ++ 2.12.13!
  //"org.apache.avro" %   "avro-compiler"   %   "1.10.1",

  "commons-codec"   %   "commons-codec"   %   "1.11",
  "org.scalatest"   %%  "scalatest"       %   "3.2.12" % Test,

  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion,

  "com.typesafe.akka" %% "akka-http-testkit" %  akkaHttpVersion % Test,
  "com.typesafe.akka" %% "akka-testkit"      %  akkaVersion     % Test,

  //https://github.com/chatwork/akka-guard
  //"com.chatwork" %% "akka-guard-http-typed" % "1.5.3-SNAPSHOT",

  //https://github.com/typelevel/algebra/blob/46722cd4aa4b01533bdd01f621c0f697a3b11040/docs/docs/main/tut/typeclasses/overview.md
  //"org.typelevel" %% "algebra" % "2.1.0",

  //https://github.com/politrons/reactiveScala/blob/master/scala_features/src/main/scala/app/impl/scala/ReflectionFeature.scala
  //"org.scala-lang" % "scala-reflect" % scalaVersion.value

  "com.lihaoyi" % "ammonite" % "2.5.4" % "test" cross CrossVersion.full,
)

//comment out for test:run
//Compile / run / fork := true

scalafmtOnCompile := true

//AvroConfig / stringType := "String"

AvroConfig / fieldVisibility := "private"
AvroConfig / enableDecimalLogicalType := true
//AvroConfig / sourceDirectory := baseDirectory.value / "src" / "main" / "resources" / "avro"

//sbtPlugin := true // this makes a difference

// Scalafix

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"

Global / semanticdbEnabled := true
Global / semanticdbVersion := scalafixSemanticdb.revision
Global / watchAntiEntropy := scala.concurrent.duration.FiniteDuration(5, java.util.concurrent.TimeUnit.SECONDS)



addCommandAlias("sfix", "scalafix OrganizeImports; test:scalafix OrganizeImports")
addCommandAlias("sFixCheck", "scalafix --check OrganizeImports; test:scalafix --check OrganizeImports")
addCommandAlias("c", "compile")
addCommandAlias("r", "reload")


// transitive dependency of akka 2.5x that is brought in
dependencyOverrides ++= Seq(
  "com.typesafe.akka" %% "akka-protobuf"        % akkaVersion,
  "com.typesafe.akka" %% "akka-actor"           % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster"         % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding"% akkaVersion,
  "com.typesafe.akka" %% "akka-coordination"    % akkaVersion,
  "com.typesafe.akka" %% "akka-discovery"       % akkaVersion,
  "com.typesafe.akka" %% "akka-stream"          % akkaVersion,
  "com.typesafe.akka" %% "akka-http"            % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-core"       % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
)

// ammonite repl
// test:run

Test / sourceGenerators += Def.task {
  val file = (Test / sourceManaged).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue
