lazy val root = (project in file("."))
  .settings(
    name := "test-create-indent-deep",
    version := "0.1.0",
    scalaVersion := "3.8.2",
    nomadStampFormat := "yyyyMMddHHmmss",
    TaskKey[Unit]("checkIndentation") := {
      val content = IO.read(nomadManifestFile.value)
      val lines = content.linesIterator.toVector
      val migrationLines = lines.filter(_.trim.startsWith("SQLMigration("))
      assert(migrationLines.nonEmpty, "No SQLMigration lines found in manifest")
      val indents = migrationLines.map(l => l.takeWhile(_.isWhitespace))
      assert(
        indents.distinct.size == 1,
        s"Inconsistent indentation:\n${migrationLines.map(l => s"'${l.takeWhile(_.isWhitespace)}' -> ${l.trim}").mkString("\n")}"
      )
      // Verify the deep indentation (10 spaces) is preserved
      assert(indents.head == "          ", s"Expected 10-space indentation but got '${indents.head}' (${indents.head.length} chars)")
      assert(migrationLines.size == 2, s"Expected 2 SQLMigration lines, got ${migrationLines.size}")
    }
  )
