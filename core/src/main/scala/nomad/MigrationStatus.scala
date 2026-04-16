package nomad

/** The status of a migration relative to the database history. */
enum MigrationStatus derives CanEqual {

  /** The migration has been applied and its checksum matches. */
  case Applied

  /** The migration is in the manifest but has not been applied yet. */
  case Pending

  /** The migration was applied but the file has been modified since (checksum differs). */
  case BadChecksum

  /** The migration resource is completely gone from the classpath. */
  case Missing

  /** The migration resource exists on the classpath but is not listed in the manifest. */
  case NotInManifest

  /** The migration resource exists and is in the manifest, but at a different position than its installed rank. */
  case OutOfOrder
}

/** A single entry in the migration status report.
  *
  * @param status the status of this migration
  * @param name the resource path or class name of the migration
  * @param displayName the human-readable name shown in status output
  * @param appliedChecksum the checksum recorded when the migration was applied, if any
  * @param currentChecksum the checksum of the current file on the classpath, if available
  * @param transactional whether this migration runs inside a transaction
  */
case class MigrationEntry(
  status: MigrationStatus,
  name: String,
  displayName: String,
  appliedChecksum: Option[String],
  currentChecksum: Option[String],
  transactional: Boolean = true
)
