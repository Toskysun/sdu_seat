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

package sduseat.bean

import sduseat.AppException
import sduseat.config
import sduseat.configFilePath
import sduseat.constant.Const
import io.github.oshai.kotlinlogging.KotlinLogging as KLogger
import sduseat.utils.GSON
import sduseat.utils.fromJsonObject
import java.io.File

private val logger = KLogger.logger {}

/**
 * 配置说明：
 * - userid: 学号（必填）
 * - deviceId: 设备ID（可选，用于调试）
 * - area: 选座区域（必填）
 * - seats: 预约座位信息（必填）
 * - filterRule: 座位筛选规则（可选）
 * - only: 是否仅预约指定座位（默认false）
 * - time: 运行时间，格式为"HH:mm[:ss[.SSS]]"，例如"06:02"、"12:32:00"、"12:32:00.500"
 * - retry: 预约重试次数（默认10）
 * - retryInterval: 预约重试间隔（秒，默认2）
 * - delta: 预约日期偏移（默认0）
 * - bookOnce: 是否只预约一次（默认false）
 * - wechatSession: 微信OAuth会话配置（可选）
 * - emailNotification: 邮件通知配置（可选）
 *
 * 注意：
 * - 使用微信OAuth认证，无需密码
 * - 微信OAuth会话数据可以手动配置以避免重复认证
 */
data class Config(
    var userid: String? = null,
    var deviceId: String? = null,
    var area: String? = null,
    var seats: LinkedHashMap<String, ArrayList<String>>? = LinkedHashMap(),
    var filterRule: String? = "",
    var only: Boolean = false,
    var time: String? = "06:02",
    var retry: Int = 10,
    var retryInterval: Int = 2,
    var delta: Int = 0,
    var bookOnce: Boolean = false,
    var wechatSession: WeChatSessionConfig? = null,
    var emailNotification: EmailConfig? = null,
) {
    companion object {
        fun initConfig(args: Array<String>) {
            // 获取 JAR 文件所在目录
            val jarPath = Config::class.java.protectionDomain.codeSource.location.toURI().path
            val jarDir = File(jarPath).parent
            val jarDirConfig = File(jarDir, "config.json")

            // 确定配置文件路径
            val configPath = when {
                // 1. 如果命令行指定了配置文件，优先使用
                args.isNotEmpty() -> args[0]
                // 2. 如果 JAR 同目录下存在 config.json，使用它
                jarDirConfig.exists() -> jarDirConfig.absolutePath
                // 3. 最后使用默认配置文件路径
                else -> Const.defaultConfig
            }

            val configFile = File(configPath)
            if (!configFile.exists()) {
                throw AppException("$configPath 配置文件不存在")
            } else {
                logger.info { "使用配置文件：$configPath" }
                configFilePath = configFile.absolutePath // 保存配置文件的绝对路径
                config = GSON.fromJsonObject<Config>(configFile.readText())?.apply {
                    if (userid.isNullOrEmpty()) throw AppException("userid：用户名/学号不能为空")

                    // 检查设备ID配置
                    if (deviceId.isNullOrEmpty()) {
                        logger.info { "deviceId：设备ID可选，使用默认值" }
                        deviceId = Const.DEFAULT_DEVICE_ID
                    }

                    if (area.isNullOrEmpty()) throw AppException("area：选座区域不能为空")

                    if (time.isNullOrEmpty()) time = "12:32"
                    if (retryInterval <= 0) retryInterval = 30
                    if (!filterRule.isNullOrEmpty()) {
                        filterRule = if (filterRule!!.startsWith("@js:")) {
                            filterRule!!.substring(4)
                        } else {
                            try {
                                File(filterRule!!).readText()
                            } catch (e: Exception) {
                                logger.error(e) { "过滤规则文件读取失败" }
                                ""
                            }
                        }
                    }
                } ?: throw AppException("配置文件格式错误")
            }
        }

        /**
         * 保存配置到文件
         * @param filePath 配置文件路径，如果为空则使用当前配置文件路径
         */
        fun saveConfig(filePath: String? = null) {
            try {
                val targetPath = filePath ?: configFilePath
                if (targetPath.isEmpty()) {
                    logger.warn { "无法保存配置：配置文件路径为空" }
                    return
                }

                val configFile = File(targetPath)
                val configJson = GSON.toJson(config)
                configFile.writeText(configJson)
                logger.info { "配置已保存到: $targetPath" }
            } catch (e: Exception) {
                logger.error(e) { "保存配置文件失败" }
            }
        }
    }
}

/**
 * 微信OAuth会话配置
 * 用于保存已有的微信OAuth会话数据，避免重复认证
 */
data class WeChatSessionConfig(
    var userObj: String? = null,        // URL编码的用户对象数据
    var school: String? = null,         // URL编码的学校配置数据
    var dinepo: String? = null,         // 微信OpenID
    var user: String? = null,           // URL编码的用户会话数据
    var connectSid: String? = null,     // 会话ID
    var autoInject: Boolean = true,     // 是否自动注入会话数据
    var redirectUri: String? = null     // 自定义微信OAuth回调地址
)

/**
 * 邮件配置
 */
data class EmailConfig(
    var enable: Boolean = false,           // 是否启用邮件通知
    var smtpHost: String = "",            // SMTP服务器地址
    var smtpPort: Int = 465,              // SMTP端口
    var username: String = "",            // 发件人邮箱
    var password: String = "",            // 发件人密码（授权码）
    var recipientEmail: String = "",      // 收件人邮箱
    var sslEnable: Boolean = true         // 是否启用SSL
)