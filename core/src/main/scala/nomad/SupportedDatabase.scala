package nomad

/** Enumerates the databases supported by Nomad for database-specific operations
  * such as [[Migrator.cleanAndMigrate]].
  */
enum SupportedDatabase derives CanEqual {

  /** PostgreSQL database. */
  case Postgres

  /** H2 database. */
  case H2
}
