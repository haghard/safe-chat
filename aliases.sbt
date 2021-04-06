addCommandAlias(
  "first",
  "runMain com.safechat.Boot\n" +
  "-DENV=development " +
  "-DCONFIG=./src/main/resources/ " +
  "-DHTTP_PORT=8080\n" +
  "-Dakka.remote.artery.canonical.hostname=127.0.0.1\n" +
  "-Dakka.remote.artery.canonical.port=2550\n" +
  "-DCONTACT_POINTS=127.0.0.1,127.0.0.2\n" +
  "-DCASSANDRA=127.0.0.1:9042\n" +
  "-DCAS_PWS=...\n" +
  "-DCAS_USR=chat"
)

//sudo ifconfig lo0 127.0.0.2 add
addCommandAlias(
  "second",
  "runMain com.safechat.Boot\n" +
  "-DENV=development " +
  "-DCONFIG=./src/main/resources/ " +
  "-DHTTP_PORT=8080 " +
  "-DAKKA_PORT=2550 " +
  "-DHOSTNAME=127.0.0.2\n" +
  "-DSEEDS=127.0.0.1:2550,127.0.0.2:2550\n" +
  "-DDISCOVERY_METHOD=config\n" +
  //"-Dakka.cluster.seed-nodes.0=akka://safe-chat@127.0.0.1:2550\n" +
  //"-Dakka.cluster.seed-nodes.1=akka://safe-chat@127.0.0.2:2550\n" +
  "-Dcassandra.hosts=84.201.150.26:9042,84.201.146.112:9042\n" +
  //"-Ddatastax-java-driver.basic.contact-points.0=84.201.150.26:9042\n" +
  //"-Ddatastax-java-driver.basic.contact-points.1=84.201.146.112:9042\n" +
  "-Dcassandra.psw=...\n" +
  "-Dcassandra.user=fsa"
)

//sudo ifconfig lo0 127.0.0.3 add
addCommandAlias(
  "third",
  "runMain com.safechat.Boot\n" +
  "-DENV=development " +
  "-DCONFIG=./src/main/resources/ " +
  "-DHTTP_PORT=8080 " +
  "-DAKKA_PORT=2550 " +
  "-DHOSTNAME=127.0.0.3\n" +
  "-DSEEDS=127.0.0.1:2550,127.0.0.2:2550\n" +
  "-DDISCOVERY_METHOD=config\n" +
  "-Dcassandra.hosts=84.201.150.26:9042,84.201.146.112:9042\n" +
  "-Dcassandra.psw=..\n" +
  "-Dcassandra.user=fsa"
)


// Local

//sbt localFirst
addCommandAlias(
  "localFirst",
  "runMain com.safechat.Boot\n" +
  "-DENV=development " +
  "-DCONFIG=./src/main/resources/ " +
  "-DHTTP_PORT=8080\n" +
  "-Dakka.remote.artery.canonical.hostname=127.0.0.1\n" +
  "-Dakka.remote.artery.canonical.port=2550\n" +
  "-DCONTACT_POINTS=127.0.0.1,127.0.0.2\n" +
  "-DCASSANDRA=127.0.0.1:9042\n" +
  "-DCAS_PWS=...\n" +
  "-DCAS_USR=chat"
)

//sudo ifconfig lo0 127.0.0.2 add
//sbt localSecond
addCommandAlias(
  "localSecond",
  "runMain com.safechat.Boot\n" +
  "-DENV=development " +
  "-DCONFIG=./src/main/resources/ " +
  "-DHTTP_PORT=8080\n" +
  "-Dakka.remote.artery.canonical.hostname=127.0.0.2\n" +
  "-Dakka.remote.artery.canonical.port=2550\n" +
  "-DCONTACT_POINTS=127.0.0.1,127.0.0.2\n" +
  "-DCASSANDRA=127.0.0.1:9042\n" +
  "-DCAS_PWS=...\n" +
  "-DCAS_USR=chat"
)

addCommandAlias("db", "runMain com.safechat.LocalCassandra")


addCommandAlias("sfix", "scalafix OrganizeImports; test:scalafix OrganizeImports")
addCommandAlias("sFixCheck", "scalafix --check OrganizeImports; test:scalafix --check OrganizeImports")

//https://hub.docker.com/r/scylladb/scylla/

//docker run -d -p 9042:9042/tcp -v /Volumes/dev/github/safe-chat/scylla/chat:/var/lib/scylla scylladb/scylla:4.3.2 --broadcast-address=127.0.0.1 --smp 2 --memory=750M --overprovisioned 1
//docker exec -it <hash> cqlsh
