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
import java.io.File
import java.io.RandomAccessFile

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
    
    /**
     * 获取最近的日志内容
     * @param lines 要获取的日志行数
     * @param includeErrors 是否只包含错误日志
     * @return 日志内容字符串
     */
    fun getRecentLogs(lines: Int = 100, includeErrors: Boolean = false): String {
        val logFile = File("logs/sdu-seat.log")
        if (!logFile.exists()) {
            logger.warn { "日志文件不存在: ${logFile.absolutePath}" }
            return "日志文件不存在"
        }
        
        try {
            // 使用RandomAccessFile从文件末尾读取
            val randomAccessFile = RandomAccessFile(logFile, "r")
            val fileLength = randomAccessFile.length()
            
            // 存储日志行
            val logLines = mutableListOf<String>()
            var linesRead = 0
            
            // 从文件末尾开始向前读取
            var pointer = fileLength - 1
            randomAccessFile.seek(pointer)
            
            // 跳过文件末尾的换行符
            while (pointer >= 0) {
                val c = randomAccessFile.read().toChar()
                if (c != '\n' && c != '\r') {
                    pointer--
                    randomAccessFile.seek(pointer)
                } else {
                    break
                }
            }
            
            // 向前读取指定行数
            while (pointer >= 0 && linesRead < lines) {
                randomAccessFile.seek(pointer)
                val c = randomAccessFile.read().toChar()
                
                // 找到一行的开始
                if (c == '\n' || c == '\r' || pointer == 0) {
                    if (pointer != fileLength - 1L) { // 不是文件的最后一个字符
                        randomAccessFile.seek(if (pointer == 0L) 0L else pointer + 1)
                        val line = randomAccessFile.readLine()
                        
                        // 如果只需要错误日志且当前行包含错误信息，或者不限制日志类型
                        if (!includeErrors || (line != null && (line.contains("ERROR") || line.contains("WARN")))) {
                            logLines.add(line ?: "")
                            linesRead++
                        }
                    }
                }
                
                pointer--
            }
            
            randomAccessFile.close()
            
            // 反转列表以按时间顺序排列
            return logLines.reversed().joinToString("\n")
        } catch (e: Exception) {
            logger.error(e) { "读取日志文件失败: ${e.message}" }
            return "读取日志文件失败: ${e.message}"
        }
    }
} 