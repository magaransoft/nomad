package nomad

import javax.sql.DataSource

/** Base trait for defining a project's migration manifest.
  *
  * Implementations must provide a [[SupportedDatabase]], a [[javax.sql.DataSource]] for database connectivity,
  * and a [[Vector]] of [[Migration]]s that define the execution order.
  *
  * Migrations are specified directly:
  * {{{
  * object MyMigrations extends NomadMigrations {
  *   def database: SupportedDatabase = SupportedDatabase.Postgres
  *   def datasource: DataSource = ???
  *   def migrations: Vector[Migration] = Vector(
  *     SQLMigration("migrations/M202603210842_CreateUsers.sql"),
  *     ScalaMigrationDef(new SeedData())
  *   )
  * }
  * }}}
  */
trait NomadMigrations {

  /** The target database type, used for database-specific DDL and operations. */
  def database: SupportedDatabase

  /** The datasource used by sbt tasks (`nomadMigrate`, `nomadStatus`) to connect to the target database.
    *
    * This datasource is only used when running migrations through the sbt plugin.
    * If you use [[Migrator]] directly from code (e.g., for per-environment datasource selection),
    * you pass the datasource to the [[Migrator]] constructor instead.
    */
  def datasource: DataSource

  /** The ordered list of migrations to apply. */
  def migrations: Vector[Migration]

  /** An optional second datasource pointing at a long-lived "rebase" database on the same
    * Postgres cluster as [[datasource]], used by the `nomadRebase` sbt task.
    *
    * When configured, `nomadRebase` drops the target database (the one [[datasource]]
    * points at) and re-clones it from this rebase database via a fast Postgres
    * `CREATE DATABASE ... WITH TEMPLATE` file-level copy, then runs any pending
    * migrations on top. The rebase database is never modified.
    *
    * Defaults to `None`. Override to enable `nomadRebase`. Postgres only — the task
    * fails if [[database]] is not [[SupportedDatabase.Postgres]].
    */
  def rebaseDatasource: Option[DataSource] = None
}
