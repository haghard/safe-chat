
addCommandAlias(
  "first",
  "runMain com.safechat.Server\n" +
    "-DENV=development " +
    "-DCONFIG=./src/main/resources/ " +
    "-DHTTP_PORT=8080 " +
    "-DAKKA_PORT=2550 " +
    "-Dakka.remote.artery.canonical.port=2550\n" +
    "-Dakka.remote.artery.canonical.hostname=192.168.77.10\n" +
    "-Dakka.cluster.seed-nodes.0=akka://safe-chat@192.168.77.10:2550\n" +
    "-Dakka.cluster.seed-nodes.1=akka://safe-chat@192.168.77.5:2550\n" +
    "-Dcassandra.hosts=84.201.150.26\n" +
    "-Dcassandra.psw=...\n" +
    "-Dcassandra.user=fsa"
)

//192.168.77.10

addCommandAlias(
  "second",
  "runMain com.safechat.Server\n" +
    "-DENV=development " +
    "-DCONFIG=./src/main/resources/ " +
    "-DHTTP_PORT=8080 " +
    "-DAKKA_PORT=2550 " +
    "-Dakka.remote.artery.tcp.port=2550\n" +
    "-Dakka.remote.artery.tcp.hostname=46.21.248.170\n" +
    "-Dakka.cluster.seed-nodes.0=akka://echatter@95.213.236.45:2550\n" +
    "-Dakka.cluster.seed-nodes.1=akka://echatter@46.21.248.170:2550\n" +
    "-Dcassandra.hosts=84.201.150.26\n" +
    "-Dcassandra.psw=... \n " +
    "-Dcassandra.user=... \n "
)
