package nomad

import java.security.MessageDigest
import java.sql.{Connection, Timestamp}
import java.time.Instant
import javax.sql.DataSource
import org.slf4j.LoggerFactory
import scala.io.Source
import scala.util.Using

/** Executes database migrations in the order defined by the manifest.
  *
  * The migrator tracks applied migrations in a history table, validates
  * checksums to detect modifications to already-applied migrations,
  * and runs each pending SQL migration within its own transaction.
  *
  * @param datasource the datasource used to connect to the target database
  * @param db the target database type, used for database-specific DDL
  * @param migrations the ordered list of migrations to apply
  * @param historyTable the name of the table used to track applied migrations
  * @param schema the database schema to use for migrations and history tracking
  */
class Migrator(
  datasource: DataSource,
  db: SupportedDatabase,
  migrations: Vector[Migration],
  historyTable: String = "nomad_migrations",
  schema: String = "public"
) {

  final case class FlywayImportAnalysis(
    exactMatchCount: Int,
    remappableMismatchCount: Int,
    missingCount: Int,
    ambiguousCount: Int
  ) {
    def hasOnlyPathMismatches: Boolean =
      remappableMismatchCount > 0 && missingCount == 0 && ambiguousCount == 0
  }

  private case class FlywayHistoryRow(
    script: String,
    description: String,
    installedBy: String,
    installedOn: Timestamp,
    executionTimeMs: Long
  )

  private enum FlywayImportResolution derives CanEqual {
    case Exact(rank: Int, resourcePath: String)
    case Remapped(rank: Int, resourcePath: String)
    case Missing
    case Ambiguous
  }

  private val logger = LoggerFactory.getLogger(classOf[Migrator])

  /** Runs all pending migrations.
    *
    * Creates the history table if it does not exist, validates checksums
    * and ordering of previously applied migrations, then executes each
    * pending migration in its own transaction.
    *
    * @throws RuntimeException if a checksum mismatch is detected, a migration
    *                          is missing, not in the manifest, out of order,
    *                          or execution fails
    */
  def migrate(): Unit = {
    val conn = datasource.getConnection
    try {
      setSchemaIfNeeded(conn)
      ensureHistoryTable(conn)
      val applied = loadApplied(conn)
      val pendingMigrations = migrations.drop(applied.size)
      validateApplied(applied, pendingMigrations)

      if (pendingMigrations.isEmpty) {
        logger.info("No pending migrations.")
        return
      }

      // Compute starting point and pending display names for table layout
      val startingPoint = if (applied.nonEmpty) {
        val lastAppliedName = applied.last.name
        val lastManifestEntry = migrations.lift(applied.size - 1)
        Some(lastManifestEntry.map(displayNameOfAny).getOrElse(StatusTable.extractDisplayName(lastAppliedName)))
      } else None
      val pendingInfo = pendingMigrations.map(m => (displayNameOfAny(m), isTransactional(m)))
      val printer = StatusTable.migrateTablePrinter(startingPoint, pendingInfo, logger)

      startingPoint.foreach(printer.printStartingPoint)

      for ((migration, idx) <- pendingMigrations.zipWithIndex) {
        val (migrationName, checksum, migrationType) = migration match {
          case m: SQLMigration =>
            val content = loadSQL(m.resource)
            (m.resource, sha256(content), "SQL")
          case m: ScalaMigrationDef =>
            (migrationNameOf(m), "scala", "Scala")
        }
        val display = displayNameOfAny(migration)
        val txn = isTransactional(migration)
        val rank = applied.size + idx + 1
        val start = System.nanoTime()

        if (txn) {
          conn.setAutoCommit(false)
          try {
            migration match {
              case m: SQLMigration      => conn.createStatement().execute(loadSQL(m.resource))
              case m: ScalaMigrationDef => m.migration.execute(utils.ManagedConnection(conn))
            }
            recordMigration(conn, migrationName, checksum, System.nanoTime() - start, rank, migrationType, display)
            conn.commit()
            printer.printApplied(display, txn)
          } catch {
            case e: Exception =>
              conn.rollback()
              printer.printFailed("exception", display, txn)
              val hasMorePending = idx < pendingMigrations.size - 1
              if (hasMorePending) printer.closeWithPendingMessage()
              else printer.close()
              val msg = s"Migration failed: $migrationName"
              logger.error(msg, e)
              throw new RuntimeException(msg, e)
          } finally {
            conn.setAutoCommit(true)
          }
        } else {
          // Use a fresh connection for non-transactional migrations to guarantee
          // a clean auto-commit state, free from any prior transaction context.
          // Statements are executed individually to prevent the JDBC driver from
          // batching them into a single protocol-level transaction (which would
          // break statements like CREATE INDEX CONCURRENTLY).
          val ntConn = datasource.getConnection
          try {
            ntConn.setAutoCommit(true)
            setSchemaIfNeeded(ntConn)
            migration match {
              case m: SQLMigration =>
                val statements = splitStatements(loadSQL(m.resource))
                for (stmt <- statements) {
                  ntConn.createStatement().execute(stmt)
                }
              case _: ScalaMigrationDef =>
                throw new IllegalStateException("ScalaMigrationDef is always transactional")
            }
          } catch {
            case e: Exception =>
              printer.printFailed("exception", display, txn)
              val hasMorePending = idx < pendingMigrations.size - 1
              if (hasMorePending) printer.closeWithPendingMessage()
              else printer.close()
              val msg = s"Non-transactional migration failed: $migrationName — the database may be in an inconsistent state as statements already executed cannot be rolled back."
              logger.error(msg, e)
              throw new RuntimeException(msg, e)
          } finally {
            ntConn.close()
          }
          // Record history on the main connection
          conn.setAutoCommit(false)
          try {
            recordMigration(conn, migrationName, checksum, System.nanoTime() - start, rank, migrationType, display)
            conn.commit()
          } finally {
            conn.setAutoCommit(true)
          }
          printer.printApplied(display, txn)
        }
      }
      printer.close()
    } finally {
      conn.close()
    }
  }

  /** Drops all objects in the configured schema and re-runs all migrations from scratch.
    *
    * This is a destructive operation intended for development and testing environments.
    * If the schema is empty, the drop is skipped and migrations are applied directly.
    * Otherwise, it validates that the schema contains a valid Nomad history table
    * (with the expected columns and at least one recorded migration) to prevent
    * accidental destruction of a schema not managed by Nomad.
    *
    * @throws java.lang.IllegalStateException if the schema is non-empty but no Nomad history table is found
    */
  def cleanAndMigrate(): Unit = {
    val conn = datasource.getConnection
    try {
      setSchemaIfNeeded(conn)
      if (isSchemaEmpty(conn)) {
        logger.info("Instructed to clean schema, but schema is empty, nothing to clean.")
      } else {
        requireHistoryTableExists(conn)

        val stmt = conn.createStatement()
        db match {
          case SupportedDatabase.Postgres =>
            stmt.execute(s"""DROP SCHEMA "$schema" CASCADE""")
            stmt.execute(s"""CREATE SCHEMA "$schema"""")
          case SupportedDatabase.H2 =>
            stmt.execute(s"""DROP SCHEMA "$schema" CASCADE""")
            stmt.execute(s"""CREATE SCHEMA "$schema"""")
        }
        stmt.close()
      }
    } finally {
      conn.close()
    }
    migrate()
  }

  /** Returns the status of all migrations by comparing the manifest against the history table.
    *
    * Each migration is classified as [[MigrationStatus.Applied]], [[MigrationStatus.Pending]],
    * [[MigrationStatus.BadChecksum]], [[MigrationStatus.Missing]],
    * [[MigrationStatus.NotInManifest]], or [[MigrationStatus.OutOfOrder]].
    *
    * @return a vector of [[MigrationEntry]] describing the state of each migration
    */
  def status(): Vector[MigrationEntry] = {
    val conn = datasource.getConnection
    try {
      setSchemaIfNeeded(conn)
      val historyExists = checkHistoryTableExists(conn)
      if (!historyExists) {
        return migrations.map { m =>
          MigrationEntry(MigrationStatus.Pending, migrationNameOfAny(m), displayNameOfAny(m), None, None, isTransactional(m))
        }
      }

      val applied = loadApplied(conn)
      val manifestNames = migrations.map(migrationNameOfAny)
      val entries = Vector.newBuilder[MigrationEntry]

      for ((appliedMigration, i) <- applied.zipWithIndex) {
        if (i >= migrations.size || migrationNameOfAny(migrations(i)) != appliedMigration.name) {
          entries += classifyMismatch(appliedMigration, manifestNames)
        } else {
          val m = migrations(i)
          val display = displayNameOfAny(m)
          m match {
            case sql: SQLMigration =>
              val currentChecksum = sha256(loadSQL(sql.resource))
              if (currentChecksum != appliedMigration.checksum) {
                entries += MigrationEntry(
                  MigrationStatus.BadChecksum,
                  sql.resource,
                  display,
                  Some(appliedMigration.checksum),
                  Some(currentChecksum),
                  sql.transactional
                )
              } else {
                entries += MigrationEntry(
                  MigrationStatus.Applied,
                  sql.resource,
                  display,
                  Some(appliedMigration.checksum),
                  Some(currentChecksum),
                  sql.transactional
                )
              }
            case sm: ScalaMigrationDef =>
              entries += MigrationEntry(
                MigrationStatus.Applied,
                migrationNameOf(sm),
                display,
                Some(appliedMigration.checksum),
                None
              )
          }
        }
      }

      // Remaining migrations in manifest that haven't been applied
      val pendingMigrations = migrations.drop(applied.size)
      for (m <- pendingMigrations) {
        entries += MigrationEntry(MigrationStatus.Pending, migrationNameOfAny(m), displayNameOfAny(m), None, None, isTransactional(m))
      }

      entries.result()
    } finally {
      conn.close()
    }
  }

  /** Prints the migration status table and returns true if problems were detected.
    *
    * Outputs the main status table to stdout, and if any migrations have problems,
    * also prints a detailed problems table.
    *
    * @return true if problems were detected
    */
  def printStatus(): Boolean = {
    val entries = status()
    val noHistory = {
      val conn = datasource.getConnection
      try {
        setSchemaIfNeeded(conn)
        !checkHistoryTableExists(conn)
      } finally {
        conn.close()
      }
    }
    logger.info(StatusTable.renderStatusTable(entries, noHistory))

    val problems = entries.filter(e => StatusTable.isProblem(e.status))
    if (problems.nonEmpty) {
      logger.error(StatusTable.renderProblemsTable(problems))
      true
    } else {
      false
    }
  }

  /** Imports migration history from a Flyway schema history table into the Nomad history table.
    *
    * Only SQL migrations are imported. For each Flyway entry, the corresponding SQL file
    * is loaded from the classpath to compute Nomad's SHA-256 checksum. The installed rank
    * is assigned based on the position in `importedPaths`, which should match the manifest order.
    *
    * @param flywayTable the name of the Flyway schema history table to read from
    * @param importedPaths the resource paths of the imported Flyway migrations, in manifest order
    * @return the number of migrations imported
    * @throws RuntimeException if the Nomad history table already has recorded migrations
    */
  def importFlywayHistory(flywayTable: String, importedPaths: Array[String]): Int = {
    importFlywayHistory(flywayTable, importedPaths, remapMismatchedScripts = false)
  }

  /** Imports migration history from a Flyway schema history table into the Nomad history table.
    *
    * If `remapMismatchedScripts` is true, Flyway rows whose script path does not exactly match a
    * path in `importedPaths` may still be imported when the script's file name uniquely matches one
    * imported migration. This supports imports where the current project layout differs from the
    * legacy Flyway project layout while the versioned SQL files themselves are the same.
    *
    * @param flywayTable the name of the Flyway schema history table to read from
    * @param importedPaths the resource paths of the imported Flyway migrations, in manifest order
    * @param remapMismatchedScripts whether to import uniquely basename-matched scripts under the
    *                               current project resource paths
    * @return the number of migrations imported
    */
  def importFlywayHistory(
    flywayTable: String,
    importedPaths: Array[String],
    remapMismatchedScripts: Boolean
  ): Int = {
    val conn = datasource.getConnection
    try {
      setSchemaIfNeeded(conn)
      ensureHistoryTable(conn)

      // Only import into an empty history table
      val countRs = conn.createStatement().executeQuery(s"SELECT COUNT(*) FROM $historyTable")
      countRs.next()
      val existingCount = countRs.getLong(1)
      countRs.close()
      if (existingCount > 0) {
        val msg = s"Nomad history table '$historyTable' already has $existingCount recorded migration(s). Import is only supported into an empty history table."
        logger.error(msg)
        throw new RuntimeException(msg)
      }

      val rows = readFlywayHistoryRows(conn, flywayTable)
      val resolutions = resolveFlywayScripts(importedPaths, rows)

      var importedCount = 0
      for ((row, resolution) <- rows.zip(resolutions)) {
        resolution match {
          case FlywayImportResolution.Exact(rank, resourcePath) =>
            insertImportedFlywayRow(conn, rank, resourcePath, row)
            logger.info(s"Imported history: $resourcePath")
            importedCount += 1
          case FlywayImportResolution.Remapped(rank, resourcePath) if remapMismatchedScripts =>
            insertImportedFlywayRow(conn, rank, resourcePath, row)
            logger.info(s"Imported history with remapped path: ${row.script} -> $resourcePath")
            importedCount += 1
          case FlywayImportResolution.Remapped(_, resourcePath) =>
            logger.warn(
              s"Flyway migration '${row.script}' matched imported migration '$resourcePath' by file name only, skipping."
            )
          case FlywayImportResolution.Missing =>
            logger.warn(s"Flyway migration '${row.script}' not found in imported paths, skipping.")
          case FlywayImportResolution.Ambiguous =>
            logger.warn(s"Flyway migration '${row.script}' matched multiple imported paths by file name, skipping.")
        }
      }

      logger.info(s"Imported $importedCount Flyway migration(s) into Nomad history.")
      importedCount
    } finally {
      conn.close()
    }
  }

  /** Analyzes how Flyway history scripts line up with the imported migration resource paths.
    *
    * Exact matches compare the Flyway `script` column directly with entries in `importedPaths`.
    * Remappable mismatches are Flyway scripts whose full path differs, but whose file name maps
    * uniquely to one imported migration resource path.
    */
  def analyzeFlywayHistoryImport(flywayTable: String, importedPaths: Array[String]): FlywayImportAnalysis = {
    val conn = datasource.getConnection
    try {
      setSchemaIfNeeded(conn)
      val rows = readFlywayHistoryRows(conn, flywayTable)
      val resolutions = resolveFlywayScripts(importedPaths, rows)
      FlywayImportAnalysis(
        exactMatchCount = resolutions.count {
          case FlywayImportResolution.Exact(_, _) => true
          case _                                  => false
        },
        remappableMismatchCount = resolutions.count {
          case FlywayImportResolution.Remapped(_, _) => true
          case _                                     => false
        },
        missingCount = resolutions.count(_ == FlywayImportResolution.Missing),
        ambiguousCount = resolutions.count(_ == FlywayImportResolution.Ambiguous)
      )
    } finally {
      conn.close()
    }
  }

  private def checkHistoryTableExists(conn: Connection): Boolean = {
    try {
      val rs = conn.createStatement().executeQuery(s"SELECT 1 FROM $historyTable WHERE 1=0")
      rs.close()
      true
    } catch {
      case _: Exception => false
    }
  }

  /** Reads all successful SQL migration rows from a Flyway history table, ordered by installed rank. */
  private def readFlywayHistoryRows(conn: Connection, flywayTable: String): Vector[FlywayHistoryRow] = {
    val rs = conn.createStatement().executeQuery(
      s"SELECT script, description, installed_by, installed_on, execution_time FROM $flywayTable WHERE success = true AND type = 'SQL' ORDER BY installed_rank"
    )
    val rows = Vector.newBuilder[FlywayHistoryRow]
    while (rs.next()) {
      rows += FlywayHistoryRow(
        script = rs.getString("script"),
        description = rs.getString("description"),
        installedBy = rs.getString("installed_by"),
        installedOn = rs.getTimestamp("installed_on"),
        executionTimeMs = rs.getLong("execution_time")
      )
    }
    rs.close()
    rows.result()
  }

  /** Resolves each Flyway history row to an import resolution: exact match, basename remap, missing, or ambiguous. */
  private def resolveFlywayScripts(
    importedPaths: Array[String],
    rows: Vector[FlywayHistoryRow]
  ): Vector[FlywayImportResolution] = {
    val exactPathLookup = importedPaths.zipWithIndex.map { case (path, idx) =>
      path -> (idx + 1, path)
    }.toMap
    val pathsByFileName = importedPaths.zipWithIndex
      .groupBy { case (path, _) => fileNameOf(path) }
      .view
      .mapValues(_.map { case (path, idx) => (idx + 1, path) }.sortBy(_._1).toVector)
      .toMap

    rows.map { row =>
      exactPathLookup.get(row.script) match {
        case Some((rank, resourcePath)) =>
          FlywayImportResolution.Exact(rank, resourcePath)
        case None =>
          pathsByFileName.get(fileNameOf(row.script)) match {
            case Some(Vector((rank, resourcePath))) =>
              FlywayImportResolution.Remapped(rank, resourcePath)
            case Some(matches) if matches.nonEmpty =>
              FlywayImportResolution.Ambiguous
            case _ =>
              FlywayImportResolution.Missing
          }
      }
    }
  }

  private def fileNameOf(path: String): String = {
    val lastSeparator = math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    path.substring(lastSeparator + 1)
  }

  /** Inserts a single Flyway migration into the Nomad history table, computing the SHA-256 checksum from the SQL file. */
  private def insertImportedFlywayRow(
    conn: Connection,
    rank: Int,
    resourcePath: String,
    row: FlywayHistoryRow
  ): Unit = {
    val content = loadSQL(resourcePath)
    val checksum = sha256(content)

    val ps = conn.prepareStatement(
      s"INSERT INTO $historyTable (name, installed_rank, description, type, checksum, installed_by, installed_on, execution_time_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
    )
    ps.setString(1, resourcePath)
    ps.setInt(2, rank)
    ps.setString(3, row.description)
    db match {
      case SupportedDatabase.Postgres => ps.setObject(4, "SQL", java.sql.Types.OTHER)
      case SupportedDatabase.H2       => ps.setString(4, "SQL")
    }
    ps.setString(5, checksum)
    ps.setString(6, row.installedBy)
    ps.setTimestamp(7, row.installedOn)
    ps.setLong(8, row.executionTimeMs)
    ps.executeUpdate()
    ps.close()
  }

  private def setSchemaIfNeeded(conn: Connection): Unit = {
    val current = conn.getSchema
    if (current == null || !current.equalsIgnoreCase(schema)) {
      conn.setSchema(schema)
    }
  }

  /** Light validation for cleanAndMigrate: only checks that the history table exists by name.
    * This confirms the schema is managed by Nomad without requiring specific columns,
    * since the entire schema (including the table) is about to be dropped and recreated.
    */
  private def requireHistoryTableExists(conn: Connection): Unit = {
    if (!checkHistoryTableExists(conn)) {
      val msg = s"Schema '$schema' contains objects but no Nomad history table ('$historyTable'), aborting clean to prevent accidental data loss."
      logger.error(msg)
      throw new IllegalStateException(msg)
    }
  }

  /** Checks whether the schema has no database objects (tables, views, sequences, types, etc.).
    *
    * Uses database-specific queries to detect any objects in the schema, not just tables.
    *
    * @return true if no objects exist in the configured schema
    */
  private def isSchemaEmpty(conn: Connection): Boolean = {
    db match {
      case SupportedDatabase.Postgres =>
        val ps = conn.prepareStatement(
          """SELECT COUNT(*) FROM pg_catalog.pg_class c
            |JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            |WHERE n.nspname = ?""".stripMargin
        )
        ps.setString(1, schema)
        val rs = ps.executeQuery()
        rs.next()
        val count = rs.getLong(1)
        rs.close()
        ps.close()
        count == 0
      case SupportedDatabase.H2 =>
        // Check tables and views via JDBC metadata (covers all types returned by H2)
        val meta = conn.getMetaData
        val tables = meta.getTables(null, null, null, null)
        var hasObjects = false
        while (tables.next()) {
          val tSchema = tables.getString("TABLE_SCHEM")
          if (tSchema != null && tSchema.equalsIgnoreCase(schema)) {
            hasObjects = true
          }
        }
        tables.close()
        if (hasObjects) return false
        // Check sequences
        val ps = conn.prepareStatement(
          "SELECT COUNT(*) FROM information_schema.sequences WHERE sequence_schema = ?"
        )
        ps.setString(1, schema)
        val rs = ps.executeQuery()
        rs.next()
        val seqCount = rs.getLong(1)
        rs.close()
        ps.close()
        seqCount == 0
    }
  }

  private val migrationTypeEnumName = "nomad_migration_type"

  /** Creates the history table and its migration type enum if they don't already exist. */
  private def ensureHistoryTable(conn: Connection): Unit = {
    val stmt = conn.createStatement()
    db match {
      case SupportedDatabase.Postgres =>
        stmt.execute(
          s"""DO $$$$ BEGIN
             |  IF NOT EXISTS (
             |    SELECT 1 FROM pg_type t
             |    JOIN pg_namespace n ON n.oid = t.typnamespace
             |    WHERE t.typname = '$migrationTypeEnumName' AND n.nspname = '$schema'
             |  ) THEN
             |    CREATE TYPE $migrationTypeEnumName AS ENUM ('SQL', 'Scala');
             |  END IF;
             |END $$$$""".stripMargin
        )
        stmt.execute(
          s"""CREATE TABLE IF NOT EXISTS $historyTable (
             |  name VARCHAR(512) NOT NULL PRIMARY KEY,
             |  installed_rank INT NOT NULL,
             |  description VARCHAR(512) NOT NULL,
             |  type $migrationTypeEnumName NOT NULL,
             |  checksum VARCHAR(64) NOT NULL,
             |  installed_by VARCHAR(128) NOT NULL,
             |  installed_on TIMESTAMP NOT NULL,
             |  execution_time_ms BIGINT NOT NULL
             |)""".stripMargin
        )
      case SupportedDatabase.H2 =>
        stmt.execute(
          s"""CREATE TABLE IF NOT EXISTS $historyTable (
             |  name VARCHAR(512) NOT NULL PRIMARY KEY,
             |  installed_rank INT NOT NULL,
             |  description VARCHAR(512) NOT NULL,
             |  type VARCHAR(10) NOT NULL CHECK (type IN ('SQL', 'Scala')),
             |  checksum VARCHAR(64) NOT NULL,
             |  installed_by VARCHAR(128) NOT NULL,
             |  installed_on TIMESTAMP NOT NULL,
             |  execution_time_ms BIGINT NOT NULL
             |)""".stripMargin
        )
    }
    stmt.close()
  }

  /** Loads all previously applied migrations from the history table, ordered by installed rank. */
  private def loadApplied(conn: Connection): Vector[AppliedMigration] = {
    val rs = conn.createStatement().executeQuery(
      s"SELECT name, checksum, installed_on, execution_time_ms, installed_rank FROM $historyTable ORDER BY installed_rank"
    )
    val buf = Vector.newBuilder[AppliedMigration]
    while (rs.next()) {
      buf += AppliedMigration(
        name = rs.getString("name"),
        checksum = rs.getString("checksum"),
        installedOn = rs.getTimestamp("installed_on").toInstant,
        executionTimeMs = rs.getLong("execution_time_ms"),
        installedRank = rs.getInt("installed_rank")
      )
    }
    buf.result()
  }

  /** Validates applied migrations against the manifest: checks ordering, checksums,
    * and detects missing, not-in-manifest, or out-of-order migrations.
    */
  private def validateApplied(applied: Vector[AppliedMigration], pending: Vector[Migration]): Unit = {
    val manifestNames = migrations.map(migrationNameOfAny)

    for ((appliedMigration, i) <- applied.zipWithIndex) {
      if (i >= migrations.size || migrationNameOfAny(migrations(i)) != appliedMigration.name) {
        val (failureStatus, msg) = classifyMismatchForValidation(appliedMigration, i, manifestNames)
        val display = StatusTable.extractDisplayName(appliedMigration.name)
        printValidationFailure(applied, pending, i, failureStatus, display)
        logger.error(msg)
        throw new RuntimeException(msg)
      }
      migrations(i) match {
        case sql: SQLMigration =>
          val currentChecksum = sha256(loadSQL(sql.resource))
          if (currentChecksum != appliedMigration.checksum) {
            val display = displayNameOfAny(migrations(i))
            printValidationFailure(applied, pending, i, "bad checksum", display)
            val msg = s"Checksum mismatch for '${sql.resource}': file has been modified after it was applied."
            logger.error(msg)
            throw new RuntimeException(msg)
          }
        case _: ScalaMigrationDef => () // No checksumming for Scala migrations
      }
    }
  }

  /** Classifies a mismatch between an applied migration and the manifest for validation errors.
    *
    * @param appliedMigration the applied migration that doesn't match
    * @param appliedIndex the 0-based index of this migration in the applied history
    * @param manifestNames all migration names from the manifest, in order
    * @return a tuple of (status label for table display, error message)
    */
  private def classifyMismatchForValidation(
    appliedMigration: AppliedMigration,
    appliedIndex: Int,
    manifestNames: Vector[String]
  ): (String, String) = {
    val name = appliedMigration.name
    val resourceExists = resourceExistsOnClasspath(name)
    val manifestIndex = manifestNames.indexOf(name)
    val rank = appliedIndex + 1
    val expectedAtPosition = manifestNames.lift(appliedIndex).getOrElse("<none>")

    if (!resourceExists) {
      ("missing", s"Migration '$name' was previously applied at rank $rank but its resource no longer exists on the classpath.")
    } else if (manifestIndex < 0) {
      ("not in manifest", s"Migration '$name' was previously applied at rank $rank but is no longer listed in the manifest. Expected '$expectedAtPosition' at this position.")
    } else {
      val foundAtPosition = if (appliedIndex < manifestNames.size) s"'${manifestNames(appliedIndex)}'" else "<end of manifest>"
      ("out of order", s"Expected migration '$name' at position $rank, but found $foundAtPosition instead. '$name' is now at position ${manifestIndex + 1} in the manifest.")
    }
  }

  /** Classifies a mismatch between an applied migration and the manifest for status reporting. */
  private def classifyMismatch(
    appliedMigration: AppliedMigration,
    manifestNames: Vector[String]
  ): MigrationEntry = {
    val name = appliedMigration.name
    val resourceExists = resourceExistsOnClasspath(name)
    val manifestIndex = manifestNames.indexOf(name)

    val status =
      if (!resourceExists) MigrationStatus.Missing
      else if (manifestIndex < 0) MigrationStatus.NotInManifest
      else MigrationStatus.OutOfOrder

    MigrationEntry(
      status,
      name,
      StatusTable.extractDisplayName(name),
      Some(appliedMigration.checksum),
      None
    )
  }

  /** Checks whether a migration resource still exists on the classpath. */
  private def resourceExistsOnClasspath(name: String): Boolean = {
    if (name.endsWith(".sql")) {
      getClass.getClassLoader.getResource(name) != null
    } else {
      // Scala migration — check if the class can be loaded
      try {
        getClass.getClassLoader.loadClass(name)
        true
      } catch {
        case _: ClassNotFoundException => false
      }
    }
  }

  private def printValidationFailure(
    applied: Vector[AppliedMigration],
    pending: Vector[Migration],
    failedIndex: Int,
    failureStatus: String,
    failedDisplay: String
  ): Unit = {
    val startingPoint = if (failedIndex > 0) {
      val prev = migrations.lift(failedIndex - 1)
      Some(prev.map(displayNameOfAny).getOrElse(StatusTable.extractDisplayName(applied(failedIndex - 1).name)))
    } else None
    val pendingInfo = pending.map(m => (displayNameOfAny(m), isTransactional(m)))
    val printer = StatusTable.migrateTablePrinter(startingPoint, pendingInfo :+ ((failedDisplay, true)), logger)
    startingPoint.foreach(printer.printStartingPoint)
    printer.printFailed(failureStatus, failedDisplay, true)
    val hasMorePending = pending.nonEmpty
    if (hasMorePending) printer.closeWithPendingMessage()
    else printer.close()
  }

  /** Splits a SQL string into individual statements by semicolons.
    *
    * This is used for non-transactional migrations to execute each statement
    * individually, preventing the JDBC driver from batching multiple statements
    * into a single protocol-level transaction.
    */
  private def splitStatements(sql: String): Vector[String] = {
    sql.split(";").map(_.trim).filter(_.nonEmpty).toVector
  }

  private def isTransactional(m: Migration): Boolean = m match {
    case sql: SQLMigration     => sql.transactional
    case _: ScalaMigrationDef  => true
  }

  private def migrationNameOf(m: ScalaMigrationDef): String =
    m.migration.getClass.getName.stripSuffix("$")

  private def migrationNameOfAny(m: Migration): String = m match {
    case sql: SQLMigration      => sql.resource
    case sm: ScalaMigrationDef  => migrationNameOf(sm)
  }

  private def displayNameOfAny(m: Migration): String = m match {
    case sql: SQLMigration     => StatusTable.extractDisplayName(sql.resource)
    case sm: ScalaMigrationDef => sm.migration.description
  }

  private def loadSQL(resource: String): String = {
    val stream = getClass.getClassLoader.getResourceAsStream(resource)
    if (stream == null) throw new RuntimeException(s"Resource not found: $resource")
    Using(Source.fromInputStream(stream, "UTF-8"))(_.mkString).get
  }

  private def sha256(content: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(content.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  /** Records a successfully applied migration in the history table. */
  private def recordMigration(
    conn: Connection,
    name: String,
    checksum: String,
    durationNanos: Long,
    installedRank: Int,
    migrationType: String,
    description: String
  ): Unit = {
    val installedBy = conn.getMetaData.getUserName
    val ps = conn.prepareStatement(
      s"INSERT INTO $historyTable (name, checksum, installed_on, execution_time_ms, installed_rank, type, installed_by, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
    )
    ps.setString(1, name)
    ps.setString(2, checksum)
    ps.setTimestamp(3, Timestamp.from(Instant.now()))
    ps.setLong(4, durationNanos / 1_000_000)
    ps.setInt(5, installedRank)
    db match {
      case SupportedDatabase.Postgres => ps.setObject(6, migrationType, java.sql.Types.OTHER)
      case SupportedDatabase.H2       => ps.setString(6, migrationType)
    }
    ps.setString(7, installedBy)
    ps.setString(8, description)
    ps.executeUpdate()
  }
}

/** Internal representation of a migration row read from the history table. */
private[nomad] case class AppliedMigration(
  name: String,
  checksum: String,
  installedOn: Instant,
  executionTimeMs: Long,
  installedRank: Int
)
