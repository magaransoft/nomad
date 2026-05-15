<p align="center"><a href="Table-of-Contents.md">Table of Contents</a></p>
<p align="center"><a href="GraalVM-native-image-support.md">GraalVM Native Image Support</a> &lt; sbt Reference &gt; <a href="Using-Nomad-from-code.md">Using Nomad from Code</a></p>

Tasks
=====

| Task | Description |
|---|---|
| `nomadInit` | Create the migration manifest file |
| `nomadCreate <description>` | Create a new timestamped SQL migration and add it to the manifest |
| `nomadMigrate` | Run all pending migrations |
| `nomadStatus` | Show migration status (fails if problems detected) |
| `nomadImportFlyway` | Import Flyway versioned migrations into the manifest and optionally import schema history |
| `nomadRebase` | Drop the target database and re-clone it from a long-lived rebase database via a fast Postgres `CREATE DATABASE ... WITH TEMPLATE` copy, then run any pending migrations. Postgres only; requires `rebaseDatasource` to be set on the manifest. See [Rebase](Rebase.md). |

Settings
========

| Setting | Default | Description |
|---|---|---|
| `nomadMigrationsDir` | `src/main/resources/migrations` | Directory for SQL migration files |
| `nomadStampFormat` | `"yyyyMMddHHmm"` | Timestamp format for migration file names |
| `nomadManifestFile` | `src/main/scala/Nomad.scala` | Path to the manifest file |
| `nomadManifestClass` | `"Nomad"` | Fully qualified class name of the manifest object |
| `nomadHistoryTable` | `"nomad_migrations"` | Name of the history table |
| `nomadGraalSync` | `false` | Auto-sync migration resources to GraalVM resource-config.json |
| `nomadGraalResourceConfigFile` | `src/main/resources/META-INF/native-image/resource-config.json` | Path to the GraalVM resource config |

Up next: [Using Nomad from code](Using-Nomad-from-code.md)
