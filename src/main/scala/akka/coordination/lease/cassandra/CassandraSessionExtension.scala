package akka.coordination.lease.cassandra

import akka.actor.{ActorSystem, ClassicActorSystemProvider, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession

import scala.concurrent.duration._
import scala.util.control.NonFatal

object CassandraSessionExtension extends ExtensionId[CassandraSessionExtension] with ExtensionIdProvider {

  override def get(system: ActorSystem): CassandraSessionExtension = super.get(system)

  override def get(system: ClassicActorSystemProvider): CassandraSessionExtension = super.get(system)

  override def lookup = CassandraSessionExtension

  override def createExtension(system: ExtendedActorSystem): CassandraSessionExtension =
    new CassandraSessionExtension(system)
}

class CassandraSessionExtension(system: ActorSystem) extends Extension {

  val retryTimeout = 2.second
  //val logger       = akka.event.Logging(system, getClass)

  val keyspace = system.settings.config.getString("akka.persistence.cassandra.journal.keyspace")

  lazy val session: CassandraSession = {
    implicit val ec = system.dispatcher

    val cassandraSession = PersistenceQuery(system)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
      .session

    val f = createLeaseTable(cassandraSession).recoverWith { case NonFatal(ex) ⇒
      system.log.error("CreateLeaseTable error", ex)
      akka.pattern.after(retryTimeout, system.scheduler)(createLeaseTable(cassandraSession))
    }

    //TODO: avoid blocking
    scala.concurrent.Await.result(f, Duration.Inf)
    cassandraSession
  }

  private def createLeaseTable(cassandraSession: CassandraSession) = {
    val ttl = system.settings.config
      .getDuration("akka.cluster.split-brain-resolver.stable-after")
      .getSeconds * 3

    /*

      CREATE TABLE leases1 (name text, when timeuuid, rooms set<text> static, PRIMARY KEY(name, when)) WITH CLUSTERING ORDER BY (when DESC);

      INSERT INTO leases1(name, when, rooms) VALUES ('p.0', now(), {});


      UPDATE leases1 SET rooms = rooms + {'aaa'} WHERE name = 'p.0' IF rooms = null;
      UPDATE leases1 SET rooms = rooms + {'b'}   WHERE name = 'p.0' IF rooms != null;


      UPDATE leases1 SET rooms = rooms + {'b'} WHERE name = 'p.0' IF rooms IN { 'a' };
      UPDATE leases1 SET rooms = rooms + {'b'} WHERE name = 'p.0' AND rooms NOT IN { 'a' } IF NOT EXIST;

      SELECT * FROM leases1 WHERE name = 'p.0' AND rooms CONTAINS 'a';


      SELECT * FROM leases1 WHERE name = 'p.0' AND rooms CONTAINS 'aaa' ALLOW FILTERING;

      CREATE INDEX leases_rooms_indx ON leases1(rooms);
      SELECT * FROM leases1 WHERE name = 'p.0' AND rooms CONTAINS 'aaa';


      SELECT * FROM leases1 WHERE name = 'p.0' AND rooms CONTAINS 'aaa';


      UPDATE leases1 SET rooms = rooms + {'c'} WHERE name = 'p.0' AND rooms CONTAINS 'c' IF NOT EXIST;
                                               WHERE name = 'p.0' AND rooms CONTAINS 'c' IF NOT EXIST




    #SELECT TTL(race_name) FROM cycling.calendar  WHERE race_id=200;

     */

    //CREATE TABLE leases0 (name text PRIMARY KEY, owner text, when timeuuid);
    //SELECT writetime(owner) from leases0 where name = 'a';

    //INSERT into leases0 (name, owner, when) VALUES ('a', '1', now());
    //UPDATE leases0 set owner = '2'               WHERE name = 'a' IF when > now();
    //UPDATE leases0 set owner = '3', when = now() WHERE name = 'a' IF when < now();

    val createStatement =
      //s"CREATE TABLE IF NOT EXISTS $keyspace.leases (name text PRIMARY KEY, owner text) with default_time_to_live = $ttl"
      s"CREATE TABLE IF NOT EXISTS $keyspace.leases (name text PRIMARY KEY, owner text)"
    cassandraSession executeDDL createStatement
  }
}
