lazy val root = (project in file("."))
  .settings(
    name := "test-migrate-scala",
    version := "0.1.0",
    scalaVersion := "3.8.2",
    libraryDependencies ++= Seq(
      "com.magaran" %% "nomad-core" % sys.props("plugin.version"),
      "org.slf4j" % "slf4j-simple" % "2.0.17",
      "com.h2database" % "h2" % "2.3.232"
    )
  )
