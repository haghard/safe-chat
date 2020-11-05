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

### Start message

```

[info] =================================================================================================
[info] ★ ★ ★   Node 127.0.0.1:2550   ★ ★ ★
[info] ★ ★ ★   Seed nodes: [akka://safe-chat@127.0.0.1:2550, akka://safe-chat@127.0.0.2:2550]  ★ ★ ★
[info] ★ ★ ★   Cassandra: 84.201.150.26:9042,84.201.146.112:9042   Journal partition size: 500000 ★ ★ ★
[info] ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★  Schema mapping ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★
[info] com.safechat.actors.UserTextAdded -> com.safechat.persistent.domain.UserTextAdded
[info] com.safechat.actors.UserDisconnected -> com.safechat.persistent.domain.UserDisconnected
[info] com.safechat.actors.UserJoined -> com.safechat.persistent.domain.UserJoined
[info] ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★ ★
[info] ★ ★ ★   Environment: [TZ:Europe/Amsterdam. Start time:2020-10-11T...]  ★ ★ ★
[info] ★ ★ ★   HTTP server is online: http://127.0.0.1:8080 ★ ★ ★ 
[info]                                 ___  ____   ___  __   __  ___   ___     ______
[info]                                / __| | __| | _ \ \ \ / / | __| | _ \    \ \ \ \
[info]                                \__ \ | _|  |   /  \ V /  | _|  |   /     ) ) ) )
[info]                                |___/ |___| |_|_\   \_/   |___| |_|_\    /_/_/_/
[info]         
[info] ★ ★ ★  Artery: maximum-frame-size: 262144 bytes  ★ ★ ★
[info] Version:0.3.0-SNAPSHOT at 2020-10-11 17:25:15.310+0200
[info] Cores:8 Total Memory:268Mb Max Memory:1073Mb Free Memory:197Mb RAM:17179Mb
[info] =================================================================================================

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

http 127.0.0.1:8080/cluster/members "Authorization:Basic QWxhZGRpbjpPcGVuU2VzYW1l"
http 127.0.0.1:8558/cluster/members "Authorization:Basic QWxhZGRpbjpPcGVuU2VzYW1l"

http 127.0.0.1:8558/bootstrap/seed-nodes "Authorization:Basic QWxhZGRpbjpPcGVuU2VzYW1l"


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


## Message evolution/versioning

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

Schema Management at Depop: https://youtu.be/ztkyVHPaPgY

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

Schema registry:    https://www.confluent.io/blog/author/robert-yokota/

Putting Several Event Types in the Same Topic – Revisited:  https://www.confluent.io/blog/multiple-event-types-in-the-same-kafka-topic



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

### Distributed state blog series 

https://github.com/michael-read/akka-typed-distributed-state-blog


https://github.com/chbatey/akka-talks/blob/f3babead55fa7e678ce21dcbf780e9423afb7448/http-streams/src/test/scala/info/batey/akka/http/ApplyLoad.scala
https://whiteprompt.com/scala/stress-test-restful-service-gatling/
https://sysgears.com/articles/restful-service-load-testing-using-gatling-2/
https://github.com/satyriasizz/gatling2-load-test-example


## Jvm inside a docker container

https://blog.csanchez.org/2017/05/31/running-a-jvm-in-a-container-without-getting-killed/
https://blog.csanchez.org/2018/06/21/running-a-jvm-in-a-container-without-getting-killed-ii/

http://www.batey.info/docker-jvm-k8s.html
https://github.com/chbatey/docker-jvm-akka
https://blog.docker.com/2018/12/top-5-post-docker-container-java/
https://efekahraman.github.io/2018/04/docker-awareness-in-java

### CPU considerations for Java applications running in Docker and Kubernetes

https://www.lightbend.com/blog/cpu-considerations-for-java-applications-running-in-docker-and-kubernetes


### On kubernetes

https://blog.softwaremill.com/running-akka-cluster-on-kubernetes-e4cd2913e951

## Videos

How to use CQRS in Akka 2.6 https://www.youtube.com/watch?v=6ECsFlNNIAM

Introduction To Akka Cluster Sharding https://youtu.be/SrPubnOKJcQ

How to do distributed, stateful processing with #Akka Cluster Sharding and Kafka https://akka.io/blog/news/2020/03/18/akka-sharding-kafka-video

Stateful OR Stateless Applications: To Akka Cluster, Or Not https://www.youtube.com/watch?v=CiVsKjZV-Ys

Split Brain Resolver in Akka Cluster https://www.youtube.com/watch?v=vc6eTolxGbM


### SBT

```bash

sbt '; set javaOptions += "-Dconfig.resource=cluster-application.conf" ; run’

sbt -J-XX:MaxMetaspaceSize=512M -J-XX:+PrintCommandLineFlags -J-XshowSettings

sbt -J-Xms512M -J-XX:+PrintCommandLineFlags -J-XX:NativeMemoryTracking=summary -J-XshowSettings

```

OR

Create .sbtopts


### Examples
  
Killrweather:             https://github.com/akka/akka-samples/tree/2.6/akka-sample-sharding-scala

Persistent shopping cart: https://github.com/akka/akka-samples/tree/2.6/akka-sample-persistence-scala

DData example:            https://github.com/akka/akka-samples/tree/2.6/akka-sample-distributed-data-scala

CQRS ShoppingCart example: read-side is implemented using Akka Projections: https://github.com/akka/akka-samples/tree/2.6/akka-sample-cqrs-scala

Akka Persistence and MariaDB:  https://medium.com/@matteodipirro/stateful-actors-with-akka-event-sourcing-and-maria-db-d4202c6c599a


```bash

select persistence_id, partition_nr, sequence_nr, timestamp, ser_id, ser_manifest from chat_journal where persistence_id='chat-rooms|aaa' and partition_nr = 0;

 chat-room|703c1ae555da3cd4 |            0 |           1 | 6a8b6c60-7be9-11ea-96e6-9f6061501887 |   9999 |       com.safechat.domain.MsgEnvelope/Joined:b936961c182c4389a3f88ba780575915
 chat-room|703c1ae555da3cd4 |            0 |           2 | 7ac552d0-7be9-11ea-96e6-9f6061501887 |   9999 | com.safechat.domain.MsgEnvelope/Disconnected:b936961c182c4389a3f88ba780575915
 chat-room|703c1ae555da3cd4 |            0 |           3 | 7b848420-7be9-11ea-96e6-9f6061501887 |   9999 |       com.safechat.domain.MsgEnvelope/Joined:b936961c182c4389a3f88ba780575915
 chat-room|703c1ae555da3cd4 |            0 |           4 | 7cfa8250-7be9-11ea-96e6-9f6061501887 |   9999 |       com.safechat.domain.MsgEnvelope/Joined:b936961c182c4389a3f88ba780575915
 chat-room|703c1ae555da3cd4 |            0 |           5 | 7fdfc7f0-7be9-11ea-96e6-9f6061501887 |   9999 |       com.safechat.domain.MsgEnvelope/Joined:b936961c182c4389a3f88ba780575915
 chat-room|703c1ae555da3cd4 |            0 |           6 | 8345af40-7be9-11ea-96e6-9f6061501887 |   9999 |       com.safechat.domain.MsgEnvelope/Joined:b936961c182c4389a3f88ba780575915
 chat-room|703c1ae555da3cd4 |            0 |           7 | 86575c10-7be9-11ea-96e6-9f6061501887 |   9999 |       com.safechat.domain.MsgEnvelope/Joined:b936961c182c4389a3f88ba780575915
 chat-room|703c1ae555da3cd4 |            0 |           8 | 89d11980-7be9-11ea-96e6-9f6061501887 |   9999 |       com.safechat.domain.MsgEnvelope/Joined:b936961c182c4389a3f88ba780575915
 chat-room|703c1ae555da3cd4 |            0 |           9 | 8d6a93f0-7be9-11ea-96e6-9f6061501887 |   9999 |       com.safechat.domain.MsgEnvelope/Joined:b936961c182c4389a3f88ba780575915

```


### Links to read

https://doc.akka.io/docs/akka/current/typed/actors.html#a-more-complex-example
https://github.com/akka/akka-samples/tree/2.6/akka-sample-sharding-scala

https://github.com/apache/cassandra/blob/trunk/src/java/org/apache/cassandra/dht/IPartitioner.java


###

http GET :8558/cluster/members

Find the PID for the unreachable node:
> lsof -i :2551 | grep LISTEN | awk '{print $2}'

Hard kill
> kill -9 <pid>

Suspend
> kill -stop <pid>

Resume
> kill -cont <pid>


curl -w '\n' -X PUT -H 'Content-Type: multipart/form-data' -F operation=down http://localhost:8080/cluster/members/safe-chat@127.0.0.1:2550

curl -w '\n' -X PUT -H 'Content-Type: multipart/form-data' -F operation=leave http://localhost:8080/cluster/members/safe-chat@127.0.0.1:2550


### Akka-cluster-sharding links 

https://manuel.bernhardt.io/2018/02/26/tour-akka-cluster-cluster-sharding/

https://www.youtube.com/watch?v=SrPubnOKJcQ

https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html


### Cassandra journal schema

```

CREATE KEYSPACE chat WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '3'}  AND durable_writes = true;

CREATE TABLE chat.all_persistence_ids (
    persistence_id text PRIMARY KEY
) WITH bloom_filter_fp_chance = 0.01
    AND caching = {'keys': 'ALL', 'rows_per_partition': 'NONE'}
    AND comment = ''
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy', 'max_threshold': '32', 'min_threshold': '4'}
    AND compression = {'chunk_length_in_kb': '64', 'class': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND crc_check_chance = 1.0
    AND dclocal_read_repair_chance = 0.1
    AND default_time_to_live = 0
    AND gc_grace_seconds = 864000
    AND max_index_interval = 2048
    AND memtable_flush_period_in_ms = 0
    AND min_index_interval = 128
    AND read_repair_chance = 0.0
    AND speculative_retry = '99PERCENTILE';

CREATE TABLE chat.chat_journal (
    persistence_id text,
    partition_nr bigint,
    sequence_nr bigint,
    timestamp timeuuid,
    event blob,
    event_manifest text,
    meta blob,
    meta_ser_id int,
    meta_ser_manifest text,
    ser_id int,
    ser_manifest text,
    tags set<text>,
    timebucket text,
    writer_uuid text,
    PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp)
) WITH CLUSTERING ORDER BY (sequence_nr ASC, timestamp ASC)
    AND bloom_filter_fp_chance = 0.01
    AND caching = {'keys': 'ALL', 'rows_per_partition': 'NONE'}
    AND comment = ''
    AND compaction = {'bucket_high': '1.5', 'bucket_low': '0.5', 'class': 'org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy', 'enabled': 'true', 'max_threshold': '32', 'min_sstable_size': '50', 'min_threshold': '4', 'tombstone_compaction_interval': '86400', 'tombstone_threshold': '0.2', 'unchecked_tombstone_compaction': 'false'}
    AND compression = {'chunk_length_in_kb': '64', 'class': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND crc_check_chance = 1.0
    AND dclocal_read_repair_chance = 0.1
    AND default_time_to_live = 0
    AND gc_grace_seconds = 864000
    AND max_index_interval = 2048
    AND memtable_flush_period_in_ms = 0
    AND min_index_interval = 128
    AND read_repair_chance = 0.0
    AND speculative_retry = '99PERCENTILE';

CREATE TABLE chat.metadata (
    persistence_id text PRIMARY KEY,
    deleted_to bigint,
    properties map<text, text>
) WITH bloom_filter_fp_chance = 0.01
    AND caching = {'keys': 'ALL', 'rows_per_partition': 'NONE'}
    AND comment = ''
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy', 'max_threshold': '32', 'min_threshold': '4'}
    AND compression = {'chunk_length_in_kb': '64', 'class': 'org.apache.cassandra.io.compress.LZ4Compressor'}
    AND crc_check_chance = 1.0
    AND dclocal_read_repair_chance = 0.1
    AND default_time_to_live = 0
    AND gc_grace_seconds = 864000
    AND max_index_interval = 2048
    AND memtable_flush_period_in_ms = 0
    AND min_index_interval = 128
    AND read_repair_chance = 0.0
    AND speculative_retry = '99PERCENTILE';

```


### Git

git tag -a v0.2.0 -m "v0.2.0" &&  git push --tags

### TO DO 

Add two roles: endpoint and domain. If the role of the cluster node is “domain” we simply start cluster sharding, 
otherwise we initialize cluster sharding as a proxy so no shards are hosted by the node and start HTTP endpoints. 
(See how it's done for https://www.lightbend.com/blog/how-to-distribute-application-state-with-akka-cluster-part-4-the-source-code)
Use `streamee` to enable streaming from http routes to shard region proxies. 


Enable replicated-event-sourcing 
https://doc.akka.io/docs/akka/current/typed/replicated-eventsourcing.html#replicated-event-sourcing
https://github.com/akka/akka-samples.git akka-sample-persistence-dc-scala


Sharding improvements(2.6.10): https://doc.akka.io/docs/akka/2.6/additional/rolling-updates.html