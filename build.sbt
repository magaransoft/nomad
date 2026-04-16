import ReleaseTransformations.*

ThisBuild / organization := "com.magaran"
ThisBuild / versionScheme := Some("semver-spec")
ThisBuild / pgpSigningKey := Some("E20C31E38E557DEEC82006986DC61FDE08C36DE2")

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

// Scala 3 compiler options (applies to core via ThisBuild)
val globalCrossModuleOptions = Vector(
  "-feature",
  "-preview",
  "-Xmax-inlines",
  "64",
  "-Wunused:all",
  "-Wnonunit-statement",
  "-Werror",
  "-Yexplicit-nulls",
  "-language:strictEquality"
)
val excludedOptionsFromTesting = Set("-Wnonunit-statement")

ThisBuild / scalacOptions := globalCrossModuleOptions
Test / scalacOptions      := globalCrossModuleOptions.filterNot(excludedOptionsFromTesting.contains)

// Scala 2.12 equivalents for the sbt plugin
val pluginScalacOptions = Vector(
  "-feature",
  "-Xfatal-warnings",
  "-Ywarn-unused:imports",
  "-Ywarn-value-discard"
)
val excludedPluginOptionsFromTesting = Set("-Ywarn-value-discard")

lazy val publishSettings = Seq(
  description := "Manifest-based database migration library for Scala",
  licenses := Seq("MIT" -> url("https://github.com/magaransoft/nomad/blob/main/LICENSE")),
  homepage := Some(url("https://github.com/magaransoft/nomad")),
  scmInfo := Some(
    ScmInfo(
      browseUrl = url("https://github.com/magaransoft/nomad"),
      connection = "scm:git@github.com:magaransoft/nomad.git"
    )
  ),
  developers := List(
    Developer(
      id = "NovaMage",
      name = "Ángel Felipe Blanco Guzmán",
      email = "novamage@magaran.com",
      url = url("https://github.com/NovaMage")
    )
  ),
  publishMavenStyle := true,
  publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true),
  publishConfiguration := publishConfiguration.value.withOverwrite(false),
  Test / publishArtifact := false,
  pomIncludeRepository := { _ => false }
)

lazy val root = (project in file("."))
  .aggregate(core, sbtPlugin)
  .settings(
    name := "nomad",
    publish / skip := true,
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      releaseStepCommand("publishSigned"),
      releaseStepCommand("sonaUpload"),
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  )

lazy val core = (project in file("core"))
  .settings(publishSettings)
  .settings(
    name := "nomad-core",
    scalaVersion := "3.8.2",
    libraryDependencies ++= Seq(
      "org.jetbrains" % "annotations" % "26.0.2",
      "org.slf4j" % "slf4j-api" % "2.0.17",
      "org.slf4j" % "slf4j-simple" % "2.0.17" % Test
    )
  )

lazy val sbtPlugin = (project in file("sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .settings(publishSettings)
  .settings(
    name := "sbt-nomad",
    scalaVersion := "2.12.20",
    scalacOptions := pluginScalacOptions,
    Test / scalacOptions := pluginScalacOptions.filterNot(excludedPluginOptionsFromTesting.contains),
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++ Seq(
        "-Xmx1024M",
        "-Dorg.slf4j.simpleLogger.logFile=System.out",
        s"-Dplugin.version=${version.value}"
      )
    },
    scriptedBufferLog := false,
    scriptedBatchExecution := true,
    scripted := scripted.evaluated,
    scriptedDependencies := {
      scriptedDependencies.value
      (core / publishLocal).value
    }
  )
