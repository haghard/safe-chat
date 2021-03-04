# End-to-End encrypted chat

# Main idea
We want to have a MergeHub connected with a BroadcastHub to achieve dynamic fan-in/fan-out (many-to-many) per a chat room in combination with StreamRefs to get long-running streams of data between two entities over the network.  


## Connect

ws://127.0.0.1:8080/chat/aaa/user/harry?pub=hjkhkjhjk

ws://127.0.0.2:8080/chat/aaa/user/charly?pub=hjkhkjhjk

chrome-extension://pfdhoblngboilpfeibdedpjgfnlcodoo/index.html

harry:charly:Hello

charly:harry:WATS up

### How to run locally

```bash

sudo ifconfig lo0 127.0.0.2 add

sbt first
sbt second

```


### How to build and publish with docker

```bash
  sbt -Denv=development docker && docker push haghard/safe-chat:0.1.0
      
```

```bash

docker run --net="host" -d -p 2551:2551 -p 8080:8080 -e HOSTNAME=10.130.0.22 -e HTTP_PORT=8080 -e AKKA_PORT=2551 -e CASSANDRA=84.201.150.26:9042,84.201.146.112:9042 -e SEEDS=10.130.0.22:2551 -e DISCOVERY_METHOD=config -e CAS_USER=... -e CAS_PWS=... -m 700MB haghard/safe-chat:0.1.0

```

### Management

```bash

Aladdin:OpenSesame - QWxhZGRpbjpPcGVuU2VzYW1l

http 127.0.0.1:8558/cluster/members "Authorization:Basic QWxhZGRpbjpPcGVuU2VzYW1l"
http 127.0.0.1:8558/cluster/members "Authorization:Basic QWxhZGRpbjpPcGVuU2VzYW1l"

http 127.0.0.1:8558/bootstrap/seed-nodes "Authorization:Basic QWxhZGRpbjpPcGVuU2VzYW1l"


http 127.0.0.1:8558/cluster/shards/chat-rooms

Leave
curl -w '\n' -X PUT -H 'Content-Type: multipart/form-data' -F operation=leave http://127.0.0.1:8080/cluster/members/safe-chat@127.0.0.2:2550
or

http DELETE 127.0.0.1:8080/cluster/members/akka://safe-chat@127.0.0.2:2550


curl -w '\n' -X PUT -H 'Content-Type: multipart/form-data' -F operation=down http://127.0.0.1:8080/cluster/members/safe-chat@127.0.0.2:2550
curl -w '\n' -X PUT -H 'Content-Type: multipart/form-data' -F operation=leave http://127.0.0.1:8080/cluster/members/safe-chat@127.0.0.2:2550


http 127.0.0.1:8080/cluster/shards/chat-rooms //shards on this node (local shards)
http 127.0.0.2:8080/cluster/shards/chat-rooms //shards on this node (local shards)


http 127.0.0.1:8080/cluster/members/akka://safe-chat@127.0.0.1:2550
http 127.0.0.1:8080/cluster/members/safe-chat@127.0.0.1:2550

```


```bash

ws://188.68.210.125:8080/chat/aaa/user/charley?key=sdfgsdf
ws://85.119.150.35:8080/chat/aaa/user/charley?key=sdfgsdf

http GET 188.68.210.125:8080/cluster/shards/chat-rooms

```


## Links

http://allaboutscala.com/scala-frameworks/akka/

https://doc.akka.io/docs/akka/2.6/typed/from-classic.html

https://www.lightbend.com/blog/cloud-native-app-design-techniques-distributed-state

https://www.lightbend.com/blog/cloud-native-app-design-techniques-cqrs-event-sourcing-messaging

https://doc.akka.io/docs/akka/current/typed/persistence.html

https://doc.akka.io/docs/akka/current/typed/persistence.html#example

https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html


### Talks about Akka Persistence Typed

LunaConf 2020 - Akka Persistence Typed by Renato Cavalcanti: https://youtu.be/hYucH6dXGSM?list=LLq_6THQ1qPDuFwd-a_O0pxg

https://github.com/renatocaval/akka-persistence-typed-talk


## Akka

https://doc.akka.io/docs/akka/2.6/index.html
 
https://doc.akka.io/docs/akka/2.6/typed/from-classic.html

https://discuss.lightbend.com/t/akka-2-6-0-m7-released/5008

AtLeastOnceDelivery with typed-actors: https://gist.github.com/patriknw/514bae62134050f24ca7af95ee977e54

https://doc.akka.io/docs/akka/2.6/project/migration-guide-2.5.x-2.6.x.html

https://github.com/hseeberger/welcome-akka-typed/blob/master/src/main/scala/rocks/heikoseeberger/wat/typed/Transfer.scala

https://doc.akka.io/docs/akka/current/typed/routers.html

https://doc.akka.io/docs/akka/current/typed/distributed-data.html

https://github.com/johanandren/akka-typed-samples.git

https://manuel.bernhardt.io/2019/07/11/tour-of-akka-typed-protocols-and-behaviors/

https://manuel.bernhardt.io/2019/08/07/tour-of-akka-typed-message-adapters-ask-pattern-and-actor-discovery/

https://manuel.bernhardt.io/2019/09/05/tour-of-akka-typed-supervision-and-signals/

https://github.com/rkuhn/blog/blob/master/01_my_journey_towards_understanding_distribution.md

https://skillsmatter.com/skillscasts/12671-akka-cluster-up-and-running

https://medium.com/bestmile/domain-driven-event-sourcing-with-akka-typed-5f5b8bbfb823

https://blog.softwaremill.com/3-reasons-to-adopt-event-sourcing-89cb855453f6

https://blog.knoldus.com/akka-cluster-formation-fundamentals/

https://blog.knoldus.com/akka-cluster-in-use-part-4-managing-a-cluster/

### Sharding: 
 
https://manuel.bernhardt.io/2018/02/26/tour-akka-cluster-cluster-sharding/

## Akka cluster split brain

https://blog.softwaremill.com/akka-cluster-split-brain-failures-are-you-ready-for-it-d9406b97e099

https://doc.akka.io/docs/akka-enhancements/current/split-brain-resolver.html#using-the-split-brain-resolver

https://www.youtube.com/watch?v=vc6eTolxGbM


## Akka cluster links

How Akka Cluster Works: https://www.lightbend.com/blog/akka-cluster-quickstart-dashboard-part-1-getting-started

Sample project: https://github.com/mckeeh3/akka-typed-java-cluster.git 

https://doc.akka.io/docs/akka/current/typed/extending.html


## Schema evolution/versioning

https://www.scala-exercises.org/shapeless/coproducts

https://github.com/Keenworks/akka-avro-evolution.git

https://softwaremill.com/schema-registry-and-topic-with-multiple-message-types/

http://martin.kleppmann.com/2012/12/05/schema-evolution-in-avro-protocol-buffers-thrift.html

https://www.programcreek.com/java-api-examples/?code=rkluszczynski/avro-cli/avro-cli-master/src/main/java/io/github/rkluszczynski/avro/cli/command/conversion/RawConverterUtil.java

https://doc.akka.io/docs/akka/current/persistence-schema-evolution.html?language=scala

https://www.scala-exercises.org/shapeless/coproducts

https://github.com/Keenworks/akka-avro-evolution.git

https://github.com/calvinlfer/Akka-Persistence-Schema-Evolution-Example (Event Adapters) 

https://softwaremill.com/schema-registry-and-topic-with-multiple-message-types/

https://blog.softwaremill.com/the-best-serialization-strategy-for-event-sourcing-9321c299632b

Schema Management at Depop talk: https://youtu.be/ztkyVHPaPgY

https://pulsar.apache.org/docs/en/concepts-schema-registry/
     


### Protocol-buffers

https://rockset.com/blog/ivalue-efficient-representation-of-dynamic-types-in-cplusplus/
https://www.lightbend.com/lightbend-platform-subscription

https://codeburst.io/protocol-buffers-part-1-serialization-library-for-microservices-37418e72908b
https://github.com/rotomer/protobuf-blogpost-1

https://codeburst.io/protocol-buffers-part-2-the-untold-parts-of-using-any-6a328560048d
https://github.com/rotomer/protobuf-blogpost-2

https://codeburst.io/protocol-buffers-part-3-json-format-e1ca0af27774
https://github.com/rotomer/protobuf-blogpost-3.git

https://developers.google.com/protocol-buffers/docs/proto3

https://developer.confluent.io/podcast/introducing-json-and-protobuf-support-ft-david-araujo-and-tushar-thole


### Schema registry

Schema registry: https://www.confluent.io/blog/author/robert-yokota/
                  

Putting Several Event Types in the Same Topic              https://www.confluent.io/blog/put-several-event-types-kafka-topic/

Putting Several Event Types in the Same Topic – Revisited: https://www.confluent.io/blog/multiple-event-types-in-the-same-kafka-topic



## Snapshotting

https://doc.akka.io/docs/akka/current/typed/persistence-snapshot.html


## SinkRef serialization/deserialization

https://blog.softwaremill.com/akka-references-serialization-with-protobufs-up-to-akka-2-5-87890c4b6cb0


## Cassandra

https://medium.com/@27.rahul.k/cassandra-ttl-intricacies-and-usage-by-examples-d54248f2853c

https://blog.softwaremill.com/7-mistakes-when-using-apache-cassandra-51d2cf6df519

https://github.com/wiringbits/safer.chat

https://doc.akka.io/docs/akka-persistence-cassandra/1.0/migrations.html

## Sharding

Distributed processing with Akka Cluster & Kafka(for how to integrate Kafka with Cluster Sharding): https://akka.io/blog/news/2020/03/18/akka-sharding-kafka-video 

How to use CQRS in Akka 2.6 https://www.youtube.com/watch?v=6ECsFlNNIAM

Akka typed persistence:  https://www.youtube.com/watch?v=hYucH6dXGSM

https://blog.knoldus.com/introduction-to-akka-cluster-sharding/

https://blog.knoldus.com/implementing-akka-cluster-sharding/ 

https://github.com/jmarin/akka-persistent-entity

https://github.com/jmarin/pixels

##  Avro

https://avro.apache.org/docs/1.8.2/spec.html#Maps

https://www.confluent.io/blog/learn-stream-processing-with-kafka-tutorials/

https://medium.com/jeroen-rosenberg/building-and-deploying-your-first-cloudflow-application-6ea4b7157e6d

https://medium.com/data-rocks/schema-evolution-is-not-that-complex-b7cf7eb567ac

## K8s

https://github.com/akka/akka-sample-cluster-kubernetes-scala.git
 
https://developer.lightbend.com/guides/openshift-deployment/lagom/forming-a-cluster.html

https://doc.akka.io/docs/akka-management/current/bootstrap/details.html

https://doc.akka.io/docs/akka-management/current/bootstrap/kubernetes-api.html  (akka.management.cluster.bootstrap.LowestAddressJoinDecider)

https://www.lightbend.com/blog/how-to-distribute-application-state-with-akka-cluster-part-3-kubernetes-monitoring

https://www.youtube.com/watch?v=2jKu_E1TZPM

Managing an Akka Cluster on Kubernetes - Markus Jura: https://www.youtube.com/watch?v=Sz-SE1FyhJE&list=PLLMLOC3WM2r5KDwkSRrLJ1_O6kZqlhhFt&index=22


## Gatling

https://blog.knoldus.com/gatling-for-websocket-protocol

https://github.com/michael-read/akka-typed-distributed-state-blog/tree/master/gatling

https://github.com/michael-read/akka-typed-distributed-state-blog/blob/master/gatling/src/test/scala/com/lightbend/gatling/ArtifactStateScenario.scala

https://blog.knoldus.com/gatling-with-jenkins/


### Websocket chats samples

1. Chat app (web socket) 
 MergeHub.source[String].toMat(BroadcastHub.sink[String])
https://markatta.com/codemonkey/posts/chat-with-akka-http-websockets/

2. Web socket to shard region: https://github.com/henrikengstrom/sa-2017-akka.git

3. Building a Reactive, Distributed Messaging Server in Scala and Akka with WebSockets:
https://medium.com/@nnnsadeh/building-a-reactive-distributed-messaging-server-in-scala-and-akka-with-websockets-c70440c494e3


### Distributed state blog series 

https://github.com/michael-read/akka-typed-distributed-state-blog
https://github.com/michael-read/akka-typed-distributed-state-blog/blob/master/Blog_Model.png

https://github.com/chbatey/akka-talks/blob/f3babead55fa7e678ce21dcbf780e9423afb7448/http-streams/src/test/scala/info/batey/akka/http/ApplyLoad.scala
https://whiteprompt.com/scala/stress-test-restful-service-gatling/
https://sysgears.com/articles/restful-service-load-testing-using-gatling-2/
https://github.com/satyriasizz/gatling2-load-test-example


## Jvm inside a docker container

https://merikan.com/2019/04/jvm-in-a-container/
https://www.lightbend.com/blog/cpu-considerations-for-java-applications-running-in-docker-and-kubernetes


https://blog.csanchez.org/2017/05/31/running-a-jvm-in-a-container-without-getting-killed/
https://blog.csanchez.org/2018/06/21/running-a-jvm-in-a-container-without-getting-killed-ii/

http://www.batey.info/docker-jvm-k8s.html
https://github.com/chbatey/docker-jvm-akka
https://blog.docker.com/2018/12/top-5-post-docker-container-java/
https://efekahraman.github.io/2018/04/docker-awareness-in-java


```

docker run -it --cpu-shares 2048 openjdk:11-jdk
jshell> Runtime.getRuntime().availableProcessors()
$1 ==> 2

```


### CPU considerations for Java applications running in Docker and Kubernetes

https://www.lightbend.com/blog/cpu-considerations-for-java-applications-running-in-docker-and-kubernetes


### On kubernetes

https://blog.softwaremill.com/running-akka-cluster-on-kubernetes-e4cd2913e951

## Videos

How to use CQRS in Akka 2.6 https://www.youtube.com/watch?v=6ECsFlNNIAM

Introduction To Akka Cluster Sharding https://youtu.be/SrPubnOKJcQ

How to do distribute, stateful processing with #Akka Cluster Sharding and Kafka https://akka.io/blog/news/2020/03/18/akka-sharding-kafka-video

Stateful OR Stateless Applications: To Akka Cluster, Or Not https://www.youtube.com/watch?v=CiVsKjZV-Ys

Split Brain Resolver in Akka Cluster https://www.youtube.com/watch?v=vc6eTolxGbM


### SBT

```bash

sbt '; set javaOptions += "-Dconfig.resource=cluster-application.conf" ; run’

sbt -J-XX:MaxMetaspaceSize=512M -J-XX:+PrintCommandLineFlags -J-XshowSettings

sbt -J-Xms256M -J-Xmx512M -J-XX:+UseG1GC -J-XX:+PrintCommandLineFlags -J-XshowSettings

sbt -J-Xms256M -J-Xmx512M -J-XX:MaxMetaspaceSize=368M -J-XX:+UseG1GC -J-XX:+PrintCommandLineFlags -J-XshowSettings

sbt -J-Xss2M -J-Xms256M -J-Xmx512M -J-XX:MaxMetaspaceSize=368M -J-XX:+UseG1GC -J-XX:+PrintCommandLineFlags -J-XshowSettings

sbt -J-XX:ThreadStackSize=2024 -J-XX:InitialHeapSize=268435456 -J-XX:-XX:MaxHeapSize=536870912 -J-XX:+UseG1GC -J-XX:+PrintCommandLineFlags

sbt -J-Xms256M -J-Xmx512M -J-XX:+PrintCommandLineFlags -J-XX:NativeMemoryTracking=summary -J-XshowSettings

```


### Examples
  
Killrweather:             https://github.com/akka/akka-samples/tree/2.6/akka-sample-sharding-scala

Persistent shopping cart: https://github.com/akka/akka-samples/tree/2.6/akka-sample-persistence-scala

DData example:            https://github.com/akka/akka-samples/tree/2.6/akka-sample-distributed-data-scala

CQRS ShoppingCart example: read-side is implemented using Akka Projections: https://github.com/akka/akka-samples/tree/2.6/akka-sample-cqrs-scala

Akka Persistence and MariaDB:  https://medium.com/@matteodipirro/stateful-actors-with-akka-event-sourcing-and-maria-db-d4202c6c599a


### A slice of a journal after a split brain

```bash

select persistence_id, sequence_nr, timestamp, ser_id, ser_manifest, writer_uuid from chat_journal where persistence_id='chat-rooms|k' and partition_nr = 0;

chat-rooms|k |81 | 1e4d74a0-7c07-11eb-968c-ed6f87b8049e |  99999 | EVENT_com.safechat.avro.persistent.domain.UserTextAdded | ab4e5ea0-b9c1-46e5-ad35-08ed8942640f
chat-rooms|k |82 | 1e68ebe0-7c07-11eb-968c-ed6f87b8049e |  99999 | EVENT_com.safechat.avro.persistent.domain.UserTextAdded | ab4e5ea0-b9c1-46e5-ad35-08ed8942640f
chat-rooms|k |83 | 1e9923a0-7c07-11eb-968c-ed6f87b8049e |  99999 | EVENT_com.safechat.avro.persistent.domain.UserTextAdded | ab4e5ea0-b9c1-46e5-ad35-08ed8942640f
chat-rooms|k |84 | 2becda10-7c07-11eb-b141-7f65a26e8259 |  99999 |    EVENT_com.safechat.avro.persistent.domain.UserJoined | 6446391b-38e0-470b-9031-32d9bf3b74eb
chat-rooms|k |84 | 381f6910-7c07-11eb-968c-ed6f87b8049e |  99999 | EVENT_com.safechat.avro.persistent.domain.UserTextAdded | ab4e5ea0-b9c1-46e5-ad35-08ed8942640f
chat-rooms|k |85 | 319efb50-7c07-11eb-b141-7f65a26e8259 |  99999 | EVENT_com.safechat.avro.persistent.domain.UserTextAdded | 6446391b-38e0-470b-9031-32d9bf3b74eb
chat-rooms|k |85 | 38229d60-7c07-11eb-968c-ed6f87b8049e |  99999 | EVENT_com.safechat.avro.persistent.domain.UserTextAdded | ab4e5ea0-b9c1-46e5-ad35-08ed8942640f
chat-rooms|k |86 | 32190d50-7c07-11eb-b141-7f65a26e8259 |  99999 | EVENT_com.safechat.avro.persistent.domain.UserTextAdded | 6446391b-38e0-470b-9031-32d9bf3b74eb
chat-rooms|k |86 | 38244b10-7c07-11eb-968c-ed6f87b8049e |  99999 | EVENT_com.safechat.avro.persistent.domain.UserTextAdded | ab4e5ea0-b9c1-46e5-ad35-08ed8942640f
chat-rooms|k |87 | 39bc5030-7c07-11eb-b141-7f65a26e8259 |  99999 | EVENT_com.safechat.avro.persistent.domain.UserTextAdded | 6446391b-38e0-470b-9031-32d9bf3b74eb
chat-rooms|k |88 | 3a37e8d0-7c07-11eb-b141-7f65a26e8259 |  99999 | EVENT_com.safechat.avro.persistent.domain.UserTextAdded | 6446391b-38e0-470b-9031-32d9bf3b74eb
chat-rooms|k |89 | 3a9d8870-7c07-11eb-b141-7f65a26e8259 |  99999 | EVENT_com.safechat.avro.persistent.domain.UserTextAdded | 6446391b-38e0-470b-9031-32d9bf3b74eb  

```


### The real time ordering of those write

```bash

ts 0    - ab4e5ea0-b9c1-46e5-ad35-08ed8942640f:83

ts 22.3 - 6446391b-38e0-470b-9031-32d9bf3b74eb:84
ts 31.9 - 6446391b-38e0-470b-9031-32d9bf3b74eb:85
ts 32.7 - 6446391b-38e0-470b-9031-32d9bf3b74eb:86

ts 42.823 - ab4e5ea0-b9c1-46e5-ad35-08ed8942640f:84
ts 42.844 - ab4e5ea0-b9c1-46e5-ad35-08ed8942640f:85
ts 42.855 - ab4e5ea0-b9c1-46e5-ad35-08ed8942640f:86

ts 47.9   - 6446391b-38e0-470b-9031-32d9bf3b74eb:87


```

Writer `ab4e5ea0-b9c1-46e5-ad35-08ed8942640f` freezes for some time and as a result new writer `6446391b-38e0-470b-9031-32d9bf3b74eb` recovers and continues writing messages (84,85 and 86). Later, the old writer `ab4e5ea0-b9c1-46e5-ad35-08ed8942640f` comes back and before downing itself it produces its own sequence of events (84,85 and 86) that was buffered in some internal queue.  





### Links to read

https://doc.akka.io/docs/akka/current/typed/actors.html#a-more-complex-example
https://github.com/akka/akka-samples/tree/2.6/akka-sample-sharding-scala

https://github.com/apache/cassandra/blob/trunk/src/java/org/apache/cassandra/dht/IPartitioner.java

### How to simulate a split brain

`http GET :8558/cluster/members`

a) Down but not terminate nodes on both sides of your partition.


0. Find target <pid> 
> lsof -i :2551 | grep LISTEN | awk '{print $2}'

1. Suspend the process  
> kill -stop <pid>

2. Send <pid> to Down via akka-http-managment interface
> curl -w '\n' -X PUT -H 'Content-Type: multipart/form-data' -F operation=down http://localhost:8080/cluster/members/safe-chat@127.0.0.1:2551  
> curl -w '\n' -X PUT -H 'Content-Type: multipart/form-data' -F operation=leave http://localhost:8080/cluster/members/safe-chat@127.0.0.1:2551 


3. Resume the process 
> kill -cont <pid>
 

b) Docker pause
c) Bring down all seed nodes leaving only non-seed nodes and then start the seed nodes again. They will form a new cluster and the rest of the cluster will be left
d) Incomplete coordinated shutdown.
e) Unresponsive applications due to long GC pause.

or drop some traffic

Use "iptable" to drop 1%, 2%, 5%, 10% of traffic using iptables command:

```bash

iptables -A INPUT -m statistic --mode random --probability 0.2 -j DROP 

iptables -A OUTPUT -m statistic --mode random --probability 0.2 -j DROP 

```

Another example

https://github.com/hseeberger/akkluster

To create network partitions we need to connect to a running container and block traffic:

```bash

docker run -d --cap-add NET_ADMIN ...

docker exec -i -t ... bash

iptables -A INPUT -p tcp -j DROP
iptables -D INPUT -p tcp -j DROP

```


### Akka-cluster-sharding links 

https://manuel.bernhardt.io/2018/02/26/tour-akka-cluster-cluster-sharding/

https://www.youtube.com/watch?v=SrPubnOKJcQ

https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html

https://github.com/michael-read/akka-typed-distributed-state-blog/blob/master/Blog_Model.png


## Sharding improvements in 2.6.10

https://doc.akka.io/docs/akka/2.6/additional/rolling-updates.html

### How to run in docker

```

docker-compose -f docker/docker-cassandra.yml up -d
docker-compose -f docker/docker-cassandra.yml rm


docker-compose -f docker/docker-cassandra-cluster.yml up -d 
docker-compose -f docker/docker-cassandra-cluster.yml rm


```

### Lease for shards

https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html#lease
        



### Git

git tag -a v0.2.0 -m "v0.2.0" &&  git push --tags


## How to run

```

sbt localFirst

sbt localSecond

```

### TO DO 

1. Add two roles: endpoint and domain. If the role of the cluster node is “domain” we simply start cluster sharding, 
otherwise we initialize the cluster sharding proxy so no shards are hosted by the node and start HTTP endpoints.
(See how it's done for https://www.lightbend.com/blog/how-to-distribute-application-state-with-akka-cluster-part-4-the-source-code)

2. Use `streamee` to enable streaming from http routes to shard region proxies. 

3. Make use of `askWithStatus` Example: https://developer.lightbend.com/docs/akka-platform-guide/microservices-tutorial/entity.html

4. Try replicated-event-sourcing 
https://doc.akka.io/docs/akka/current/typed/replicated-eventsourcing.html#replicated-event-sourcing
https://github.com/akka/akka-samples.git akka-sample-persistence-dc-scala
https://github.com/akka/akka/tree/146944f99934557eac72e6dc7fa25fc6b2f0f11c/akka-persistence-typed-tests/src/test/scala/docs/akka/persistence/typed
                



akka.cluster.sharding.PersistentShardCoordinator

