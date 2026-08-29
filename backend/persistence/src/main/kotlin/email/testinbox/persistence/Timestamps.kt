package email.testinbox.persistence

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal object Timestamps {
    fun toDb(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    fun fromDb(
        rs: ResultSet,
        column: String,
    ): Instant? = rs.getObject(column, OffsetDateTime::class.java)?.toInstant()
}
