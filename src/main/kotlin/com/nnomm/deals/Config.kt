package com.nnomm.deals

data class Config(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val webhookSecret: String,
    val claimTtlSeconds: Long,
    val port: Int,
    // How often each instance looks for RESERVED claims past expires_at. Rule 4 allows 10 s; we aim for ~1 s.
    val sweepIntervalMillis: Long = 1000,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() }
                ?: error("Missing required environment variable $name")

            return Config(
                databaseUrl = required("DATABASE_URL"),
                databaseUser = required("DATABASE_USER"),
                databasePassword = required("DATABASE_PASSWORD"),
                jwtSecret = required("JWT_SECRET"),
                webhookSecret = required("WEBHOOK_SECRET"),
                claimTtlSeconds = env["CLAIM_TTL_SECONDS"]?.toLong() ?: 600,
                port = env["PORT"]?.toInt() ?: 8080,
            ).also { require(it.claimTtlSeconds > 0) { "CLAIM_TTL_SECONDS must be positive" } }
        }
    }
}
