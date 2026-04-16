<p align="center"><a href="Table-of-Contents.md">Table of Contents</a></p>
<p align="center"><a href="Clean-and-migrate.md">Clean and Migrate</a> &lt; Database and Schema Support &gt; <a href="GraalVM-native-image-support.md">GraalVM Native Image Support</a></p>

Supported Databases
===================

Nomad currently supports PostgreSQL and H2. The database type is specified via `SupportedDatabase`:

```scala
def database: SupportedDatabase = SupportedDatabase.Postgres
// or
def database: SupportedDatabase = SupportedDatabase.H2
```

The database type controls database-specific behavior such as DDL generation for the history table (e.g., PostgreSQL uses a native ENUM type for the migration type column, while H2 uses a VARCHAR with a CHECK constraint) and schema operations in `cleanAndMigrate`.

Schema Support
==============

The `Migrator` accepts a `schema` parameter to target a specific database schema:

```scala
val migrator = new Migrator(datasource, SupportedDatabase.Postgres, migrations, schema = "my_schema")
migrator.migrate()
```

Defaults to `"public"`. The history table and all migrations will execute within the specified schema.

Up next: [GraalVM native image support](GraalVM-native-image-support.md)
