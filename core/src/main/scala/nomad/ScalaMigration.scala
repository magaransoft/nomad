package nomad

import nomad.utils.ManagedConnection

/** Base trait for Scala-based migrations.
  *
  * Implement this trait to define a migration in Scala code rather than SQL.
  * The migration name is derived from the implementing class name and used
  * for history tracking.
  *
  * {{{
  * class SeedData extends ScalaMigration {
  *   def execute(conn: ManagedConnection): Unit = {
  *     conn.createStatement().execute("INSERT INTO users (name) VALUES ('admin')")
  *   }
  * }
  * }}}
  */
trait ScalaMigration {

  /** A human-readable description for this migration, used in status output.
    *
    * Defaults to the simple class name. Override to provide a custom description.
    */
  def description: String = getClass.getSimpleName.stripSuffix("$")

  /** Executes this migration against the given managed connection.
    *
    * The connection is already set to the correct schema and will be committed
    * or rolled back by the [[Migrator]] after this method returns. Transaction
    * control methods (commit, close, setAutoCommit, etc.) are not available.
    *
    * @param conn the managed connection to use
    */
  def execute(conn: ManagedConnection): Unit
}
