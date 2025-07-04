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

package sduseat.constant

import sduseat.config
import io.github.oshai.kotlinlogging.KotlinLogging as KLogger
import java.io.File
import java.text.SimpleDateFormat
import javax.script.ScriptEngineManager

object Const {
    const val UA_NAME = "User-Agent"

    // 微信浏览器User-Agent（模拟真实微信环境）
    const val WECHAT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.93 Mobile Safari/537.36 MicroMessenger/8.0.1.1841(0x28000151) Process/tools WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64"

    // 默认桌面浏览器User-Agent（作为备用）
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.66 Safari/537.36"

    // 使用微信浏览器User-Agent
    val USER_AGENT: String = WECHAT_USER_AGENT

    // 图书馆API基础URL
    const val LIB_URL = "http://seatwx.lib.sdu.edu.cn:85"

    // 图书馆首页URL
    const val LIB_FIRST_URL = "http://seatwx.lib.sdu.edu.cn/"

    // 微信登录相关常量
    const val WECHAT_LOGIN_URL = "http://seatwx.lib.sdu.edu.cn/"



    val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd") }

    val logger = KLogger.logger {}

    const val ONE_DAY = 86400000L
    const val THREAD_POOL_SHUTDOWN_TIMEOUT = 60L
    const val AUTH_EXPIRE_TIME = 30 * 60 * 1000L // 30 minutes
    const val PRE_BOOK_REFRESH_TIME = 10000L // 10 seconds
    const val DEFAULT_DEVICE_ID = "wechat_device"

    private val defaultPath = "." + File.separator + "config" + File.separator

    val defaultConfig = if (File(defaultPath + "config.json").exists()) {
        defaultPath + "config.json"
    } else {
        defaultPath + "config.example.json"
    }

    val statusMap = mapOf(
        Pair(1, "等待审核"),
        Pair(2, "预约成功"),
        Pair(3, "使用中"),
        Pair(4, "已使用"),
        Pair(5, "审核未通过"),
        Pair(6, "用户取消"),
        Pair(7, "已超时"),
        Pair(8, "已关闭"),
        Pair(9, "预约开始提醒"),
        Pair(10, "迟到提醒"),
        Pair(11, "预约结束提醒")
    )

    // JavaScript引擎（仅用于座位筛选功能）
    val SCRIPT_ENGINE by lazy {
        try {
            // 尝试使用 Nashorn 引擎
            val nashornEngine = ScriptEngineManager().getEngineByName("nashorn")
            if (nashornEngine != null) {
                logger.info { "成功初始化 Nashorn JavaScript 引擎" }
                return@lazy nashornEngine
            }

            // 尝试使用通用 JavaScript 引擎
            val jsEngine = ScriptEngineManager().getEngineByName("javascript")
            if (jsEngine != null) {
                logger.info { "成功初始化通用 JavaScript 引擎" }
                return@lazy jsEngine
            }

            throw IllegalArgumentException("找不到可用的 JavaScript 引擎")
        } catch (e: Exception) {
            logger.error(e) { "JavaScript 引擎初始化失败" }
            throw RuntimeException("JavaScript 引擎初始化失败", e)
        }
    }
}