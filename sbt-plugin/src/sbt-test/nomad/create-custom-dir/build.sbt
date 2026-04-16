lazy val root = (project in file("."))
  .settings(
    name := "test-create-custom-dir",
    version := "0.1.0",
    scalaVersion := "3.8.2",
    nomadMigrationsDir := baseDirectory.value / "custom-migrations"
  )
