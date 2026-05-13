package systems.diath.homeclaim.core.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

object SqlDsl {
    fun hikari(
        jdbcUrl: String,
        username: String? = null,
        password: String? = null,
        driverClassName: String? = null,
        maximumPoolSize: Int = 10,
        minimumIdle: Int = 2,
        connectionTimeoutMs: Long = 10_000,
        validationTimeoutMs: Long = 5_000,
        idleTimeoutMs: Long = 300_000,
        maxLifetimeMs: Long = 1_800_000,
        leakDetectionThresholdMs: Long = 60_000,
        connectionTestQuery: String = "SELECT 1"
    ): DataSource {
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username?.let { this.username = it }
            password?.let { this.password = it }
            driverClassName?.let { this.driverClassName = it }
            this.maximumPoolSize = maximumPoolSize
            this.minimumIdle = minimumIdle.coerceAtMost(maximumPoolSize)
            this.connectionTimeout = connectionTimeoutMs
            this.validationTimeout = validationTimeoutMs
            this.idleTimeout = idleTimeoutMs
            this.maxLifetime = maxLifetimeMs
            this.leakDetectionThreshold = leakDetectionThresholdMs
            this.connectionTestQuery = connectionTestQuery
        }
        return HikariDataSource(config)
    }
}
