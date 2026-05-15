<p align="center"><a href="Table-of-Contents.md">Table of Contents</a></p>
<p align="center"><a href="Clean-and-migrate.md">Clean and Migrate</a> &lt; Rebase &gt; <a href="Database-and-schema-support.md">Database and Schema Support</a></p>

For development against large or expensive-to-restore databases (e.g., a production backup that takes ten minutes to load), Nomad supports a "rebase" workflow: keep a long-lived rebase database at a known-good point in time, and reset your working database from it on demand.

`nomadRebase` drops the target database and re-clones it from the rebase database via Postgres's `CREATE DATABASE target WITH TEMPLATE rebase`. The copy is a file-level operation, so it is dramatically faster than restoring from a logical dump. After cloning, any pending migrations are applied on top via the same logic as `nomadMigrate`.

## Configuration

Override `rebaseDatasource` on your manifest to point at the rebase database:

```scala
object Nomad extends NomadMigrations {
  def database: SupportedDatabase = SupportedDatabase.Postgres
  def datasource: DataSource = workingDatasource
  override def rebaseDatasource: Option[DataSource] = Some(rebaseDb)
  def migrations: Vector[Migration] = Vector(/* ... */)
}
```

Then run:

```
sbt nomadRebase
```

The task is destructive (it drops the entire target database), so it prompts for confirmation before proceeding. There is no flag to bypass the prompt — `nomadRebase` is a developer convenience and is not intended for CI or production.

## Constraints

`nomadRebase` enforces several invariants and fails with a clear error if any are violated:

- **Postgres only.** H2 is rejected. The fast-copy mechanism has no portable analog outside of Postgres.
- **Same server.** Both datasources must connect to the same Postgres cluster — host and port are parsed from each JDBC URL and must match exactly.
- **Single-host URLs.** Multi-host failover JDBC URLs (`jdbc:postgresql://h1:p1,h2:p2/db`) are rejected because the server identity cannot be determined unambiguously.
- **Target must exist.** Nomad does not create databases. If the target database does not exist yet, create it manually (or via your usual provisioning) before running `nomadRebase`.
- **Target ≠ rebase.** The two database names must differ; templating a database from itself is rejected.

## How the clone works

`nomadRebase` opens a single connection to the rebase datasource and, from there:

1. Terminates any other sessions on the target database via `pg_terminate_backend`.
2. Runs `DROP DATABASE IF EXISTS target`.
3. Terminates any other sessions on the rebase database (excluding its own session — Postgres permits the issuing session to remain connected to the template source).
4. Runs `CREATE DATABASE target WITH TEMPLATE rebase`.
5. Hands off to `Migrator.migrate()` against the original target datasource to apply any pending manifest entries.

The rebase database is never modified.

Up next: [Database and schema support](Database-and-schema-support.md)
