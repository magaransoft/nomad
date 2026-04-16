<p align="center"><a href="Table-of-Contents.md">Table of Contents</a></p>
<p align="center"><a href="Scala-migrations.md">Scala Migrations</a> &lt; Migration Status &gt; <a href="Clean-and-migrate.md">Clean and Migrate</a></p>

Check the state of your migrations:

```
sbt nomadStatus
```

```
+---------+--------------+
| Status  | Migration    |
+---------+--------------+
| applied | CreateUsers  |
| applied | AddEmail     |
| pending | CreateOrders |
+---------+--------------+
2 applied, 1 pending.
```

If problems are detected, a detail table with checksums is displayed and the task fails. Each migration has a `MigrationStatus`:

| Status | Meaning |
|---|---|
| `Applied` | Successfully applied and checksum matches |
| `Pending` | Listed in the manifest but not yet applied |
| `BadChecksum` | The migration file was modified after being applied |
| `Missing` | The migration resource no longer exists on the classpath |
| `NotInManifest` | Recorded in the history table but not listed in the manifest |
| `OutOfOrder` | Listed in the manifest but at a different position than when it was applied |

```
+-----------------+------------------------------------------+------------------------------------------------------------------+------------------------------------------------------------------+
| Status          | Migration                                | Applied checksum                                                 | Current checksum                                                 |
+-----------------+------------------------------------------+------------------------------------------------------------------+------------------------------------------------------------------+
| bad checksum    | AddEmail                                 | a3f2b9...                                                        | 7c1e4d...                                                        |
| not in manifest | migrations/M202603210001_CreateUsers.sql | 5e8f1a...                                                        | -                                                                |
+-----------------+------------------------------------------+------------------------------------------------------------------+------------------------------------------------------------------+
```

The `nomadMigrate` task also validates applied migrations before running and fails with descriptive error messages if any problems are found.

Up next: [Clean and migrate](Clean-and-migrate.md)
