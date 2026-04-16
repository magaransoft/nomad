import nomad.ScalaMigration
import nomad.utils.ManagedConnection

class SeedUsers extends ScalaMigration {
  def execute(conn: ManagedConnection): Unit = {
    val stmt = conn.createStatement()
    stmt.execute("INSERT INTO users (name) VALUES ('Alice')")
    stmt.execute("INSERT INTO users (name) VALUES ('Bob')")
    stmt.close()
  }
}
