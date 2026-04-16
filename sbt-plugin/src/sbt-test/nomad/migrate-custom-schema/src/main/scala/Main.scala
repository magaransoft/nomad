import nomad.{Migrator, SQLMigration, SupportedDatabase}
import org.h2.jdbcx.JdbcDataSource

object Main {
  def main(args: Array[String]): Unit = {
    val ds = new JdbcDataSource()
    ds.setURL("jdbc:h2:mem:custom_schema_test;DB_CLOSE_DELAY=-1")

    // Create the custom schema in H2 (H2 uppercases unquoted identifiers)
    val setup = ds.getConnection
    setup.createStatement().execute("CREATE SCHEMA IF NOT EXISTS MY_SCHEMA")
    setup.close()

    val migrations = Vector(SQLMigration("migrations/M001_CreateUsers.sql"))
    val migrator = new Migrator(ds, SupportedDatabase.H2, migrations, schema = "MY_SCHEMA")
    migrator.migrate()

    // Verify the history table and users table are in MY_SCHEMA
    val conn = ds.getConnection
    conn.setSchema("MY_SCHEMA")
    val tables = conn.getMetaData.getTables(null, "MY_SCHEMA", null, Array("TABLE"))
    val foundTables = Set.newBuilder[String]
    while (tables.next()) {
      foundTables += tables.getString("TABLE_NAME").toUpperCase
    }
    tables.close()
    conn.close()

    val found = foundTables.result()
    assert(found.contains("USERS"), s"Expected 'users' table in MY_SCHEMA, found: $found")
    assert(found.contains("NOMAD_MIGRATIONS"), s"Expected 'nomad_migrations' table in MY_SCHEMA, found: $found")

    // Verify the default (PUBLIC) schema does NOT have these tables
    val conn2 = ds.getConnection
    val publicTables = conn2.getMetaData.getTables(null, "PUBLIC", null, Array("TABLE"))
    val publicFound = Set.newBuilder[String]
    while (publicTables.next()) {
      publicFound += publicTables.getString("TABLE_NAME").toUpperCase
    }
    publicTables.close()
    conn2.close()

    val pub = publicFound.result()
    assert(!pub.contains("USERS"), s"'users' table should NOT be in PUBLIC schema")
    assert(!pub.contains("NOMAD_MIGRATIONS"), s"'nomad_migrations' should NOT be in PUBLIC schema")

    println("Custom schema migrate test passed!")
  }
}
