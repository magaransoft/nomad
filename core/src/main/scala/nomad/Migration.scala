package nomad

import org.intellij.lang.annotations.Language

/** Represents a database migration to be executed by the [[Migrator]]. */
sealed trait Migration

/** A migration defined as a SQL resource file.
  *
  * @param resource the classpath-relative path to the SQL file (e.g., "migrations/M202603210842_CreateUsers.sql")
  * @param transactional whether to run this migration inside a transaction (default: true).
  *                      When false, each SQL statement is auto-committed individually and
  *                      cannot be rolled back on failure.
  */
final case class SQLMigration(
  @Language("file-reference") resource: String,
  transactional: Boolean = true
) extends Migration

/** A migration defined as an instance of [[ScalaMigration]].
  *
  * @param migration the Scala migration instance to execute
  */
final case class ScalaMigrationDef(migration: ScalaMigration) extends Migration
