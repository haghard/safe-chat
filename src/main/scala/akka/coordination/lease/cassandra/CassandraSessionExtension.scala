package akka.coordination.lease.cassandra

import akka.actor.{ActorSystem, ClassicActorSystemProvider, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession

import scala.concurrent.duration._

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

    val cassandraSession = PersistenceQuery(system)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
      .session

    //TODO: avoid blocking
    scala.concurrent.Await.result(
      akka.pattern.retry(() ⇒ createLeaseTable(cassandraSession), 10)(system.dispatcher),
      Duration.Inf
    )
    cassandraSession
  }

  private def createLeaseTable(cassandraSession: CassandraSession) = {
    val stmt = s"CREATE TABLE IF NOT EXISTS $keyspace.leases (name text PRIMARY KEY, owner text)"
    cassandraSession executeDDL stmt
  }
}
