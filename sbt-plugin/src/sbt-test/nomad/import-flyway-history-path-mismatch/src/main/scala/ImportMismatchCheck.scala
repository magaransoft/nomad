import nomad.{Migrator, SupportedDatabase}
import org.h2.jdbcx.JdbcDataSource

import java.sql.Timestamp
import java.time.Instant

object ImportMismatchCheck {
  def main(args: Array[String]): Unit = {
    val ds = new JdbcDataSource()
    ds.setURL("jdbc:h2:file:./target/testdb-path-mismatch;AUTO_SERVER=TRUE")

    prepareFlywayHistory(ds)

    val migrator = new Migrator(ds, SupportedDatabase.H2, Vector.empty)
    val importedPaths = Array(
      "migrations/phoenix/V1.sql",
      "migrations/phoenix/V2.sql"
    )
    val mixedImportedPaths = Array(
      "default/V1.sql",
      "migrations/phoenix/V2.sql"
    )

    val mixedAnalysis = migrator.analyzeFlywayHistoryImport("flyway_schema_history", mixedImportedPaths)
    assert(mixedAnalysis.exactMatchCount == 1, s"Expected 1 exact match, got ${mixedAnalysis.exactMatchCount}")
    assert(
      mixedAnalysis.remappableMismatchCount == 1,
      s"Expected 1 remappable mismatch, got ${mixedAnalysis.remappableMismatchCount}"
    )

    val analysis = migrator.analyzeFlywayHistoryImport("flyway_schema_history", importedPaths)
    assert(analysis.exactMatchCount == 0, s"Expected no exact matches, got ${analysis.exactMatchCount}")
    assert(
      analysis.remappableMismatchCount == 2,
      s"Expected 2 remappable mismatches, got ${analysis.remappableMismatchCount}"
    )
    assert(analysis.missingCount == 0, s"Expected no missing scripts, got ${analysis.missingCount}")
    assert(analysis.ambiguousCount == 0, s"Expected no ambiguous scripts, got ${analysis.ambiguousCount}")
    assert(analysis.hasOnlyPathMismatches, "Expected analysis to identify pure path mismatches")

    val importedCount = migrator.importFlywayHistory("flyway_schema_history", importedPaths, remapMismatchedScripts = true)
    assert(importedCount == 2, s"Expected 2 imported rows, got $importedCount")

    val conn = ds.getConnection
    try {
      val rs = conn.createStatement().executeQuery(
        "SELECT name, installed_rank FROM nomad_migrations ORDER BY installed_rank"
      )
      val rows = Vector.newBuilder[(String, Int)]
      while (rs.next()) {
        rows += ((rs.getString("name"), rs.getInt("installed_rank")))
      }
      rs.close()

      assert(
        rows.result() == Vector(
          "migrations/phoenix/V1.sql" -> 1,
          "migrations/phoenix/V2.sql" -> 2
        ),
        s"Unexpected imported rows: ${rows.result()}"
      )
    } finally {
      conn.close()
    }
  }

  private def prepareFlywayHistory(ds: JdbcDataSource): Unit = {
    val conn = ds.getConnection
    try {
      val stmt = conn.createStatement()
      stmt.execute("DROP ALL OBJECTS")
      stmt.execute(
        """CREATE TABLE flyway_schema_history (
          |  installed_rank INT PRIMARY KEY,
          |  version VARCHAR(50),
          |  description VARCHAR(200) NOT NULL,
          |  type VARCHAR(20) NOT NULL,
          |  script VARCHAR(1000) NOT NULL,
          |  checksum INT,
          |  installed_by VARCHAR(100) NOT NULL,
          |  installed_on TIMESTAMP NOT NULL,
          |  execution_time INT NOT NULL,
          |  success BOOLEAN NOT NULL
          |)""".stripMargin
      )
      stmt.close()

      val ps = conn.prepareStatement(
        """INSERT INTO flyway_schema_history
          |(installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
      )

      insertRow(ps, 1, "1", "Create users", "default/V1.sql", 15)
      insertRow(ps, 2, "2", "Add email", "default/V2.sql", 11)
      ps.close()
    } finally {
      conn.close()
    }
  }

  private def insertRow(
    ps: java.sql.PreparedStatement,
    installedRank: Int,
    version: String,
    description: String,
    script: String,
    executionTime: Int
  ): Unit = {
    ps.setInt(1, installedRank)
    ps.setString(2, version)
    ps.setString(3, description)
    ps.setString(4, "SQL")
    ps.setString(5, script)
    ps.setInt(6, 0)
    ps.setString(7, "test")
    ps.setTimestamp(8, Timestamp.from(Instant.parse("2026-03-25T00:00:00Z")))
    ps.setInt(9, executionTime)
    ps.setBoolean(10, true)
    ps.executeUpdate()
  }
}
