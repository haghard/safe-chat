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
    "-Dcassandra.hosts=84.201.150.26\n" +
    "-Dcassandra.psw=...\n" +
    "-Dcassandra.user=fsa"
)

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
    "-Dcassandra.hosts=84.201.150.26\n" +
    "-Dcassandra.psw=...\n" +
    "-Dcassandra.user=fsa"
)
