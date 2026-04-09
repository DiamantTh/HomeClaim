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
        maximumPoolSize: Int = 10
    ): DataSource {
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username?.let { this.username = it }
            password?.let { this.password = it }
            driverClassName?.let { this.driverClassName = it }
            this.maximumPoolSize = maximumPoolSize
        }
        return HikariDataSource(config)
    }
}
