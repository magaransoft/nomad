<p align="center"><a href="Table-of-Contents.md">Table of Contents</a></p>
<p align="center">Getting Started &gt; <a href="SQL-migrations.md">SQL Migrations</a></p>

Nomad is published to Maven Central. Add the plugin to `project/plugins.sbt`:

```scala
addSbtPlugin("com.magaran" % "sbt-nomad" % "0.1.0")
```

The plugin **must** be in `project/plugins.sbt` because sbt resolves plugins before reading `build.sbt`.

Add the core library to your `build.sbt`:

```scala
libraryDependencies += "com.magaran" %% "nomad-core" % "0.1.0"
```

Quick Start
===========

Initialize the manifest:

```
sbt nomadInit
```

This creates `src/main/scala/Nomad.scala`:

```scala
import nomad.{Migration, NomadMigrations, SupportedDatabase}
import javax.sql.DataSource

object Nomad extends NomadMigrations {
  def database: SupportedDatabase = {
    ???
  }

  // This datasource is used by sbt tasks (nomadMigrate, nomadStatus).
  // If you use Migrator directly from code, you can pass a different datasource there.
  def datasource: DataSource = {
    ???
  }

  // Your migrations are automatically added here when you run nomadCreate,
  // only edit this file to solve conflicts.
  def migrations: Vector[Migration] = Vector(
  )
}
```

Fill in your database type (e.g., `SupportedDatabase.Postgres` or `SupportedDatabase.H2`) and datasource, then create your first migration:

```
sbt nomadCreate CreateUsersTable
```

This generates a timestamped SQL file (e.g., `src/main/resources/migrations/M202603210842_CreateUsersTable.sql`) and automatically adds it to the manifest. Write your SQL, then run:

```
sbt nomadMigrate
```

Up next: [SQL migrations](SQL-migrations.md)
