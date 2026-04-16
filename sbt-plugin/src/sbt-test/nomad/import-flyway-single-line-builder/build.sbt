lazy val root = (project in file("."))
  .settings(
    name := "test-import-flyway-single-line-builder",
    version := "0.1.0",
    scalaVersion := "3.8.2",
    libraryDependencies += "com.magaran" %% "nomad-core" % sys.props("plugin.version"),
    TaskKey[Unit]("checkManifest") := {
      val content = IO.read(nomadManifestFile.value)
      val lines = content.linesIterator.toVector

      val vectorLine = lines.indexWhere(_.contains("Vector("))
      val sqlLines = lines.zipWithIndex.filter(_._1.trim.startsWith("SQLMigration("))

      assert(vectorLine >= 0, "Expected manifest to use a Vector(...) definition")
      assert(sqlLines.size == 2, s"Expected 2 imported SQLMigration lines, got ${sqlLines.size}")
      assert(sqlLines.forall { case (_, idx) => idx > vectorLine }, "Expected all SQLMigration lines to appear inside the vector block")
    }
  )
