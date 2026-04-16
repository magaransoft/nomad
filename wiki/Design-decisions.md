<p align="center"><a href="Table-of-Contents.md">Table of Contents</a></p>
<p align="center"><a href="Importing-from-Flyway.md">Importing from Flyway</a> &lt; Design Decisions</p>

- **No rollback/down migrations.** Nomad is forward-only by design. If you need to undo a migration, write a new migration that reverses the changes.
- **Manifest-based ordering.** The manifest is the single source of truth for migration order. There is no filename-based sorting or convention -- you see exactly what will run and in what order.
- **Checksum validation.** SQL migration files are checksummed with SHA-256. If a file is modified after being applied, Nomad will refuse to migrate until the issue is resolved. Scala migrations are not checksummed since their bytecode changes with every recompile.
- **One transaction per migration.** Each migration (SQL or Scala) runs in its own transaction by default. If it fails, only that migration is rolled back. SQL migrations can opt out with `transactional = false` for statements that cannot run inside a transaction.
