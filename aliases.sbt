addCommandAlias(
  "first",
  "runMain com.safechat.Server\n" +
  "-DENV=development " +
  "-DCONFIG=./src/main/resources/ " +
  "-DHTTP_PORT=8080\n" +
  "-DAKKA_PORT=2550\n" +
  "-DHOSTNAME=127.0.0.1\n" +
  "-DSEEDS=127.0.0.1:2550,127.0.0.2:2550\n" +
  "-DDISCOVERY_BACKEND=config\n" +
  "-Dcassandra.hosts=84.201.150.26:9042,84.201.146.112:9042\n" +
  "-Dcassandra.psw=...\n" +
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
  "-DDISCOVERY_BACKEND=config\n" +
  //"-Dakka.cluster.seed-nodes.0=akka://safe-chat@127.0.0.1:2550\n" +
  //"-Dakka.cluster.seed-nodes.1=akka://safe-chat@127.0.0.2:2550\n" +
  "-Dcassandra.hosts=84.201.150.26:9042,84.201.146.112:9042\n" +
  //"-Ddatastax-java-driver.basic.contact-points.0=84.201.150.26:9042\n" +
  //"-Ddatastax-java-driver.basic.contact-points.1=84.201.146.112:9042\n" +
  "-Dcassandra.psw=...\n" +
  "-Dcassandra.user=fsa"
)
