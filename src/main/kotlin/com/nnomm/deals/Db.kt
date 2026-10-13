package com.nnomm.deals

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.net.URI
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class Db(val dataSource: HikariDataSource) : AutoCloseable {

    /**
     * Runs [block] in one READ COMMITTED transaction on a pooled connection.
     * Commits if the block returns, rolls back if it throws. JDBC blocks, so it runs on the IO dispatcher.
     */
    suspend fun <T> tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                block(conn).also { conn.commit() }
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
    }

    override fun close() = dataSource.close()

    companion object {
        fun connect(config: Config): Db {
            val hikari = HikariConfig().apply {
                jdbcUrl = toJdbcUrl(config.databaseUrl)
                username = config.databaseUser
                password = config.databasePassword
                maximumPoolSize = 20
                // If this process dies mid-transaction, Postgres rolls the transaction back as soon as the
                // socket closes. This timeout covers the rarer case where the connection hangs open instead:
                // locks (including an in-flight Idempotency-Key) are freed within 30 s, under the 60 s limit.
                connectionInitSql = "SET idle_in_transaction_session_timeout = '30s'"
            }
            val dataSource = HikariDataSource(hikari)
            Flyway.configure().dataSource(dataSource).load().migrate()
            return Db(dataSource)
        }

        /** Accepts a JDBC URL as-is, or converts a postgres://user:pass@host:port/db URL into one. */
        fun toJdbcUrl(url: String): String {
            if (url.startsWith("jdbc:")) return url
            val uri = URI(url)
            require(uri.scheme == "postgres" || uri.scheme == "postgresql") { "Unsupported DATABASE_URL scheme" }
            val port = if (uri.port == -1) 5432 else uri.port
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return "jdbc:postgresql://${uri.host}:$port${uri.rawPath}$query"
        }
    }
}

// Small helpers so every query in the codebase is a plain, parameterised SQL string.

private fun bind(stmt: java.sql.PreparedStatement, params: Array<out Any?>) {
    params.forEachIndexed { i, p ->
        when (p) {
            is Instant -> stmt.setObject(i + 1, p.atOffset(ZoneOffset.UTC))
            else -> stmt.setObject(i + 1, p)
        }
    }
}

fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { stmt ->
        bind(stmt, params)
        stmt.executeUpdate()
    }

fun <T> Connection.queryList(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { stmt ->
        bind(stmt, params)
        stmt.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    queryList(sql, *params, map = map).firstOrNull()

fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

fun ResultSet.uuid(column: String): java.util.UUID = getObject(column, java.util.UUID::class.java)

/** Postgres SQLSTATE codes we react to. */
object SqlState {
    const val UNIQUE_VIOLATION = "23505"
    const val LOCK_NOT_AVAILABLE = "55P03"
}
