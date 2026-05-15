package nomad

import java.sql.Connection
import javax.sql.DataSource
import org.slf4j.LoggerFactory
import scala.util.Using

/** Rebases a target Postgres database from a long-lived "rebase" template database
  * on the same Postgres cluster, using a fast file-level template copy.
  *
  * The rebase workflow is designed for developers who maintain a long-lived rebase
  * database at a known-good point in time (e.g., a restored production backup with
  * most migrations already applied). When their working database becomes
  * contaminated or needs to be reset, [[rebase]] drops it and re-clones it from
  * the rebase database via `CREATE DATABASE target WITH TEMPLATE rebase`. Any
  * pending migrations can then be applied on top via [[Migrator.migrate]].
  *
  * The copy is a Postgres file-level operation: it does not re-execute schema or
  * data DDL, so it is dramatically faster than restoring from a logical dump.
  *
  * '''Destructive''': this drops the entire target database. The caller is
  * responsible for any confirmation prompting.
  *
  * Constraints (all enforced by [[rebase]] and reported as exceptions):
  *   - Database type must be [[SupportedDatabase.Postgres]] (H2 is rejected).
  *   - Both datasources must connect to the same Postgres cluster — the host
  *     and port parsed from each JDBC URL must match exactly.
  *   - Multi-host failover JDBC URLs are rejected.
  *   - The target database must already exist (Nomad does not create databases).
  *   - Target and rebase database names must differ.
  *
  * @param mainDatasource the working datasource — its target database is dropped
  *                       and recreated from the rebase template
  * @param rebaseDatasource the rebase datasource — never dropped or modified;
  *                         used both as the template source and as the connection
  *                         from which `DROP DATABASE` and `CREATE DATABASE` are
  *                         issued (these statements cannot be issued from a
  *                         connection on the target database itself)
  * @param db the database type (must be Postgres)
  */
class Rebaser(
  mainDatasource: DataSource,
  rebaseDatasource: DataSource,
  db: SupportedDatabase
) {

  private val logger = LoggerFactory.getLogger(classOf[Rebaser])

  /** Drops the target database and recreates it as a fast file-level clone of the
    * rebase database.
    *
    * Sequence:
    *   1. Verify Postgres, parse host:port from each JDBC URL, fail on mismatch.
    *   2. Resolve target and rebase database names via `Connection.getCatalog`.
    *   3. From a single connection on the rebase datasource:
    *      - terminate other sessions on the target database,
    *      - `DROP DATABASE IF EXISTS target`,
    *      - terminate other sessions on the rebase database (required because
    *        Postgres rejects `CREATE DATABASE ... TEMPLATE` if any other session
    *        is connected to the template source),
    *      - `CREATE DATABASE target WITH TEMPLATE rebase`.
    *
    * The issuing session is excluded from the terminate step via
    * `pg_backend_pid()`; Postgres permits the template source to have the
    * issuing session connected during the copy as long as no other sessions
    * are present.
    *
    * @throws java.lang.IllegalArgumentException if `db` is not Postgres, the datasources
    *                                            are on different servers, a URL is
    *                                            multi-host or non-Postgres, or target ==
    *                                            rebase
    * @throws java.lang.IllegalStateException if the target database cannot be reached
    *                                         (typically because it does not yet exist)
    */
  def rebase(): Unit = {
    db match {
      case SupportedDatabase.Postgres => ()
      case other =>
        throw new IllegalArgumentException(
          s"nomadRebase requires Postgres, got: $other"
        )
    }

    val (mainUrl, targetDbName)     = readDatasourceMetadata(mainDatasource, role = "main")
    val (rebaseUrl, rebaseDbName)   = readDatasourceMetadata(rebaseDatasource, role = "rebase")
    val mainServer                  = parseSingleHost(mainUrl, role = "main")
    val rebaseServer                = parseSingleHost(rebaseUrl, role = "rebase")

    if (mainServer != rebaseServer) {
      throw new IllegalArgumentException(
        s"Main and rebase datasources must be on the same Postgres server. " +
          s"Main is on '$mainServer', rebase is on '$rebaseServer'."
      )
    }

    if (targetDbName == rebaseDbName) {
      throw new IllegalArgumentException(
        s"Target and rebase databases must differ (both resolved to '$targetDbName'). " +
          s"Refusing to template a database from itself."
      )
    }

    logger.info(
      s"Rebasing target database '$targetDbName' from rebase database '$rebaseDbName' on $mainServer."
    )

    Using.resource(rebaseDatasource.getConnection) { conn =>
      terminateOtherSessions(conn, targetDbName)
      dropDatabaseIfExists(conn, targetDbName)
      terminateOtherSessions(conn, rebaseDbName)
      createDatabaseFromTemplate(conn, targetDbName, rebaseDbName)
    }
    logger.info(
      s"Rebase complete: database '$targetDbName' is now a fresh clone of '$rebaseDbName'."
    )
  }

  /** Returns `(jdbcUrl, currentDatabaseName)` read from a short-lived connection on `ds`. */
  private def readDatasourceMetadata(ds: DataSource, role: String): (String, String) = {
    try
      Using.resource(ds.getConnection) { conn =>
        val url = conn.getMetaData.getURL
        if (url == null || url.isEmpty) {
          throw new IllegalStateException(
            s"$role datasource did not expose a JDBC URL via getMetaData.getURL."
          )
        }
        val catalog = conn.getCatalog
        if (catalog == null || catalog.isEmpty) {
          throw new IllegalStateException(
            s"$role datasource did not expose its database name via Connection.getCatalog."
          )
        }
        (url, catalog)
      }
    catch {
      case e: IllegalStateException => throw e
      case e: java.sql.SQLException =>
        throw new IllegalStateException(
          s"Could not connect to $role datasource. For a rebase, the target database must already exist " +
            s"(Nomad does not create databases). Underlying error: ${e.getMessage}",
          e
        )
    }
  }

  /** Parses a single `host:port` (or bare `host`) from a Postgres JDBC URL.
    * Rejects non-Postgres URLs and multi-host failover URLs.
    */
  private def parseSingleHost(url: String, role: String): String = {
    val prefix = "jdbc:postgresql://"
    if (!url.startsWith(prefix)) {
      throw new IllegalArgumentException(
        s"$role datasource is not a Postgres JDBC URL (got: $url). nomadRebase requires Postgres."
      )
    }
    val afterPrefix = url.substring(prefix.length)
    val authority   = afterPrefix.takeWhile(c => c != '/' && c != '?')
    if (authority.contains(',')) {
      throw new IllegalArgumentException(
        s"$role datasource uses a multi-host JDBC URL ($url). nomadRebase requires a single-host URL."
      )
    }
    if (authority.isEmpty) {
      throw new IllegalArgumentException(
        s"$role datasource URL does not include a host ($url)."
      )
    }
    authority
  }

  private def terminateOtherSessions(conn: Connection, dbName: String): Unit = {
    Using.resource(
      conn.prepareStatement(
        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = ? AND pid <> pg_backend_pid()"
      )
    ) { ps =>
      ps.setString(1, dbName)
      Using.resource(ps.executeQuery()) { rs =>
        var terminated = 0
        while (rs.next()) terminated += 1
        if (terminated > 0) {
          logger.info(s"Terminated $terminated other session(s) on database '$dbName'.")
        }
      }
    }
  }

  private def dropDatabaseIfExists(conn: Connection, dbName: String): Unit = {
    Using.resource(conn.createStatement()) { stmt =>
      val _ = stmt.execute(s"""DROP DATABASE IF EXISTS "${escapeIdentifier(dbName)}"""")
    }
    logger.info(s"Dropped database '$dbName'.")
  }

  private def createDatabaseFromTemplate(conn: Connection, target: String, template: String): Unit = {
    Using.resource(conn.createStatement()) { stmt =>
      val _ = stmt.execute(
        s"""CREATE DATABASE "${escapeIdentifier(target)}" WITH TEMPLATE "${escapeIdentifier(template)}""""
      )
    }
    logger.info(s"Created database '$target' from template '$template'.")
  }

  /** Doubles double-quote characters so an identifier can be safely embedded inside `"..."`. */
  private def escapeIdentifier(id: String): String = id.replace("\"", "\"\"")
}
