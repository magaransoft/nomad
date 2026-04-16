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
}
