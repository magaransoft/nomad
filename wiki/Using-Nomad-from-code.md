<p align="center"><a href="Table-of-Contents.md">Table of Contents</a></p>
<p align="center"><a href="sbt-reference.md">sbt Reference</a> &lt; Using Nomad from Code &gt; <a href="Importing-from-Flyway.md">Importing from Flyway</a></p>

The sbt plugin handles the common workflow, but you can also use the `Migrator` directly:

```scala
import nomad.{Migrator, NomadMigrations, SupportedDatabase}

// Using the manifest
val manifest = Nomad // your NomadMigrations implementation
val migrator = new Migrator(
  datasource = manifest.datasource,
  db = manifest.database,
  migrations = manifest.migrations,
  historyTable = "nomad_migrations", // default — name of the history table
  schema = "public"                  // default — target database schema
)

migrator.migrate()               // Run pending migrations
migrator.status()                // Returns Vector[MigrationEntry]
migrator.printStatus()           // Prints status table, returns true if problems found
migrator.cleanAndMigrate()       // Drop schema and re-run all (dev/test only)
```

The `historyTable` and `schema` parameters are optional and default to `"nomad_migrations"` and `"public"` respectively.

Up next: [Importing from Flyway](Importing-from-Flyway.md)
