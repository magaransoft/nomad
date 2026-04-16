package nomad

/** Formats migration status information as ASCII tables with ANSI colors. */
object StatusTable {

  private val Red = "\u001b[31m"
  private val LightBlue = "\u001b[94m"
  private val BoldTeal = "\u001b[1;36m"
  private val Reset = "\u001b[0m"

  private val NonTransactionalLabel = "non-transactional"

  private val problemStatuses: Set[MigrationStatus] = Set(
    MigrationStatus.BadChecksum,
    MigrationStatus.Missing,
    MigrationStatus.NotInManifest,
    MigrationStatus.OutOfOrder
  )

  /** Returns true if the given status represents a problem. */
  def isProblem(status: MigrationStatus): Boolean = problemStatuses.contains(status)

  /** Extracts a human-readable display name from a migration resource path.
    *
    * For a resource like "migrations/M202603210001_CreateUsers.sql", returns "CreateUsers".
    * If no description part exists (e.g., "migrations/M202603210001.sql"), returns the full path.
    *
    * @param resource the classpath-relative path to the migration file
    * @return the extracted description or the full path as a fallback
    */
  def extractDisplayName(resource: String): String = {
    val fileName = resource.substring(resource.lastIndexOf('/') + 1)
    val withoutExt = if (fileName.endsWith(".sql")) fileName.dropRight(4) else fileName
    val underscoreIdx = withoutExt.indexOf('_')
    if (underscoreIdx >= 0) withoutExt.substring(underscoreIdx + 1)
    else resource
  }

  /** Returns the label string for a given migration status. */
  private def statusLabel(status: MigrationStatus): String = status match {
    case MigrationStatus.Applied       => "applied"
    case MigrationStatus.Pending       => "pending"
    case MigrationStatus.BadChecksum   => "bad checksum"
    case MigrationStatus.Missing       => "missing"
    case MigrationStatus.NotInManifest => "not in manifest"
    case MigrationStatus.OutOfOrder    => "out of order"
  }

  /** Renders the main status table showing all migrations.
    *
    * @param entries the migration entries to display
    * @param noHistoryTable true if the history table does not exist yet
    * @return the formatted table string
    */
  def renderStatusTable(entries: Vector[MigrationEntry], noHistoryTable: Boolean = false): String = {
    if (entries.isEmpty) return "No migrations defined."

    val rows = entries.map { e =>
      val label = statusLabel(e.status)
      val display = e.status match {
        case MigrationStatus.Missing | MigrationStatus.NotInManifest | MigrationStatus.OutOfOrder => e.name
        case _ => e.displayName
      }
      (e.status, label, display, e.transactional)
    }

    val statusWidth = math.max("Status".length, rows.map(_._2.length).max)
    val nonTxNames = rows.collect { case (_, _, _, false) => NonTransactionalLabel.length }
    val migrationWidth = math.max("Migration".length, (rows.map(_._3.length) ++ nonTxNames).max)

    val sb = new StringBuilder
    val separator = s"+-${"-" * statusWidth}-+-${"-" * migrationWidth}-+"
    val _ = sb.append(separator).append('\n')
    val _ = sb.append(s"| ${"Status".padTo(statusWidth, ' ')} | ${"Migration".padTo(migrationWidth, ' ')} |").append('\n')
    val _ = sb.append(separator).append('\n')

    for ((status, label, display, transactional) <- rows) {
      val color = status match {
        case s if isProblem(s)         => Red
        case MigrationStatus.Pending   => LightBlue
        case _                         => ""
      }
      val reset = if (color.nonEmpty) Reset else ""
      val _ = sb.append(s"| $color${label.padTo(statusWidth, ' ')}$reset | $color${display.padTo(migrationWidth, ' ')}$reset |").append('\n')
      if (!transactional) {
        val _ = sb.append(s"| ${"".padTo(statusWidth, ' ')} | $BoldTeal${NonTransactionalLabel.padTo(migrationWidth, ' ')}$Reset |").append('\n')
      }
    }

    val _ = sb.append(separator).append('\n')

    val applied = entries.count(_.status == MigrationStatus.Applied)
    val pending = entries.count(_.status == MigrationStatus.Pending)
    val problems = entries.count(e => isProblem(e.status))

    if (noHistoryTable) {
      sb.append(s"No history table found. All ${entries.size} migrations are pending.")
    } else if (pending == 0 && problems == 0) {
      sb.append("All migrations applied.")
    } else {
      sb.append(s"$applied applied, $pending pending.")
    }

    sb.toString()
  }

  /** Renders the problems detail table for migrations with issues.
    *
    * @param entries the problem migration entries
    * @return the formatted problems table string
    */
  def renderProblemsTable(entries: Vector[MigrationEntry]): String = {
    if (entries.isEmpty) return ""

    val rows = entries.map { e =>
      val label = statusLabel(e.status)
      val display = e.status match {
        case MigrationStatus.Missing | MigrationStatus.NotInManifest | MigrationStatus.OutOfOrder => e.name
        case _ => e.displayName
      }
      val appliedCs = e.appliedChecksum.getOrElse("-")
      val currentCs = e.currentChecksum.getOrElse("-")
      (label, display, appliedCs, currentCs, e.transactional)
    }

    val statusWidth = math.max("Status".length, rows.map(_._1.length).max)
    val nonTxNames = rows.collect { case (_, _, _, _, false) => NonTransactionalLabel.length }
    val migrationWidth = math.max("Migration".length, (rows.map(_._2.length) ++ nonTxNames).max)
    val appliedCsWidth = math.max("Applied checksum".length, rows.map(_._3.length).max)
    val currentCsWidth = math.max("Current checksum".length, rows.map(_._4.length).max)

    val sb = new StringBuilder
    val separator = s"+-${"-" * statusWidth}-+-${"-" * migrationWidth}-+-${"-" * appliedCsWidth}-+-${"-" * currentCsWidth}-+"

    val _ = sb.append('\n').append("Problems detected:").append('\n')
    val _ = sb.append(separator).append('\n')
    val _ = sb.append(s"| ${"Status".padTo(statusWidth, ' ')} | ${"Migration".padTo(migrationWidth, ' ')} | ${"Applied checksum".padTo(appliedCsWidth, ' ')} | ${"Current checksum".padTo(currentCsWidth, ' ')} |").append('\n')
    val _ = sb.append(separator).append('\n')

    for ((label, display, appliedCs, currentCs, transactional) <- rows) {
      val _ = sb.append(s"| $Red${label.padTo(statusWidth, ' ')}$Reset | $Red${display.padTo(migrationWidth, ' ')}$Reset | ${appliedCs.padTo(appliedCsWidth, ' ')} | ${currentCs.padTo(currentCsWidth, ' ')} |").append('\n')
      if (!transactional) {
        val _ = sb.append(s"| ${"".padTo(statusWidth, ' ')} | $BoldTeal${NonTransactionalLabel.padTo(migrationWidth, ' ')}$Reset | ${"".padTo(appliedCsWidth, ' ')} | ${"".padTo(currentCsWidth, ' ')} |").append('\n')
      }
    }

    val _ = sb.append(separator)
    sb.toString()
  }

  /** Creates a [[MigrateTablePrinter]] for progressively printing migration results.
    *
    * Column widths are pre-computed from all possible rows (starting point + all pending migrations)
    * so the table renders correctly as rows are added one at a time.
    *
    * @param startingPoint the display name of the last previously applied migration, if any
    * @param pendingInfo display names and transactional flags of all pending migrations
    * @return a new printer ready to output the table
    */
  def migrateTablePrinter(
    startingPoint: Option[String],
    pendingInfo: Vector[(String, Boolean)],
    logger: org.slf4j.Logger
  ): MigrateTablePrinter = {
    val allStatuses = Vector("starting point", "applied", "bad checksum", "missing", "not in manifest", "out of order", "exception")
    val pendingDisplayNames = pendingInfo.map(_._1)
    val allNames = startingPoint.toVector ++ pendingDisplayNames
    val nonTxNames = pendingInfo.collect { case (_, false) => NonTransactionalLabel.length }
    val statusWidth = math.max("Status".length, allStatuses.map(_.length).max)
    val nameWidths = (if (allNames.isEmpty) Vector(0) else allNames.map(_.length)) ++ nonTxNames
    val migrationWidth = math.max("Migration".length, nameWidths.max)
    new MigrateTablePrinter(statusWidth, migrationWidth, logger)
  }
}

/** Progressively prints a migration table row by row.
  *
  * Prints the header on the first row, then each subsequent row as it's added.
  * Call [[close]] to print the closing separator.
  */
class MigrateTablePrinter(statusWidth: Int, migrationWidth: Int, logger: org.slf4j.Logger) {

  private val Red = "\u001b[31m"
  private val BoldTeal = "\u001b[1;36m"
  private val Reset = "\u001b[0m"
  private val NonTransactionalLabel = "non-transactional"
  private val separator = s"+-${"-" * statusWidth}-+-${"-" * migrationWidth}-+"
  private var headerPrinted = false

  private def ensureHeader(): Unit = {
    if (!headerPrinted) {
      logger.info(separator)
      logger.info(s"| ${"Status".padTo(statusWidth, ' ')} | ${"Migration".padTo(migrationWidth, ' ')} |")
      logger.info(separator)
      headerPrinted = true
    }
  }

  /** Prints a row with the given status and migration display name. */
  def printRow(status: String, displayName: String, color: String = "", transactional: Boolean = true): Unit = {
    ensureHeader()
    val reset = if (color.nonEmpty) Reset else ""
    logger.info(s"| $color${status.padTo(statusWidth, ' ')}$reset | $color${displayName.padTo(migrationWidth, ' ')}$reset |")
    if (!transactional) {
      logger.info(s"| ${"".padTo(statusWidth, ' ')} | $BoldTeal${NonTransactionalLabel.padTo(migrationWidth, ' ')}$Reset |")
    }
  }

  /** Prints the starting point row. */
  def printStartingPoint(displayName: String): Unit =
    printRow("starting point", displayName)

  /** Prints an applied migration row. */
  def printApplied(displayName: String, transactional: Boolean = true): Unit =
    printRow("applied", displayName, transactional = transactional)

  /** Prints a failed migration row with the given failure reason. */
  def printFailed(status: String, displayName: String, transactional: Boolean = true): Unit =
    printRow(status, displayName, Red, transactional)

  /** Prints the closing separator. */
  def close(): Unit = {
    if (headerPrinted) logger.info(separator)
  }

  /** Prints the closing separator and a message about remaining pending migrations. */
  def closeWithPendingMessage(): Unit = {
    close()
    logger.warn("More pending migrations were not applied due to the above failure.")
  }
}
