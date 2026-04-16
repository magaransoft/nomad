<p align="center"><a href="Table-of-Contents.md">Table of Contents</a></p>
<p align="center"><a href="Database-and-schema-support.md">Database and Schema Support</a> &lt; GraalVM Native Image Support &gt; <a href="sbt-reference.md">sbt Reference</a></p>

If you're building a GraalVM native image, enable automatic resource registration:

```scala
// build.sbt
nomadGraalSync := true
```

This generates and maintains a `resource-config.json` file that includes your migration SQL files, so they're available at runtime in the native binary. The file is updated automatically when you run `nomadCreate` or `nomadMigrate`.

Up next: [sbt reference](sbt-reference.md)
