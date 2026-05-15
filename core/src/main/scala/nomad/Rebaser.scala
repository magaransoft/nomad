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

  /** Tags a datasource in error messages so users know which one failed. */
  private enum Role derives CanEqual {
    case Main, Rebase

    def label: String = this match {
      case Main   => "main"
      case Rebase => "rebase"
    }
  }

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

    val (mainUrl, targetDbName)   = readDatasourceMetadata(mainDatasource, Role.Main)
    val (rebaseUrl, rebaseDbName) = readDatasourceMetadata(rebaseDatasource, Role.Rebase)
    val mainServer                = parseSingleHost(mainUrl, Role.Main)
    val rebaseServer              = parseSingleHost(rebaseUrl, Role.Rebase)

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
      // DROP DATABASE and CREATE DATABASE ... WITH TEMPLATE cannot run inside a
      // transaction block. DataSource#getConnection does not guarantee autoCommit
      // is true (pools configured for ORM-style apps often default to false), so
      // force it true for the duration of admin work and restore the borrowed
      // state on the way out — pooled connections returned in a different state
      // than they were borrowed in are a known source of cross-borrow surprises.
      val originalAutoCommit = conn.getAutoCommit
      conn.setAutoCommit(true)
      try
        // ALLOW_CONNECTIONS=false on target closes the gate against the user's
        // app pool refilling connections to target between our terminate and our
        // drop — that race is what would otherwise leave residual sessions and
        // make DROP DATABASE fail with "database 'target' is being accessed by
        // other users".
        //
        // We deliberately do not apply the same trick to the rebase database:
        //   1. Postgres rejects ALTER DATABASE ... ALLOW_CONNECTIONS=false on
        //      the session's own current database (which is rebase, because the
        //      admin connection is borrowed from rebaseDatasource) with
        //      "cannot disallow connections for current database".
        //   2. Conceptually the rebase database is a passive long-lived template
        //      that no developer workflow connects to between rebases, so the
        //      pool-refill race the gate guards against doesn't realistically
        //      arise on the rebase side. We still terminate other sessions on
        //      rebase immediately before CREATE ... TEMPLATE so any opportunistic
        //      stragglers are cleared, but the rebase side remains technically
        //      racy — if your rebase database is being actively connected to
        //      (unusual), close those sessions before invoking nomadRebase.
        withAllowConnectionsDisabled(conn, targetDbName) {
          terminateOtherSessions(conn, targetDbName)
          dropDatabaseIfExists(conn, targetDbName)
          terminateOtherSessions(conn, rebaseDbName)
          createDatabaseFromTemplate(conn, targetDbName, rebaseDbName)
          // The new target inherits ALLOW_CONNECTIONS from the rebase template,
          // which we never touched (still true). The surrounding `finally`
          // re-runs ALTER ... ALLOW_CONNECTIONS=true on target — a no-op in the
          // happy path, load-bearing if CREATE failed and the freshly-dropped
          // target was somehow recreated by something else in a non-true state.
        }
      finally
        try conn.setAutoCommit(originalAutoCommit)
        catch {
          case e: java.sql.SQLException =>
            logger.warn(
              s"Could not restore autoCommit=$originalAutoCommit on rebase admin connection: ${e.getMessage}"
            )
        }
    }
    logger.info(
      s"Rebase complete: database '$targetDbName' is now a fresh clone of '$rebaseDbName'."
    )
  }

  /** Returns `(jdbcUrl, currentDatabaseName)` read from a short-lived connection on `ds`. */
  private def readDatasourceMetadata(ds: DataSource, role: Role): (String, String) = {
    try
      Using.resource(ds.getConnection) { conn =>
        val url = conn.getMetaData.getURL
        if (url == null || url.isEmpty) {
          throw new IllegalStateException(
            s"${role.label} datasource did not expose a JDBC URL via getMetaData.getURL."
          )
        }
        val catalog = conn.getCatalog
        if (catalog == null || catalog.isEmpty) {
          throw new IllegalStateException(
            s"${role.label} datasource did not expose its database name via Connection.getCatalog."
          )
        }
        (url, catalog)
      }
    catch {
      case e: IllegalStateException => throw e
      case e: java.sql.SQLException =>
        val hint = role match {
          case Role.Main =>
            "For a rebase, the target database must already exist (Nomad does not create databases)."
          case Role.Rebase =>
            "Check that the rebase database exists and the rebaseDatasource configuration is correct."
        }
        throw new IllegalStateException(
          s"Could not connect to ${role.label} datasource. $hint Underlying error: ${e.getMessage}",
          e
        )
    }
  }

  /** Parses a single `host:port` (or bare `host`) from a Postgres JDBC URL.
    * Rejects non-Postgres URLs and multi-host failover URLs.
    */
  private def parseSingleHost(url: String, role: Role): String = {
    val prefix = "jdbc:postgresql://"
    if (!url.startsWith(prefix)) {
      throw new IllegalArgumentException(
        s"${role.label} datasource is not a Postgres JDBC URL (got: $url). nomadRebase requires Postgres."
      )
    }
    val afterPrefix = url.substring(prefix.length)
    val authority   = afterPrefix.takeWhile(c => c != '/' && c != '?')
    if (authority.contains(',')) {
      throw new IllegalArgumentException(
        s"${role.label} datasource uses a multi-host JDBC URL ($url). nomadRebase requires a single-host URL."
      )
    }
    if (authority.isEmpty) {
      throw new IllegalArgumentException(
        s"${role.label} datasource URL does not include a host ($url)."
      )
    }
    authority
  }

  /** Runs `body` with `ALLOW_CONNECTIONS=false` set on `dbName`, restoring `true`
    * in a `finally` block. The restore swallows SQL errors (logged at warn) so
    * the body's exception is not masked — e.g., if `body` dropped the database
    * outright and the restore can't find it. The new database created from a
    * template inherits `ALLOW_CONNECTIONS=false` from the template, so a guard
    * scoped to the target name covers the post-CREATE restore as well.
    */
  private def withAllowConnectionsDisabled[A](conn: Connection, dbName: String)(body: => A): A = {
    setAllowConnections(conn, dbName, allow = false)
    try body
    finally
      try setAllowConnections(conn, dbName, allow = true)
      catch {
        case e: java.sql.SQLException =>
          logger.warn(
            s"Could not restore ALLOW_CONNECTIONS=true on database '$dbName' " +
              s"(likely dropped or unreachable): ${e.getMessage}"
          )
      }
  }

  private def setAllowConnections(conn: Connection, dbName: String, allow: Boolean): Unit = {
    Using.resource(conn.createStatement()) { stmt =>
      val _ = stmt.execute(
        s"""ALTER DATABASE "${escapeIdentifier(dbName)}" WITH ALLOW_CONNECTIONS ${if (allow) "true" else "false"}"""
      )
    }
  }

  private def terminateOtherSessions(conn: Connection, dbName: String): Unit = {
    Using.resource(
      conn.prepareStatement(
        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = ? AND pid <> pg_backend_pid()"
      )
    ) { ps =>
      ps.setString(1, dbName)
      Using.resource(ps.executeQuery()) { rs =>
        // pg_terminate_backend returns false when the PID exited between the
        // SELECT and the signal — count only true returns so the log reflects
        // what was actually killed, and warn separately on the race.
        var succeeded = 0
        var failed    = 0
        while (rs.next()) {
          if (rs.getBoolean(1)) succeeded += 1 else failed += 1
        }
        if (succeeded > 0) {
          logger.info(s"Terminated $succeeded other session(s) on database '$dbName'.")
        }
        if (failed > 0) {
          logger.warn(
            s"Failed to terminate $failed session(s) on database '$dbName' " +
              s"(likely already exited between selection and signal)."
          )
        }
      }
    }
  }

  private def dropDatabaseIfExists(conn: Connection, dbName: String): Unit = {
    if (databaseExists(conn, dbName)) {
      Using.resource(conn.createStatement()) { stmt =>
        // IF EXISTS retained for the small race window between the pg_database
        // check above and the DROP hitting the server.
        val _ = stmt.execute(s"""DROP DATABASE IF EXISTS "${escapeIdentifier(dbName)}"""")
      }
      logger.info(s"Dropped database '$dbName'.")
    } else {
      logger.info(s"Database '$dbName' did not exist; skipping drop.")
    }
  }

  private def databaseExists(conn: Connection, dbName: String): Boolean = {
    Using.resource(conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) { ps =>
      ps.setString(1, dbName)
      Using.resource(ps.executeQuery())(_.next())
    }
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
