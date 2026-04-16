lazy val root = (project in file("."))
  .settings(
    name := "test-init-custom-file",
    version := "0.1.0",
    scalaVersion := "3.8.2",
    nomadManifestFile := (Compile / scalaSource).value / "db" / "MyMigrations.scala",
    TaskKey[Unit]("checkManifest") := {
      val content = IO.read(nomadManifestFile.value)
      assert(content.contains("package db"), "Expected generated manifest to declare package db")
      assert(content.contains("object MyMigrations extends NomadMigrations"), "Expected generated manifest object name to match file name")
    }
  )
