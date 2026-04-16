<p align="center"><a href="Table-of-Contents.md">Table of Contents</a></p>
<p align="center"><a href="Using-Nomad-from-code.md">Using Nomad from Code</a> &lt; Importing from Flyway &gt; <a href="Design-decisions.md">Design Decisions</a></p>

If you're migrating an existing project from Flyway, Nomad can import your versioned migrations and optionally their schema history:

```
sbt nomadInit
sbt nomadImportFlyway
```

This scans your resource directories for Flyway-style versioned migrations (`V1__CreateUsers.sql`, `V2.1__AddIndex.sql`, etc.), sorts them by version number, and adds `SQLMigration("...")` entries to the manifest in the correct order. Migrations already listed in the manifest are skipped, so the command is idempotent.

The SQL files are referenced at their original location -- they are not copied or renamed. This makes it safe to run alongside an existing Flyway setup during a gradual transition.

Importing Flyway Schema History
===============================

After adding migrations to the manifest, `nomadImportFlyway` will ask if you want to import your Flyway schema history into Nomad's history table. If you answer yes, it will then prompt for the Flyway history table name and default to `flyway_schema_history` when you press ENTER. This is useful for production databases that already have Flyway migrations applied -- it lets Nomad know which migrations have already been run so it won't try to re-apply them.

The import reads Flyway's `flyway_schema_history` table (or a custom table name you provide), matches each entry to its manifest resource path, and computes Nomad's SHA-256 checksum from the actual SQL file. Flyway's `installed_by`, `installed_on`, `execution_time`, and `description` values are preserved. Only successful SQL migrations are imported, and the import only works into an empty Nomad history table.

If any Flyway script path does not match the current project layout but the file name still matches a current migration uniquely, Nomad will show one additional confirmation prompt before remapping those history rows to the current manifest paths. This is useful when migrating from a legacy project layout into a rewritten codebase.

Up next: [Design decisions](Design-decisions.md)
