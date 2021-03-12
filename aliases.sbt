addCommandAlias(
  "first",
  "runMain com.safechat.Server\n" +
  "-DENV=development " +
  "-DCONFIG=./src/main/resources/ " +
  "-DHTTP_PORT=8080\n" +
  "-DAKKA_PORT=2550\n" +
  "-DHOSTNAME=127.0.0.1\n" +
  "-DSEEDS=127.0.0.1:2550,127.0.0.2:2550\n" +
  "-DDISCOVERY_METHOD=config\n" +
  "-Dcassandra.hosts=84.201.150.26:9042,84.201.146.112:9042\n" +
  "-Dcassandra.psw=ncXMbELjuDycnokVhnQowLaFzcsPfnRJrmgTAeRmxouuexrcQFdx3mBPzcJNawEy\n" +
  "-Dcassandra.user=fsa"
)

//sudo ifconfig lo0 127.0.0.2 add
addCommandAlias(
  "second",
  "runMain com.safechat.Server\n" +
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
  "-Dcassandra.psw=ncXMbELjuDycnokVhnQowLaFzcsPfnRJrmgTAeRmxouuexrcQFdx3mBPzcJNawEy\n" +
  "-Dcassandra.user=fsa"
)

//sudo ifconfig lo0 127.0.0.3 add
addCommandAlias(
  "third",
  "runMain com.safechat.Server\n" +
  "-DENV=development " +
  "-DCONFIG=./src/main/resources/ " +
  "-DHTTP_PORT=8080 " +
  "-DAKKA_PORT=2550 " +
  "-DHOSTNAME=127.0.0.3\n" +
  "-DSEEDS=127.0.0.1:2550,127.0.0.2:2550\n" +
  "-DDISCOVERY_METHOD=config\n" +
  "-Dcassandra.hosts=84.201.150.26:9042,84.201.146.112:9042\n" +
  "-Dcassandra.psw=ncXMbELjuDycnokVhnQowLaFzcsPfnRJrmgTAeRmxouuexrcQFdx3mBPzcJNawEy\n" +
  "-Dcassandra.user=fsa"
)



//Avro support for commands !!!


/// Local

//sbt localFirst
addCommandAlias(
  "localFirst",
  "runMain com.safechat.Server\n" +
  "-DENV=development " +
  "-DCONFIG=./src/main/resources/ " +
  "-DHTTP_PORT=8080\n" +
  "-DAKKA_PORT=2550\n" +
  "-DHOSTNAME=127.0.0.1\n" +
  "-DSEEDS=127.0.0.1:2550,127.0.0.2:2550\n" +
  "-DDISCOVERY_METHOD=config\n" +
  "-Dcassandra.hosts=127.0.0.1:9042\n" +
  "-Dcassandra.psw=pws\n" +
  "-Dcassandra.user=chat"
)

//sudo ifconfig lo0 127.0.0.2 add
//sbt localSecond
addCommandAlias(
  "localSecond",
  "runMain com.safechat.Server\n" +
  "-DENV=development " +
  "-DCONFIG=./src/main/resources/ " +
  "-DHTTP_PORT=8080\n" +
  "-DAKKA_PORT=2550\n" +
  "-DHOSTNAME=127.0.0.2\n" +
  "-DSEEDS=127.0.0.1:2550,127.0.0.2:2550\n" +
  "-DDISCOVERY_METHOD=config\n" +
  "-Dcassandra.hosts=127.0.0.1:9042\n" +
  "-Dcassandra.psw=pws\n" +
  "-Dcassandra.user=chat"
)

addCommandAlias("db", "runMain com.safechat.LocalCassandra")


addCommandAlias("sfix", "scalafix OrganizeImports; test:scalafix OrganizeImports")
addCommandAlias("sFixCheck", "scalafix --check OrganizeImports; test:scalafix --check OrganizeImports")

//https://hub.docker.com/r/scylladb/scylla/
//docker run -d -p 9042:9042/tcp -v /Volumes/dev/github/safe-chat/scylla/chat:/var/lib/scylla scylladb/scylla:4.3.2 --broadcast-address=127.0.0.1 --smp 1 --memory=750M --overprovisioned 1