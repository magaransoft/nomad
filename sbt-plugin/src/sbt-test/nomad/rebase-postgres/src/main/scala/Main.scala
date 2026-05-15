import nomad.{Migrator, Rebaser, SQLMigration, SupportedDatabase}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

object Main {
  // On NixOS zonky's bundled generic-Linux binaries fail to load. NOMAD_PG_TARBALL
  // (set by the flake devshell) overrides the binary source with a tarball of the
  // system postgres.
  private def startEmbeddedPostgres(): EmbeddedPostgres =
    sys.env.get("NOMAD_PG_TARBALL").filter(_.nonEmpty) match {
      case Some(path) =>
        // nix-store postgres defaults unix_socket_directories to /run/postgresql,
        // which doesn't exist in this sandbox — point it at the JVM temp dir instead.
        EmbeddedPostgres
          .builder()
          .setPgBinaryResolver((_, _) => new java.io.FileInputStream(path))
          .setServerConfig("unix_socket_directories", System.getProperty("java.io.tmpdir"))
          .start()
      case None => EmbeddedPostgres.start()
    }

  def main(args: Array[String]): Unit = {
    val pg = startEmbeddedPostgres()
    try {
      // Bootstrap two databases on the same cluster: rebase_db acts as the long-lived
      // template; target_db is the working database that will be repeatedly cloned.
      val admin = pg.getPostgresDatabase().getConnection
      try {
        admin.createStatement().execute("CREATE DATABASE rebase_db")
        admin.createStatement().execute("CREATE DATABASE target_db")
      } finally {
        admin.close()
      }

      val rebaseDs = pg.getDatabase("postgres", "rebase_db")
      val targetDs = pg.getDatabase("postgres", "target_db")

      val baselineMigrations = Vector(SQLMigration("migrations/M001_CreateUsers.sql"))

      // Seed the rebase database: run M001, then insert a row so we can later assert
      // the seeded data survives a file-level clone into target_db.
      new Migrator(rebaseDs, SupportedDatabase.Postgres, baselineMigrations).migrate()
      val seed = rebaseDs.getConnection
      try {
        seed.createStatement().execute("INSERT INTO users (id, name) VALUES (1, 'Charlie')")
      } finally {
        seed.close()
      }

      // --- Test 1: happy path — rebase target from rebase_db, then migrate forward ---
      new Rebaser(targetDs, rebaseDs, SupportedDatabase.Postgres).rebase()

      // After rebase the target should hold the seeded user and the M001 history row.
      val v1 = targetDs.getConnection
      try {
        val rs1 = v1.createStatement().executeQuery("SELECT COUNT(*) FROM users WHERE name = 'Charlie'")
        rs1.next()
        assert(rs1.getLong(1) == 1, "Expected seeded 'Charlie' in cloned target")
        rs1.close()
        val rs2 = v1.createStatement().executeQuery("SELECT COUNT(*) FROM nomad_migrations")
        rs2.next()
        assert(rs2.getLong(1) == 1, "Expected M001 history row in cloned target")
        rs2.close()
      } finally {
        v1.close()
      }

      // Applying a new migration on top of the clone should only run M002.
      val extendedManifest = baselineMigrations :+ SQLMigration("migrations/M002_CreateProducts.sql")
      new Migrator(targetDs, SupportedDatabase.Postgres, extendedManifest).migrate()

      val v2 = targetDs.getConnection
      try {
        val rsP = v2.createStatement().executeQuery("SELECT COUNT(*) FROM products")
        rsP.next()
        assert(rsP.getLong(1) == 0, "Expected products table created by M002")
        rsP.close()
        val rsU = v2.createStatement().executeQuery("SELECT COUNT(*) FROM users WHERE name = 'Charlie'")
        rsU.next()
        assert(rsU.getLong(1) == 1, "Expected 'Charlie' to remain after migrating on top of clone")
        rsU.close()
        val rsH = v2.createStatement().executeQuery("SELECT COUNT(*) FROM nomad_migrations")
        rsH.next()
        assert(rsH.getLong(1) == 2, "Expected M001 + M002 both recorded after rebase + migrate")
        rsH.close()
      } finally {
        v2.close()
      }
      println("Test 1 passed: rebase + migrate cycle")

      // --- Test 2: H2 is rejected ---
      try {
        new Rebaser(targetDs, rebaseDs, SupportedDatabase.H2).rebase()
        throw new AssertionError("Expected IllegalArgumentException for H2")
      } catch {
        case e: IllegalArgumentException if e.getMessage.contains("Postgres") =>
        // expected
      }
      println("Test 2 passed: H2 rejected")

      // --- Test 3: target == rebase is rejected ---
      try {
        new Rebaser(rebaseDs, rebaseDs, SupportedDatabase.Postgres).rebase()
        throw new AssertionError("Expected IllegalArgumentException for target == rebase")
      } catch {
        case e: IllegalArgumentException if e.getMessage.contains("must differ") =>
        // expected
      }
      println("Test 3 passed: target == rebase rejected")

      // --- Test 4: datasources on different servers are rejected ---
      val pg2 = startEmbeddedPostgres()
      try {
        val otherAdmin = pg2.getPostgresDatabase().getConnection
        try {
          otherAdmin.createStatement().execute("CREATE DATABASE other_target")
        } finally {
          otherAdmin.close()
        }
        val otherTargetDs = pg2.getDatabase("postgres", "other_target")
        try {
          new Rebaser(otherTargetDs, rebaseDs, SupportedDatabase.Postgres).rebase()
          throw new AssertionError("Expected IllegalArgumentException for different servers")
        } catch {
          case e: IllegalArgumentException if e.getMessage.contains("same Postgres server") =>
          // expected
        }
        println("Test 4 passed: different servers rejected")
      } finally {
        pg2.close()
      }

      println("All Rebaser tests passed!")
    } finally {
      pg.close()
    }
  }
}
