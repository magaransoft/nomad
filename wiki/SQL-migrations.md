<p align="center"><a href="Table-of-Contents.md">Table of Contents</a></p>
<p align="center"><a href="Getting-started.md">Getting Started</a> &lt; SQL Migrations &gt; <a href="Scala-migrations.md">Scala Migrations</a></p>

SQL migrations are the primary way to define schema changes. Each migration is a `.sql` file on the classpath, referenced in the manifest via `SQLMigration`:

```scala
import nomad.{Migration, SQLMigration}

def migrations: Vector[Migration] = Vector(
  SQLMigration("migrations/M202603210001_CreateUsers.sql"),
  SQLMigration("migrations/M202603210002_AddEmailColumn.sql"),
  SQLMigration("migrations/M202603220001_CreateOrders.sql")
)
```

Migrations are applied in the order they appear in the manifest. Each migration runs in its own transaction and is recorded in a history table with a SHA-256 checksum. If a previously applied migration file is modified, Nomad will detect the checksum mismatch and refuse to proceed.

`SQLMigration` keeps IntelliJ IDEA file-reference navigation on the string argument, so you can still jump directly from the manifest to the SQL file.

Non-transactional Migrations
============================

Some SQL statements (e.g., `CREATE INDEX CONCURRENTLY` in Postgres) cannot run inside a transaction. For these cases, set `transactional = false`:

```scala
SQLMigration("migrations/M202603230001_AddIndex.sql", transactional = false)
```

Non-transactional migrations run with auto-commit enabled — each SQL statement in the file is executed individually and committed immediately. If the migration fails partway through, any statements already executed **cannot be rolled back** and the database may be left in an inconsistent state. Status output marks these migrations with a bold teal `non-transactional` label.

Up next: [Scala migrations](Scala-migrations.md)
