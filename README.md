# Nomad [![Tests & Docs](https://github.com/magaransoft/nomad/actions/workflows/ci.yml/badge.svg)](https://github.com/magaransoft/nomad/actions/workflows/ci.yml)


A database migration library for Scala 3, with an sbt plugin for workflow automation.

Nomad's approach is manifest-based: migrations are declared in a **Scala file** that explicitly defines their order, giving you full control and compile-time visibility over your migration history.

When multiple developers merge migrations into the same branch, convention-based tools often produce silent ordering conflicts -- or worse, the migrator blows up in CI with mismatched checksums or out-of-order migrations. With Nomad, concurrent migrations become a **simple line conflict in the manifest** (`Nomad.scala`), which is easy to spot and resolve with git or any VCS.

## Quick Start

Nomad is published to Maven Central. Add the plugin to `project/plugins.sbt`:

```scala
addSbtPlugin("com.magaran" % "sbt-nomad" % "0.1.0")
```

Add the core library to your `build.sbt`:

```scala
libraryDependencies += "com.magaran" %% "nomad-core" % "0.1.0"
```

Initialize the manifest, create your first migration, and run it:

```
sbt nomadInit
sbt nomadCreate CreateUsersTable
sbt nomadMigrate
```

Check the [wiki](wiki/Table-of-Contents.md) for full documentation, including [installation details](wiki/Getting-started.md), [SQL](wiki/SQL-migrations.md) and [Scala](wiki/Scala-migrations.md) migrations, [Flyway import](wiki/Importing-from-Flyway.md), and more.

## License

MIT
