lazy val root = (project in file("."))
  .settings(
    name := "test-import-flyway",
    version := "0.1.0",
    scalaVersion := "3.8.2",
    libraryDependencies += "com.magaran" %% "nomad-core" % sys.props("plugin.version"),
    TaskKey[Unit]("checkManifest") := {
      val content = IO.read(nomadManifestFile.value)
      val lines = content.linesIterator.toVector

      // Verify all three Flyway migrations are listed in version order
      val sqlLines = lines.filter(_.trim.startsWith("SQLMigration("))
      assert(sqlLines.size == 3, s"Expected 3 SQLMigration lines, got ${sqlLines.size}")

      val idx1 = content.indexOf("V1__CreateUsers.sql")
      val idx2 = content.indexOf("V2__AddEmail.sql")
      val idx3 = content.indexOf("V3.1__CreateOrders.sql")
      assert(idx1 >= 0, "V1__CreateUsers.sql not found in manifest")
      assert(idx2 >= 0, "V2__AddEmail.sql not found in manifest")
      assert(idx3 >= 0, "V3.1__CreateOrders.sql not found in manifest")
      assert(idx1 < idx2, "V1 should come before V2")
      assert(idx2 < idx3, "V2 should come before V3.1")

      // Verify indentation is consistent
      val indents = sqlLines.map(_.takeWhile(_.isWhitespace))
      assert(indents.distinct.size == 1, s"Inconsistent indentation: ${sqlLines.mkString("\n")}")
    },
    TaskKey[Unit]("checkIdempotent") := {
      val content = IO.read(nomadManifestFile.value)
      val sqlLines = content.linesIterator.filter(_.trim.startsWith("SQLMigration(")).toVector
      assert(sqlLines.size == 3, s"Expected 3 SQLMigration lines after second import, got ${sqlLines.size}")
    }
  )
