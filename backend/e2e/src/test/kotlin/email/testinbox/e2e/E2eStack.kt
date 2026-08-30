package email.testinbox.e2e

import email.testinbox.api.ApiApplication
import email.testinbox.ingestion.IngestionApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.sql.Connection
import java.sql.DriverManager

/**
 * The full walking-skeleton stack for black-box acceptance tests: real
 * Postgres + MinIO containers, the API application on a random HTTP port,
 * and the independently deployable ingestion gateway on a random SMTP port
 * — two separate Spring contexts, exactly like two deployables (ADR-001).
 */
object E2eStack {
    const val API_KEY = "tk_e2e_acceptance_key"
    const val MAIL_DOMAIN = "testinbox.local"

    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
    val minio: MinIOContainer =
        MinIOContainer("minio/minio:latest")
            .withUserName("testinbox")
            .withPassword("testinbox123")
            .also { it.start() }

    val smtpPort: Int = ServerSocket(0).use { it.localPort }

    private val commonProperties =
        mapOf(
            "spring.datasource.url" to postgres.jdbcUrl,
            "spring.datasource.username" to postgres.username,
            "spring.datasource.password" to postgres.password,
            "testinbox.mail-domain" to MAIL_DOMAIN,
            "testinbox.storage.endpoint" to minio.s3URL,
            "testinbox.storage.access-key" to "testinbox",
            "testinbox.storage.secret-key" to "testinbox123",
        )

    private fun args(properties: Map<String, String>): Array<String> = properties.map { (key, value) -> "--$key=$value" }.toTypedArray()

    val apiContext: ConfigurableApplicationContext =
        SpringApplicationBuilder(ApiApplication::class.java)
            .run(
                *args(
                    commonProperties +
                        mapOf(
                            "server.port" to "0",
                            "testinbox.bootstrap.api-key" to API_KEY,
                            "testinbox.sweep-interval" to "1s",
                            "testinbox.expiry-grace" to "1s",
                            "testinbox.wait-window-cap" to "10s",
                        ),
                ),
            )

    val apiPort: Int = apiContext.environment.getProperty("local.server.port")!!.toInt()
    val apiBaseUrl: String = "http://localhost:$apiPort"

    val ingestionContext: ConfigurableApplicationContext =
        SpringApplicationBuilder(IngestionApplication::class.java)
            .run(
                *args(
                    commonProperties +
                        mapOf(
                            "server.port" to "0",
                            "testinbox.smtp.port" to smtpPort.toString(),
                        ),
                ),
            )

    fun dbConnection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    /** Kills the API node's session-scoped LISTEN backend from the server side (ADR-020 test). */
    fun killListenConnection() {
        dbConnection().use { connection ->
            connection.createStatement().use {
                it.execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                        "WHERE application_name = 'testinbox-listen'",
                )
            }
        }
    }

    /** Sends the exact raw bytes over a real SMTP session — byte-identical resends stay identical. */
    fun sendRawSmtp(
        from: String,
        to: String,
        raw: ByteArray,
    ) {
        Socket("localhost", smtpPort).use { socket ->
            socket.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val output = socket.getOutputStream()

            fun reply(): Int {
                var line: String
                do {
                    line = reader.readLine() ?: error("SMTP connection closed")
                } while (line.length >= 4 && line[3] == '-')
                return line.take(3).toInt()
            }

            fun send(
                command: String,
                expected: Int,
            ) {
                output.write("$command\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
                val code = reply()
                check(code == expected) { "$command -> $code (expected $expected)" }
            }
            check(reply() == 220)
            send("EHLO e2e", 250)
            send("MAIL FROM:<$from>", 250)
            send("RCPT TO:<$to>", 250)
            output.write("DATA\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
            check(reply() == 354)
            val text = String(raw, Charsets.ISO_8859_1)
            val stuffed =
                text.lineSequence().joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }
            output.write(stuffed.toByteArray(Charsets.ISO_8859_1))
            output.write("\r\n.\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
            check(reply() == 250)
            send("QUIT", 221)
        }
    }

    fun verificationEmail(
        to: String,
        subject: String = "Verify your email",
        token: String = "abc123",
    ): ByteArray =
        (
            "From: SUT <no-reply@example.com>\r\n" +
                "To: $to\r\n" +
                "Subject: $subject\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "\r\n" +
                "Welcome! Verify at https://app.example.com/verify?token=$token\r\n"
        ).toByteArray(Charsets.ISO_8859_1)
}
