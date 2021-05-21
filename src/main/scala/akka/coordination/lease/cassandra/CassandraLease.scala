package akka
package coordination.lease.cassandra

import akka.actor.ExtendedActorSystem
import akka.coordination.lease.LeaseSettings
import akka.coordination.lease.scaladsl.Lease
import akka.util.ConstantFun
import com.datastax.oss.driver.api.core.ConsistencyLevel
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException
import com.datastax.oss.driver.api.core.servererrors.WriteType

import java.util.concurrent.atomic.AtomicBoolean
import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.Future
import scala.util.control.NonFatal

/** This implementation can be used for either `akka.sharding.use-lease` or `split-brain-resolver.active-strategy = lease-majority`.
  *
  * https://github.com/haghard/linguistic/blob/1b6bc8af7674982537cf574d3929cea203a2b6fa/server/src/main/scala/linguistic/dao/Accounts.scala
  * https://github.com/dekses/cassandra-lock/blob/master/src/main/java/com/dekses/cassandra/lock/LockFactory.java
  * https://www.datastax.com/blog/consensus-cassandra
  *
  * select * from leases where name = 'safe-chat-akka-sbr';
  */
object CassandraLease {
  val configPath = "akka.coordination.lease.cassandra"
  val SbrPref    = "sbr"
}

final class CassandraLease(system: ExtendedActorSystem, leaseTaken: AtomicBoolean, settings: LeaseSettings)
    extends Lease(settings) {

  def this(leaseSettings: LeaseSettings, system: ExtendedActorSystem) =
    this(system, new AtomicBoolean(false), leaseSettings)

  system.log.debug(s"★ ★ ★ ★ CassandraLease: $settings ★ ★ ★ ★")

  private val cassandraSession = CassandraSessionExtension(system.classicSystem).session

  implicit val ec = cassandraSession.ec

  private val cqlSession = cassandraSession.underlying()

  private val ksName =
    system.settings.config.getString("akka.persistence.cassandra.journal.keyspace")

  /*
  private val forceAcquireTimeout = system.settings.config
    .getDuration("akka.cluster.split-brain-resolver.stable-after")
    .plus(java.time.Duration.ofSeconds(2)) //the more X the safer it becomes
    .asScala
   */

  private val select = SimpleStatement
    .builder(s"SELECT owner FROM $ksName.leases WHERE name = ?")
    .addPositionalValues(settings.leaseName)
    .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    //A SERIAL consistency level allows reading the current (and possibly uncommitted) state of data without proposing a new addition or update.
    //If a SERIAL read finds an uncommitted transaction in progress, the database performs a read repair as part of the commit.
    .setSerialConsistencyLevel(ConsistencyLevel.LOCAL_SERIAL) //for 1 dc
    //.setTracing()
    .build()

  private val insert = SimpleStatement
    .builder(s"INSERT INTO $ksName.leases (name, owner) VALUES (?,?) IF NOT EXISTS")
    .addPositionalValues(settings.leaseName, settings.ownerName)
    .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    .setSerialConsistencyLevel(ConsistencyLevel.LOCAL_SERIAL)
    .build()

  private val forcedInsert = SimpleStatement
    .builder(s"UPDATE $ksName.leases SET owner = ? WHERE name = ? IF owner != null")
    .addPositionalValues(settings.ownerName, settings.leaseName)
    .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    .setSerialConsistencyLevel(ConsistencyLevel.LOCAL_SERIAL)
    .build()

  /*
  private val delete = SimpleStatement
    .builder(s"DELETE FROM $ksName.leases WHERE name = ? IF owner = ?")
    .addPositionalValues(settings.leaseName, settings.ownerName)
    .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    .setSerialConsistencyLevel(ConsistencyLevel.SERIAL)
    .build()
   */

  override def checkLease(): Boolean = false
  //leaseTaken.get()

  // We have TTL and it looks like a more reliable option.
  override def release(): Future[Boolean] =
    Future {
      system.log.warning("***** CassandraLease {} by {} released", settings.leaseName, settings.ownerName)
      true
    }
  /*cqlSession
      .flatMap { cqlSession ⇒
        cqlSession.executeAsync(delete).toScala.map { r ⇒
          val bool = r.wasApplied()
          system.log.info("CassandraLease {} by {} released: {}", settings.leaseName, settings.ownerName, bool)
          bool
        }
      }*/

  override def acquire(): Future[Boolean] =
    acquire(ConstantFun.scalaAnyToUnit)

  /** This implementation gives the following guaranties:
    *   If I grabed the lock, no one should be able to grab it during next `totalFailoverTime` sec
    *   (https://doc.akka.io/docs/akka-enhancements/current/split-brain-resolver.html#expected-failover-time)
    *
    *   If I grabed the lock, others should get back with false as soon as possible.
    *
    *  Total Failover Time = failure detection (~ 5 seconds) + stable-after + down-removal-margin (by default ~ stable-after)
    *  Result = 40 sec in average.
    *
    *  We have TTL = 60 on `leases` table.
    *
    *  If it die right after acquiring (in between acquiring and release), well this is nothing we can do.
    */
  override def acquire(leaseLostCallback: Option[Throwable] ⇒ Unit): Future[Boolean] =
    cqlSession
      .flatMap { cqlSession ⇒
        cqlSession
          .executeAsync(insert)
          .toScala
          .map { rs ⇒
            val bool = rs.wasApplied()
            system.log.warning(s"CassandraLease ${settings.leaseName} by ${settings.ownerName} acquired: $bool")
            bool
          }
      /*.flatMap { r ⇒
            val bool = r.wasApplied()
            system.log.warning(s"CassandraLease ${settings.leaseName} by ${settings.ownerName} acquired: $bool")
            if (bool) Future.successful(bool)
            else
              cqlSession.executeAsync(forcedInsert).toScala.map { r ⇒
                val bool = r.wasApplied()
                system.log
                  .warning(
                    s"CassandraLeaseSbr.Forced ${settings.leaseName} by ${settings.ownerName} acquired: $bool"
                  )
                bool
              }
          }*/
      }
      .recoverWith {
        case e: WriteTimeoutException ⇒
          system.log.error(e, "Cassandra write error :")
          if (e.getWriteType eq WriteType.CAS) {
            //The timeout has happened while doing the compare-and-swap for an conditional update.
            //In this case, the update may or may not have been applied so we try to re-read it.
            cqlSession.flatMap(
              _.executeAsync(select).toScala
                .map { r ⇒
                  val row = r.one()
                  if (row ne null) row.getString("owner") == settings.ownerName else false
                }
            )
          } else Future.successful(false)
        case NonFatal(ex) ⇒
          system.log.error(ex, "CassandraLease {}. Acquire error", settings.leaseName)
          Future.successful(false)
      }

}
