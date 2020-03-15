# End-to-End encrypted chat

# Main idea
We want to have a MergeHub connected with a BroadcastHub to achieve dynamic fan-in/fan-out (many-to-many) per a chat room in combination with StreamRefs to get long-running streams of data between two entities over the network.  

# Connect harry
ws://127.0.0.1:8080/chat/aaa/user/harry?pub=hjkhkjhjk

# Connect charly
ws://127.0.0.1:8080/chat/aaa/user/charly?pub=hjkhkjhjk

# Message format
 harry:charly:Hello
 harry:charly:WATS up

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

docker run --net="host" -d -p 2551:2551 -p 8080:8080 -e HOSTNAME=188.68.210.125 -e HTTP_PORT=8080 -e AKKA_PORT=2551 -e CASSANDRA=84.201.150.26 -e SEEDS=188.68.210.125:2551,85.119.150.35:2551 -e CAS_USER=fsa -e CAS_PWS= -m 700MB haghard/safe-chat:0.1.0

docker run --net="host" -d -p 2551:2551 -p 8080:8080 -e HOSTNAME=85.119.150.35 -e HTTP_PORT=8080 -e AKKA_PORT=2551 -e CASSANDRA=84.201.150.26 -e SEEDS=188.68.210.125:2551,85.119.150.35:2551 -e CAS_USER=fsa -e CAS_PWS= -m 700MB haghard/safe-chat:0.1.0

```


```bash

http GET 188.68.210.125:8080/cluster/members

ws://188.68.210.125:8080/chat/aaa/user/charley?key=...
ws://85.119.150.35:8080/chat/aaa/user/charley?key=...

http GET 188.68.210.125:8080/cluster/shards/chat-rooms

Executes leave operation in cluster for provided address
http DELETE 188.68.210.125:8080/cluster/members/akka://echatter@85.119.150.35:2551


```


## Links

http://allaboutscala.com/scala-frameworks/akka/

https://doc.akka.io/docs/akka/2.6/typed/from-classic.html

https://www.lightbend.com/blog/cloud-native-app-design-techniques-distributed-state

https://www.lightbend.com/blog/cloud-native-app-design-techniques-cqrs-event-sourcing-messaging

https://doc.akka.io/docs/akka/current/typed/persistence.html

https://doc.akka.io/docs/akka/current/typed/persistence.html#example

https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html


## message evolution/versioning

https://www.scala-exercises.org/shapeless/coproducts

https://github.com/Keenworks/akka-avro-evolution.git

https://softwaremill.com/schema-registry-and-topic-with-multiple-message-types/

https://blog.softwaremill.com/the-best-serialization-strategy-for-event-sourcing-9321c299632b

https://github.com/IainHull/akka-persistence-message-bug


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

## message evolution/versioning

https://www.scala-exercises.org/shapeless/coproducts
https://github.com/Keenworks/akka-avro-evolution.git
https://softwaremill.com/schema-registry-and-topic-with-multiple-message-types/

https://github.com/IainHull/akka-persistence-message-bug
http://martin.kleppmann.com/2012/12/05/schema-evolution-in-avro-protocol-buffers-thrift.html
https://www.programcreek.com/java-api-examples/?code=rkluszczynski/avro-cli/avro-cli-master/src/main/java/io/github/rkluszczynski/avro/cli/command/conversion/RawConverterUtil.java


## Snapshotting
https://doc.akka.io/docs/akka/current/typed/persistence-snapshot.html


## SinkRef serialization/deserialization
https://blog.softwaremill.com/akka-references-serialization-with-protobufs-up-to-akka-2-5-87890c4b6cb0

## Cassandra

https://medium.com/@27.rahul.k/cassandra-ttl-intricacies-and-usage-by-examples-d54248f2853c

https://blog.softwaremill.com/7-mistakes-when-using-apache-cassandra-51d2cf6df519

https://github.com/wiringbits/safer.chat


##  Avro

https://avro.apache.org/docs/1.8.2/spec.html#Maps
https://www.confluent.io/blog/learn-stream-processing-with-kafka-tutorials/

## Git

git tag -a v0.1.0 -m "v0.1.0" &&  git push --tags

```bash

select persistence_id, partition_nr, sequence_nr, timestamp, ser_id, ser_manifest from safe_chat_journal where persistence_id='chat-room|703c1ae555da3cd4' and partition_nr = 0;

 persistence_id             | partition_nr | sequence_nr | timestamp                            | ser_id | ser_manifest
----------------------------+--------------+-------------+--------------------------------------+--------+----------------------------------------------------------------------------
 chat-room|703c1ae555da3cd4 |            0 |           1 | e6dbf150-1e8c-11ea-a11b-7508a1320b27 |   9999 |    com.safechat.domain.MsgEnvelope/Joined:b936961c182c4389a3f88ba780575915
 chat-room|703c1ae555da3cd4 |            0 |           2 | e7db8700-1e8c-11ea-a11b-7508a1320b27 |   9999 | com.safechat.domain.MsgEnvelope/TextAdded:b936961c182c4389a3f88ba780575915
 chat-room|703c1ae555da3cd4 |            0 |           3 | e86e9f40-1e8c-11ea-a11b-7508a1320b27 |   9999 | com.safechat.domain.MsgEnvelope/TextAdded:b936961c182c4389a3f88ba780575915
 chat-room|703c1ae555da3cd4 |            0 |           4 | e8ed9340-1e8c-11ea-a11b-7508a1320b27 |   9999 | com.safechat.domain.MsgEnvelope/TextAdded:b936961c182c4389a3f88ba780575915
 chat-room|703c1ae555da3cd4 |            0 |           5 | e9658260-1e8c-11ea-a11b-7508a1320b27 |   9999 | com.safechat.domain.MsgEnvelope/TextAdded:b936961c182c4389a3f88ba780575915

```


Link to read
https://doc.akka.io/docs/akka/current/typed/actors.html#a-more-complex-example
https://github.com/akka/akka-samples/tree/2.6/akka-sample-sharding-scala