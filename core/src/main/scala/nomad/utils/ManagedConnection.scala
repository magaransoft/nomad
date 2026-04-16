package nomad.utils

import java.sql.Connection

/** A connection wrapper that prevents user code from interfering with the
  * transaction lifecycle managed by the [[nomad.Migrator]].
  *
  * Exports all safe methods from the underlying connection while omitting
  * `close`, `commit`, `setAutoCommit`, `setReadOnly`, and `setTransactionIsolation`.
  */
class ManagedConnection private[nomad] (wrapped: Connection) {

  export wrapped.{ close as _, commit as _, setAutoCommit as _, setReadOnly as _, setTransactionIsolation as _, * }

  /** Returns the underlying connection cast to the requested type.
    *
    * This is intended for cases where database-specific connection features are needed
    * (e.g., `PGConnection` for PostgreSQL COPY operations or large object support).
    *
    * '''Use with caution:''' the underlying connection is managed by the [[nomad.Migrator]],
    * which controls its transaction lifecycle. Calling `close()`, `commit()`, `setAutoCommit()`,
    * `rollback()`, or similar transaction-control methods on the returned connection will
    * interfere with the Migrator and may cause migrations to be partially applied or lost.
    *
    * @tparam A the expected connection type
    * @return the underlying connection cast to `A`
    * @throws ClassCastException if the underlying connection is not an instance of `A`
    */
  def unsafeUnderlyingAs[A]: A = wrapped.asInstanceOf[A]
}
