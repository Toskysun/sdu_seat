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
import sduseat.constant.Const
import mu.KotlinLogging
import sduseat.utils.GSON
import sduseat.utils.fromJsonObject
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * 配置说明：
 * - userid: 学号
 * - passwd: 密码
 * - deviceId: 设备ID，必填，否则无法登录
 * - area: 选座区域
 * - seats: 预约座位信息
 * - filterRule: 座位筛选规则
 * - only: 是否仅预约指定座位
 * - time: 运行时间，格式为"HH:mm[:ss]"，例如"06:02"、"12:32:00"
 * - period: 预约时间段，格式为"HH:mm-HH:mm"，例如"08:00-22:30"
 * - retry: 预约重试次数
 * - retryInterval: 预约重试间隔（秒）
 * - delta: 预约日期偏移
 * - bookOnce: 是否只预约一次
 * - webVpn: 是否使用WebVPN
 * - maxLoginAttempts: 最大登录尝试次数，默认50次，登录成功后会立即停止尝试
 * - earlyLoginMinutes: 系统会在预约时间前多少分钟自动开始登录尝试
 * - enableEarlyLogin: 是否启用提前登录功能，默认启用
 * 注意：如果启用提前登录，系统会在预约时间前earlyLoginMinutes分钟自动开始登录尝试，最多尝试maxLoginAttempts次，无间隔
 */
data class Config(
    var userid: String? = null,
    var passwd: String? = null,
    var deviceId: String? = null,
    var area: String? = null,
    var seats: LinkedHashMap<String, ArrayList<String>>? = LinkedHashMap(),
    var filterRule: String? = "",
    var only: Boolean = false,
    var time: String? = "06:02",
    var period: String? = "08:00-22:30",
    var retry: Int = 10,
    var retryInterval: Int = 2,
    var delta: Int = 0,
    var bookOnce: Boolean = false,
    var webVpn: Boolean = false,
    var maxLoginAttempts: Int = 50,
    var earlyLoginMinutes: Int = 5,
    var enableEarlyLogin: Boolean = true,
    var emailNotification: EmailConfig? = null,
) {
    constructor(webVpn: Boolean) : this("", "", "", "", webVpn = webVpn)

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
                config = GSON.fromJsonObject<Config>(configFile.readText())?.apply {
                    if (userid.isNullOrEmpty()) throw AppException("userid：用户名/学号不能为空")
                    if (passwd.isNullOrEmpty()) throw AppException("passwd：密码不能为空")
                    if (deviceId.isNullOrEmpty()) throw AppException("deviceId：设备ID不能为空，否则无法登录，请查看设备ID获取说明")
                    if (area.isNullOrEmpty()) throw AppException("area：选座区域不能为空")

                    if (webVpn) {
                        logger.warn { "webVpn：由于学校暂未开放图书馆新域名的访问权限，webVpn暂时无法使用" }
                        webVpn = false
                    }

                    if (time.isNullOrEmpty()) time = "12:32"
                    if (period.isNullOrEmpty()) period = "08:00-22:30"
                    if (!period!!.matches(Const.periodFormat)) {
                        period = "08:00-22:30"
                        logger.warn { "预约时间段格式错误，默认设置为：${config!!.period}" }
                    }
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
    }
}

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