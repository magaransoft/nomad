import nomad.{Migrator, SQLMigration, SupportedDatabase}
import org.h2.jdbcx.JdbcDataSource

object Main {
  def main(args: Array[String]): Unit = {
    val ds = new JdbcDataSource()
    ds.setURL("jdbc:h2:mem:clean_test;DB_CLOSE_DELAY=-1")

    // Create a custom schema and run initial migration (H2 uppercases unquoted identifiers)
    val setup = ds.getConnection
    setup.createStatement().execute("CREATE SCHEMA IF NOT EXISTS APP")
    setup.close()

    val migrations = Vector(SQLMigration("migrations/M001_CreateUsers.sql"))
    val migrator = new Migrator(ds, SupportedDatabase.H2, migrations, schema = "APP")
    migrator.migrate()

    // Insert a row so we can verify it gets cleaned
    val conn1 = ds.getConnection
    conn1.setSchema("APP")
    conn1.createStatement().execute("INSERT INTO users (id, name) VALUES (1, 'Alice')")
    val rs1 = conn1.createStatement().executeQuery("SELECT COUNT(*) FROM users")
    rs1.next()
    assert(rs1.getLong(1) == 1, "Expected 1 user row before clean")
    rs1.close()
    conn1.close()

    // cleanAndMigrate should drop everything and re-run migrations
    migrator.cleanAndMigrate()

    // Verify schema is fresh: tables exist but user data is gone
    val conn2 = ds.getConnection
    conn2.setSchema("APP")
    val rs2 = conn2.createStatement().executeQuery("SELECT COUNT(*) FROM users")
    rs2.next()
    assert(rs2.getLong(1) == 0, "Expected 0 user rows after clean")
    rs2.close()

    // Verify history table has exactly one recorded migration
    val rs3 = conn2.createStatement().executeQuery("SELECT COUNT(*) FROM nomad_migrations")
    rs3.next()
    assert(rs3.getLong(1) == 1, "Expected 1 migration recorded after clean")
    rs3.close()
    conn2.close()

    // Test: cleanAndMigrate on a truly empty schema should skip clean and just migrate
    val setup1b = ds.getConnection
    setup1b.createStatement().execute("CREATE SCHEMA IF NOT EXISTS TRULY_EMPTY")
    setup1b.close()

    val emptySchemaResult = new Migrator(ds, SupportedDatabase.H2, migrations, schema = "TRULY_EMPTY")
    emptySchemaResult.cleanAndMigrate()

    // Verify migrations were applied in the empty schema
    val conn3 = ds.getConnection
    conn3.setSchema("TRULY_EMPTY")
    val rs4 = conn3.createStatement().executeQuery("SELECT COUNT(*) FROM nomad_migrations")
    rs4.next()
    assert(rs4.getLong(1) == 1, "Expected 1 migration recorded after cleanAndMigrate on empty schema")
    rs4.close()
    conn3.close()

    // Test: cleanAndMigrate on a schema without history table should fail
    val setup2 = ds.getConnection
    setup2.createStatement().execute("CREATE SCHEMA IF NOT EXISTS EMPTY_SCHEMA")
    setup2.setSchema("EMPTY_SCHEMA")
    setup2.createStatement().execute("CREATE TABLE dummy (id INT)")
    setup2.close()

    val emptyMigrator = new Migrator(ds, SupportedDatabase.H2, migrations, schema = "EMPTY_SCHEMA")
    try {
      emptyMigrator.cleanAndMigrate()
      throw new AssertionError("Expected IllegalStateException for schema without history table")
    } catch {
      case e: IllegalStateException =>
        assert(
          e.getMessage.contains("no Nomad history table"),
          s"Unexpected message: ${e.getMessage}"
        )
    }

    // Test: cleanAndMigrate on a schema with empty history table should succeed
    // (the table exists so the schema is recognized as Nomad-managed, gets dropped and recreated)
    val setup3 = ds.getConnection
    setup3.createStatement().execute("CREATE SCHEMA IF NOT EXISTS EMPTY_HISTORY")
    setup3.setSchema("EMPTY_HISTORY")
    setup3.createStatement().execute(
      """CREATE TABLE nomad_migrations (
        |  name VARCHAR(512) NOT NULL PRIMARY KEY,
        |  installed_rank INT NOT NULL,
        |  description VARCHAR(512) NOT NULL,
        |  type VARCHAR(10) NOT NULL CHECK (type IN ('SQL', 'Scala')),
        |  checksum VARCHAR(64) NOT NULL,
        |  installed_by VARCHAR(128) NOT NULL,
        |  installed_on TIMESTAMP NOT NULL,
        |  execution_time_ms BIGINT NOT NULL
        |)""".stripMargin
    )
    setup3.close()

    val emptyHistoryMigrator = new Migrator(ds, SupportedDatabase.H2, migrations, schema = "EMPTY_HISTORY")
    emptyHistoryMigrator.cleanAndMigrate()

    // Verify migrations were applied
    val conn4 = ds.getConnection
    conn4.setSchema("EMPTY_HISTORY")
    val rs5 = conn4.createStatement().executeQuery("SELECT COUNT(*) FROM nomad_migrations")
    rs5.next()
    assert(rs5.getLong(1) == 1, "Expected 1 migration recorded after cleanAndMigrate on empty history schema")
    rs5.close()
    conn4.close()

    println("cleanAndMigrate tests passed!")
  }
}
