lazy val root = (project in file("."))
  .settings(
    name := "test-create-custom-format",
    version := "0.1.0",
    scalaVersion := "3.8.2",
    nomadStampFormat := "yyyyMMdd"
  )
