import nomad.ScalaMigration
import nomad.utils.ManagedConnection

class AddAdmin(role: String) extends ScalaMigration {
  def execute(conn: ManagedConnection): Unit = {
    val ps = conn.prepareStatement("INSERT INTO users (name) VALUES (?)")
    ps.setString(1, s"admin-$role")
    ps.executeUpdate()
    ps.close()
  }
}
