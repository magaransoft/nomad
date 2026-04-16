<p align="center"><a href="Table-of-Contents.md">Table of Contents</a></p>
<p align="center"><a href="SQL-migrations.md">SQL Migrations</a> &lt; Scala Migrations &gt; <a href="Migration-status.md">Migration Status</a></p>

For migrations that require programmatic logic (data transformations, conditional inserts, external API calls), extend `ScalaMigration`:

```scala
import nomad.ScalaMigration
import nomad.utils.ManagedConnection

class SeedUsers extends ScalaMigration {
  def execute(conn: ManagedConnection): Unit = {
    val stmt = conn.createStatement()
    stmt.execute("INSERT INTO users (name) VALUES ('admin')")
    stmt.close()
  }
}
```

Add it to the manifest with `ScalaMigrationDef`:

```scala
import nomad.{Migration, SQLMigration, ScalaMigrationDef}

def migrations: Vector[Migration] = Vector(
  SQLMigration("migrations/M202603210001_CreateUsers.sql"),
  ScalaMigrationDef(new SeedUsers())
)
```

For migrations that need constructor arguments, pass an instance directly:

```scala
ScalaMigrationDef(SeedUsers(environment = "production", batchSize = 1000))
```

ManagedConnection
=================

Scala migrations receive a `ManagedConnection` instead of a raw JDBC `Connection`. This wrapper exports all standard JDBC methods but prevents accidental calls to `close()`, `commit()`, `setAutoCommit()`, `setReadOnly()`, and `setTransactionIsolation()` -- the Migrator controls the transaction lifecycle.

If you need access to database-specific connection features (e.g., PostgreSQL's `PGConnection`), use the escape hatch:

```scala
val pgConn = conn.unsafeUnderlyingAs[PGConnection]
```

**Use with caution:** calling transaction-control methods on the underlying connection will interfere with the Migrator.

Custom Description
==================

By default, the status table displays the class name. Override `description` for a custom label:

```scala
class SeedUsers extends ScalaMigration {
  override def description: String = "Seed initial user accounts"
  def execute(conn: ManagedConnection): Unit = { /* ... */ }
}
```

Up next: [Migration status](Migration-status.md)
