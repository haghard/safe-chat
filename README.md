# End-to-End encrypted chat

# Main idea
We want to have a MergeHub followed by a BroadcastHub to achieve dynamic fan-in and fan-out (many-to-many) per chat room in combination with StreamRefs to get long-running streams of data between two entities over the network. 

https://github.com/wiringbits/safer.chat 

ws://192.168.77.10:8080/chat/aaa/user/harry?key=...


### How to build and publish

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

Executes leave operation in cluster for provided {address}
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


## Akka Typed from Classic:

https://doc.akka.io/docs/akka/2.6/index.html
 
https://doc.akka.io/docs/akka/2.6/typed/from-classic.html

https://discuss.lightbend.com/t/akka-2-6-0-m7-released/5008

AtLeastOnceDelivery with typed-actors: https://gist.github.com/patriknw/514bae62134050f24ca7af95ee977e54

https://doc.akka.io/docs/akka/2.6/project/migration-guide-2.5.x-2.6.x.html


## Cassandra

https://medium.com/@27.rahul.k/cassandra-ttl-intricacies-and-usage-by-examples-d54248f2853c

https://blog.softwaremill.com/7-mistakes-when-using-apache-cassandra-51d2cf6df519


##  Avro

https://avro.apache.org/docs/1.8.2/spec.html#Maps

## Git

git tag -a v0.1.0 -m "v0.1.0" &&  git push --tags

```bash

select persistence_id, partition_nr, sequence_nr, timestamp, ser_id, ser_manifest from safe_chat_journal where persistence_id= 'chat-rooms|aaa' and partition_nr = 0;

 persistence_id | partition_nr | sequence_nr | timestamp                            | ser_id | ser_manifest
----------------+--------------+-------------+--------------------------------------+--------+----------------------------------------------------------------------------
 chat-rooms|aaa |            0 |           1 | db81f100-e120-11e9-8862-59ab458a602d |   9999 |    com.safechat.domain.MsgEnvelope/Joined:1fc4afd458d3777ba86644ac39f51b70
 chat-rooms|aaa |            0 |           2 | df48bd00-e120-11e9-8862-59ab458a602d |   9999 | com.safechat.domain.MsgEnvelope/TextAdded:1fc4afd458d3777ba86644ac39f51b70
 chat-rooms|aaa |            0 |           3 | dfe1c8b0-e120-11e9-8862-59ab458a602d |   9999 | com.safechat.domain.MsgEnvelope/TextAdded:1fc4afd458d3777ba86644ac39f51b70
 chat-rooms|aaa |            0 |           4 | e048a0d0-e120-11e9-8862-59ab458a602d |   9999 | com.safechat.domain.MsgEnvelope/TextAdded:1fc4afd458d3777ba86644ac39f51b70

```
