import nomad.NomadMigrations
import nomad.Migration
import nomad.SQLMigration
import nomad.SupportedDatabase
import javax.sql.DataSource
import org.h2.jdbcx.JdbcDataSource

object Nomad extends NomadMigrations {
  def database: SupportedDatabase = SupportedDatabase.H2
  def datasource: DataSource = {
    val ds = new JdbcDataSource()
    ds.setURL("jdbc:h2:file:./target/testdb;AUTO_SERVER=TRUE")
    ds
  }

  def migrations: Vector[Migration] = Vector(
    SQLMigration("migrations/M001_CreateUsers.sql")
  )
}
