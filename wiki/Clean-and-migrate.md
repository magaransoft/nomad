<p align="center"><a href="Table-of-Contents.md">Table of Contents</a></p>
<p align="center"><a href="Migration-status.md">Migration Status</a> &lt; Clean and Migrate &gt; <a href="Database-and-schema-support.md">Database and Schema Support</a></p>

For development and testing, `cleanAndMigrate` drops the entire schema and re-runs all migrations from scratch:

```scala
val migrator = new Migrator(datasource, SupportedDatabase.Postgres, migrations, schema = "public")
migrator.cleanAndMigrate()
```

Before dropping, it checks that a Nomad history table exists in the schema to prevent accidental destruction of a schema not managed by Nomad. If the schema is empty, the drop is skipped and migrations are applied directly.

> **Note:** `cleanAndMigrate` is a method on `Migrator`, not an sbt task. It is intended to be called from your own code (e.g., test setup) where you can add any additional guardrails appropriate for your environment.

Up next: [Database and schema support](Database-and-schema-support.md)
