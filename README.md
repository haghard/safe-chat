# End-to-End encrypted chat

# Main idea
We want to have a MergeHub followed by a BroadcastHub to achieve dynamic fan-in and fan-out (many-to-many) per chat room in combination with StreamRefs to get long-running streams of data between two entities over the network. 

https://github.com/wiringbits/safer.chat 

ws://192.168.77.10:9000/chat/aaa/user/harry


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
ws://85.119.150.35:8080/chat/aaa/user/charley
ws://188.68.210.125:8080/chat/aaa/user/charley



http GET 188.68.210.125:8080/cluster/shards/chat-rooms

Executes leave operation in cluster for provided {address}
http DELETE 188.68.210.125:8080/cluster/members/akka://echatter@85.119.150.35:2551


```


ws://95.213.236.45:8080/chat/aaa/user/harry
ws://46.21.248.170:8080/chat/aaa/user/harry


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

CRDT
https://github.com/cloudstateio/cloudstate/blob/master/docs/src/main/paradox/user/features/crdts.md

Cassandra

https://medium.com/@27.rahul.k/cassandra-ttl-intricacies-and-usage-by-examples-d54248f2853c
https://blog.softwaremill.com/7-mistakes-when-using-apache-cassandra-51d2cf6df519


```bash

select persistence_id, partition_nr, sequence_nr, timestamp, ser_id, ser_manifest from chat_journal where persistence_id= 'chat-rooms|703c1ae555da3cd4' and partition_nr = 0;

persistence_id              | partition_nr | sequence_nr | timestamp                            | ser_id | ser_manifest
-----------------------------+--------------+-------------+--------------------------------------+--------+----------------------------------------------------------------------------
chat-rooms|703c1ae555da3cd4 |            0 |           1 | 50c1a460-dfa0-11e9-a486-5d0eb9688ce0 |   9999 |    com.safechat.domain.MsgEnvelope/Joined:8502517e2598f7913c22e81ae257f66a
chat-rooms|703c1ae555da3cd4 |            0 |           2 | 567d1470-dfa0-11e9-a486-5d0eb9688ce0 |   9999 | com.safechat.domain.MsgEnvelope/TextAdded:8502517e2598f7913c22e81ae257f66a
chat-rooms|703c1ae555da3cd4 |            0 |           3 | 56e59a40-dfa0-11e9-a486-5d0eb9688ce0 |   9999 | com.safechat.domain.MsgEnvelope/TextAdded:8502517e2598f7913c22e81ae257f66a
chat-rooms|703c1ae555da3cd4 |            0 |           4 | 573b5b60-dfa0-11e9-a486-5d0eb9688ce0 |   9999 | com.safechat.domain.MsgEnvelope/TextAdded:8502517e2598f7913c22e81ae257f66a
chat-rooms|703c1ae555da3cd4 |            0 |           5 | 577249e0-dfa0-11e9-a486-5d0eb9688ce0 |   9999 | com.safechat.domain.MsgEnvelope/TextAdded:8502517e2598f7913c22e81ae257f66a


```
