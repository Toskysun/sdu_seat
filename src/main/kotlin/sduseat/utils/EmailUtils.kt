package sduseat.utils

import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import sduseat.bean.EmailConfig
import mu.KotlinLogging
import java.util.*

private val logger = KotlinLogging.logger {}

object EmailUtils {
    fun sendEmail(config: EmailConfig, subject: String, content: String) {
        if (!config.enable) {
            return
        }
        
        try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.host", config.smtpHost)
                put("mail.smtp.port", config.smtpPort)
                if (config.sslEnable) {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.ssl.protocols", "TLSv1.2")
                }
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(config.username, config.password)
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(config.username))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(config.recipientEmail))
                setSubject(subject)
                setText(content)
            }

            Transport.send(message)
            logger.info { "邮件发送成功：$subject" }
        } catch (e: Exception) {
            logger.error(e) { "邮件发送失败：${e.message}" }
        }
    }
} 