/*
 * Copyright (C) 2025-2026 Toskysun
 * 
 * This file is part of Sdu-Seat
 * Sdu-Seat is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Sdu-Seat is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Sdu-Seat.  If not, see <https://www.gnu.org/licenses/>.
 */

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
            logger.info { "邮件通知功能未启用，跳过发送邮件：$subject" }
            return
        }
        
        // 验证邮件配置是否完整
        if (config.smtpHost.isEmpty() || config.username.isEmpty() || config.password.isEmpty() || config.recipientEmail.isEmpty()) {
            logger.error { "邮件发送失败：邮件配置不完整，请检查smtpHost、username、password和recipientEmail设置" }
            return
        }
        
        logger.info { "尝试发送邮件：$subject 到 ${config.recipientEmail}" }
        
        try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.host", config.smtpHost)
                put("mail.smtp.port", config.smtpPort)
                if (config.sslEnable) {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.ssl.protocols", "TLSv1.2")
                }
                
                // 设置超时属性
                put("mail.smtp.connectiontimeout", "10000")  // 连接超时10秒
                put("mail.smtp.timeout", "10000")            // 读取超时10秒
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(config.username, config.password)
                }
            })

            // 设置debug模式以便诊断问题
            // session.debug = true

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(config.username))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(config.recipientEmail))
                setSubject(subject)
                setText(content)
            }

            Transport.send(message)
            logger.info { "邮件发送成功：$subject 到 ${config.recipientEmail}" }
        } catch (e: AuthenticationFailedException) {
            logger.error { "邮件发送失败：认证失败，请检查用户名和密码设置" }
            logger.debug(e) { "认证错误详情" }
        } catch (e: MessagingException) {
            logger.error { "邮件发送失败：${e.message}" }
            logger.debug(e) { "邮件错误详情" }
        } catch (e: Exception) {
            logger.error(e) { "邮件发送失败：${e.message}" }
        }
    }
} 