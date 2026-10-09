package email.testinbox.persistence

import email.testinbox.application.port.ObservedFootprint
import email.testinbox.application.storage.FootprintGate
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * The footprint inputs for the check BEFORE recipient resolution, in one
 * statement and with T1's own definitions ([FootprintSql]). No lock: T1
 * repeats the check in its snapshot under the admission lock.
 */
class JdbcFootprintGate(
    private val jdbc: JdbcClient,
) : FootprintGate {
    override fun observe(): ObservedFootprint? =
        jdbc
            .sql("WITH ${FootprintSql.CTES} SELECT ${FootprintSql.COLUMNS}")
            .query { rs, _ -> FootprintSql.read(rs) }
            .single()
}
