lazy val root = (project in file("."))
  .aggregate(app, helper)
  .settings(
    name := "test-import-flyway-scoped-no-aggregate",
    version := "0.1.0"
  )

lazy val app = (project in file("app"))
  .settings(
    name := "app",
    version := "0.1.0",
    scalaVersion := "3.8.2",
    libraryDependencies += "com.magaran" %% "nomad-core" % sys.props("plugin.version"),
    TaskKey[Unit]("checkManifest") := {
      val content = IO.read(nomadManifestFile.value)
      val sqlLines = content.linesIterator.filter(_.trim.startsWith("SQLMigration(")).toVector
      assert(sqlLines.size == 2, s"Expected 2 SQLMigration lines, got ${sqlLines.size}")
    }
  )

lazy val helper = (project in file("helper"))
  .settings(
    name := "helper",
    version := "0.1.0",
    scalaVersion := "3.8.2"
  )
