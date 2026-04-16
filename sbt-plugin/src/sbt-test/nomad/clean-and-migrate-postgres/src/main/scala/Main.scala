import nomad.{Migrator, SQLMigration, SupportedDatabase}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

object Main {
  def main(args: Array[String]): Unit = {
    val pg = EmbeddedPostgres.start()

    try {
      val ds = pg.getPostgresDatabase()
      val migrations = Vector(SQLMigration("migrations/M001_CreateUsers.sql"))

      // --- Test 1: cleanAndMigrate on default "public" schema ---
      val migrator = new Migrator(ds, SupportedDatabase.Postgres, migrations)
      migrator.migrate()

      // Insert data to verify it gets cleaned
      val conn1 = ds.getConnection
      conn1.createStatement().execute("INSERT INTO users (id, name) VALUES (1, 'Alice')")
      conn1.close()

      migrator.cleanAndMigrate()

      val conn2 = ds.getConnection
      val rs1 = conn2.createStatement().executeQuery("SELECT COUNT(*) FROM users")
      rs1.next()
      assert(rs1.getLong(1) == 0, "Expected 0 user rows after clean on public schema")
      rs1.close()
      val rs2 = conn2.createStatement().executeQuery("SELECT COUNT(*) FROM nomad_migrations")
      rs2.next()
      assert(rs2.getLong(1) == 1, "Expected 1 migration recorded after clean on public schema")
      rs2.close()
      conn2.close()

      println("Test 1 passed: cleanAndMigrate on public schema")

      // --- Test 2: cleanAndMigrate on custom schema ---
      val setup = ds.getConnection
      setup.createStatement().execute("CREATE SCHEMA IF NOT EXISTS app")
      setup.close()

      val customMigrator = new Migrator(ds, SupportedDatabase.Postgres, migrations, schema = "app")
      customMigrator.migrate()

      val conn3 = ds.getConnection
      conn3.setSchema("app")
      conn3.createStatement().execute("INSERT INTO users (id, name) VALUES (1, 'Bob')")
      conn3.close()

      customMigrator.cleanAndMigrate()

      val conn4 = ds.getConnection
      conn4.setSchema("app")
      val rs3 = conn4.createStatement().executeQuery("SELECT COUNT(*) FROM users")
      rs3.next()
      assert(rs3.getLong(1) == 0, "Expected 0 user rows after clean on custom schema")
      rs3.close()
      conn4.close()

      // Verify the public schema was NOT affected (still has its data from test 1)
      val conn5 = ds.getConnection
      val rs4 = conn5.createStatement().executeQuery("SELECT COUNT(*) FROM public.nomad_migrations")
      rs4.next()
      assert(rs4.getLong(1) == 1, "Public schema should be untouched")
      rs4.close()
      conn5.close()

      println("Test 2 passed: cleanAndMigrate on custom schema")

      // --- Test 3: cleanAndMigrate fails without history table ---
      val setup2 = ds.getConnection
      setup2.createStatement().execute("CREATE SCHEMA IF NOT EXISTS no_history")
      setup2.setSchema("no_history")
      setup2.createStatement().execute("CREATE TABLE dummy (id INT)")
      setup2.close()

      val noHistoryMigrator = new Migrator(ds, SupportedDatabase.Postgres, migrations, schema = "no_history")
      try {
        noHistoryMigrator.cleanAndMigrate()
        throw new AssertionError("Expected IllegalStateException for schema without history table")
      } catch {
        case e: IllegalStateException =>
          assert(e.getMessage.contains("no Nomad history table"), s"Unexpected: ${e.getMessage}")
      }

      println("Test 3 passed: rejects clean without history table")

      // --- Test 4: cleanAndMigrate on schema with empty history table should succeed ---
      // (the table exists so the schema is recognized as Nomad-managed, gets dropped and recreated)
      val setup3 = ds.getConnection
      setup3.createStatement().execute("CREATE SCHEMA IF NOT EXISTS empty_history")
      setup3.setSchema("empty_history")
      setup3.createStatement().execute("CREATE TYPE nomad_migration_type AS ENUM ('SQL', 'Scala')")
      setup3.createStatement().execute(
        """CREATE TABLE nomad_migrations (
          |  name VARCHAR(512) NOT NULL PRIMARY KEY,
          |  installed_rank INT NOT NULL,
          |  description VARCHAR(512) NOT NULL,
          |  type nomad_migration_type NOT NULL,
          |  checksum VARCHAR(64) NOT NULL,
          |  installed_by VARCHAR(128) NOT NULL,
          |  installed_on TIMESTAMP NOT NULL,
          |  execution_time_ms BIGINT NOT NULL
          |)""".stripMargin
      )
      setup3.close()

      val emptyHistoryMigrator = new Migrator(ds, SupportedDatabase.Postgres, migrations, schema = "empty_history")
      emptyHistoryMigrator.cleanAndMigrate()

      val conn5b = ds.getConnection
      conn5b.setSchema("empty_history")
      val rs4b = conn5b.createStatement().executeQuery("SELECT COUNT(*) FROM nomad_migrations")
      rs4b.next()
      assert(rs4b.getLong(1) == 1, "Expected 1 migration recorded after cleanAndMigrate on empty history schema")
      rs4b.close()
      conn5b.close()

      println("Test 4 passed: cleanAndMigrate on empty history table")

      // --- Test 5: cleanAndMigrate on empty schema skips clean and just migrates ---
      val setup4 = ds.getConnection
      setup4.createStatement().execute("CREATE SCHEMA IF NOT EXISTS empty_schema")
      setup4.close()

      val emptySchemaMigrator = new Migrator(ds, SupportedDatabase.Postgres, migrations, schema = "empty_schema")
      emptySchemaMigrator.cleanAndMigrate()

      val conn6 = ds.getConnection
      conn6.setSchema("empty_schema")
      val rs5 = conn6.createStatement().executeQuery("SELECT COUNT(*) FROM nomad_migrations")
      rs5.next()
      assert(rs5.getLong(1) == 1, "Expected 1 migration recorded after cleanAndMigrate on empty schema")
      rs5.close()
      val rs6 = conn6.createStatement().executeQuery("SELECT COUNT(*) FROM users")
      rs6.next()
      assert(rs6.getLong(1) == 0, "Expected 0 user rows after cleanAndMigrate on empty schema")
      rs6.close()
      conn6.close()

      println("Test 5 passed: cleanAndMigrate on empty schema skips clean and migrates")
      println("All Postgres cleanAndMigrate tests passed!")
    } finally {
      pg.close()
    }
  }
}
