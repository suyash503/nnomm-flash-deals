package com.nnomm.deals

import java.sql.Connection
import java.util.UUID

class Expiry(private val db: Db) {

    /**
     * Sweeps every deal. Both instances call this every second; the advisory lock lets only one of
     * them sweep at a time, so two sweepers never lock the same deal rows in different orders (deadlock).
     */
    suspend fun sweepAll(): Int = db.tx { conn ->
        val gotLock = conn.queryOne("SELECT pg_try_advisory_xact_lock(?)", SWEEPER_LOCK_ID) { it.getBoolean(1) }!!
        if (gotLock) expireDue(conn, dealId = null) else 0
    }

    /** Expires one deal's due claims. Called before a claim so an expired customer can claim again at once. */
    suspend fun expireDueForDeal(dealId: UUID): Int = db.tx { conn -> expireDue(conn, dealId) }

    /**
     * One statement: lock due RESERVED claims, mark them EXPIRED, and add their units back to their deals.
     * SKIP LOCKED passes over claims a webhook is processing right now; whichever transaction holds the
     * claim's row lock decides its fate, and the other sees the result and backs off.
     */
    private fun expireDue(conn: Connection, dealId: UUID?): Int = conn.queryOne(
        """
        WITH due AS (
            SELECT id FROM claims
            WHERE status = 'RESERVED'
              AND expires_at <= clock_timestamp()
              AND (CAST(? AS uuid) IS NULL OR deal_id = CAST(? AS uuid))
            ORDER BY expires_at
            LIMIT $BATCH_SIZE
            FOR UPDATE SKIP LOCKED
        ),
        expired AS (
            UPDATE claims c
            SET status = 'EXPIRED', updated_at = clock_timestamp()
            FROM due
            WHERE c.id = due.id
            RETURNING c.deal_id, c.quantity
        ),
        restocked AS (
            UPDATE deals d
            SET stock_available = d.stock_available + t.units
            FROM (SELECT deal_id, sum(quantity) AS units FROM expired GROUP BY deal_id) t
            WHERE d.id = t.deal_id
            RETURNING d.id
        )
        SELECT count(*) FROM expired
        """,
        dealId, dealId,
    ) { it.getInt(1) }!!

    companion object {
        const val BATCH_SIZE = 500
        private const val SWEEPER_LOCK_ID = 7_310_001L
    }
}
