package email.testinbox.api.account

import email.testinbox.application.identity.EmailLoginCode
import email.testinbox.application.port.EmailLoginDelivery
import email.testinbox.domain.identity.ContactEmail
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSenderImpl

/** Dedicated transactional sending identity supplied by Ops; never the inbound mail edge. */
class SmtpLoginDelivery(
    private val properties: LoginSmtpProperties,
    private val sender: JavaMailSenderImpl = configuredSender(properties),
) : EmailLoginDelivery {
    override fun send(
        email: ContactEmail,
        code: EmailLoginCode,
    ) {
        val message = SimpleMailMessage()
        message.setFrom(checkNotNull(ContactEmail.parse(properties.from)).value)
        message.setTo(email.value)
        message.subject = "Your TestInbox sign-in code"
        message.text = "Your TestInbox sign-in code is ${code.value}.\n\n" +
            "It expires in 10 minutes and only works in the browser that requested it.\n" +
            "Do not share this code. If you did not request it, ignore this message."
        sender.send(message)
    }

    companion object {
        fun validate(properties: LoginSmtpProperties) {
            require(properties.host.matches(Regex("[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+"))) { "an explicit SMTP hostname is required" }
            require(properties.port == 587) { "login SMTP requires port 587 with mandatory STARTTLS" }
            require(properties.username.isNotBlank() && properties.password.isNotBlank()) { "SMTP credentials are required" }
            require(ContactEmail.parse(properties.from) != null) { "an approved sending identity is required" }
        }

        internal fun configuredSender(properties: LoginSmtpProperties): JavaMailSenderImpl {
            validate(properties)
            return JavaMailSenderImpl().apply {
                host = properties.host
                port = properties.port
                username = properties.username
                password = properties.password
                defaultEncoding = "UTF-8"
                javaMailProperties.setProperty("mail.smtp.auth", "true")
                javaMailProperties.setProperty("mail.smtp.starttls.enable", "true")
                javaMailProperties.setProperty("mail.smtp.starttls.required", "true")
                javaMailProperties.setProperty("mail.smtp.ssl.checkserveridentity", "true")
                javaMailProperties.setProperty("mail.smtp.ssl.protocols", "TLSv1.3 TLSv1.2")
                javaMailProperties.setProperty("mail.smtp.connectiontimeout", "5000")
                javaMailProperties.setProperty("mail.smtp.timeout", "5000")
                javaMailProperties.setProperty("mail.smtp.writetimeout", "5000")
                javaMailProperties.setProperty("mail.debug", "false")
            }
        }
    }
}
