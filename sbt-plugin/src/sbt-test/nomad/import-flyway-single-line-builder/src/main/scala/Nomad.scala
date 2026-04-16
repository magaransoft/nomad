import nomad.NomadMigrations
import nomad.Migration
import nomad.SupportedDatabase
import javax.sql.DataSource

object Nomad extends NomadMigrations {
  def database: SupportedDatabase = SupportedDatabase.H2
  def datasource: DataSource = ???

  def migrations: Vector[Migration] = Vector(
  )
}
