addCommandAlias(
  "first",
  "runMain com.safechat.Server\n" +
    "-DENV=development " +
    "-DCONFIG=./src/main/resources/ " +
    "-DHTTP_PORT=8080 " +
    "-DAKKA_PORT=2550 " +
    "-Dakka.remote.artery.canonical.port=2550\n" +
    "-Dakka.remote.artery.canonical.hostname=127.0.0.1\n" +
    "-Dakka.cluster.seed-nodes.0=akka://safe-chat@127.0.0.1:2550\n" +
    "-Dakka.cluster.seed-nodes.1=akka://safe-chat@127.0.0.2:2550\n" +
    "-Dcassandra.hosts=84.201.150.26:9042,84.201.146.112:9042\n"+
    "-Dcassandra.psw=..." +
    "-Dcassandra.user=..."
)

//sudo ifconfig lo0 127.0.0.2 add
addCommandAlias(
  "second",
  "runMain com.safechat.Server\n" +
    "-DENV=development " +
    "-DCONFIG=./src/main/resources/ " +
    "-DHTTP_PORT=8080 " +
    "-DAKKA_PORT=2550 " +
    "-Dakka.remote.artery.canonical.port=2550\n" +
    "-Dakka.remote.artery.canonical.hostname=127.0.0.2\n" +
    "-Dakka.cluster.seed-nodes.0=akka://safe-chat@127.0.0.1:2550\n" +
    "-Dakka.cluster.seed-nodes.1=akka://safe-chat@127.0.0.2:2550\n" +
    "-Dcassandra.hosts=84.201.150.26:9042,84.201.146.112:9042\n"+
    //"-Ddatastax-java-driver.basic.contact-points.0=84.201.150.26:9042\n" +
    //"-Ddatastax-java-driver.basic.contact-points.1=84.201.146.112:9042\n" +
    "-Dcassandra.psw=..." +
    "-Dcassandra.user=..."
)
