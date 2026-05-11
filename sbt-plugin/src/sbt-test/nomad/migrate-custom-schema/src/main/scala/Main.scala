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

    // Test: migrate self-heals when the configured schema does not exist
    val selfHealMigrator = new Migrator(
      ds, SupportedDatabase.H2, migrations, schema = "NOT_THERE_YET"
    )
    selfHealMigrator.migrate()

    val conn3 = ds.getConnection
    conn3.setSchema("NOT_THERE_YET")
    val rs = conn3.createStatement().executeQuery("SELECT COUNT(*) FROM nomad_migrations")
    rs.next()
    assert(rs.getLong(1) == 1, "Expected 1 migration recorded after migrate self-heal on missing schema")
    rs.close()
    conn3.close()

    // Test: migrate with autoCreateSchema=false must fail when schema is missing.
    // Assert a SQL-level failure that references the schema name — not just any error.
    val strictMigrator = new Migrator(
      ds, SupportedDatabase.H2, migrations,
      schema = "STRICT_MISSING_MIGRATE", autoCreateSchema = false
    )
    try {
      strictMigrator.migrate()
      throw new AssertionError("Expected migrate() to fail when autoCreateSchema=false and schema is missing")
    } catch {
      case e: java.sql.SQLException =>
        assert(
          e.getMessage != null && e.getMessage.toUpperCase.contains("STRICT_MISSING_MIGRATE"),
          s"Expected H2 error referencing missing schema, got: ${e.getMessage}"
        )
      case e: RuntimeException if e.getCause.isInstanceOf[java.sql.SQLException]
          && e.getCause.getMessage != null
          && e.getCause.getMessage.toUpperCase.contains("STRICT_MISSING_MIGRATE") =>
        // expected — wrapped in Migrator's RuntimeException
    }

    println("migrate self-heal tests passed!")
  }
}
