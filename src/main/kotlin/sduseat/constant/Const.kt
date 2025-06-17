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
import mu.KotlinLogging
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.script.ScriptEngineManager
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

object Const {
    const val UA_NAME = "User-Agent"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.66 Safari/537.36"
    val LIB_URL: String
        get() {
            return if (config?.webVpn != true) {
                "https://libseat.sdu.edu.cn"
                //"http://seatwx.lib.sdu.edu.cn:85"
            } else {
                "https://webvpn.sdu.edu.cn/http/77726476706e69737468656265737421e3f24088693c6152301b8db9d6502720e38b79"
            }
        }

    val LIB_FIRST_URL: String
        get() = "$LIB_URL/home/web/f_second"

    const val DEVICE_URL: String = "https://pass.sdu.edu.cn/cas/device"

    val periodFormat = "[0-9]{2}:[0-9]{2}-[0-9]{2}:[0-9]{2}".toRegex()
    val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd") }
    val timeDateFormat by lazy { SimpleDateFormat("yyyy-MM-dd hh:mm:ss", Locale.getDefault()) }

    val logger = KotlinLogging.logger {}

    const val ONE_DAY = 86400000L

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

    val SCRIPT_ENGINE by lazy {
        try {
            // 尝试使用 Rhino 引擎
            val engine = ScriptEngineManager().getEngineByName("rhino")
            if (engine != null) {
                logger.info { "成功初始化 Rhino JavaScript 引擎" }
                return@lazy engine
            }
            
            // 尝试使用 Nashorn 引擎
            val nashornEngine = ScriptEngineManager().getEngineByName("nashorn")
            if (nashornEngine != null) {
                logger.info { "成功初始化 Nashorn JavaScript 引擎" }
                return@lazy nashornEngine
            }
            
            // 尝试使用 JavaScript 引擎
            val jsEngine = ScriptEngineManager().getEngineByName("javascript")
            if (jsEngine != null) {
                logger.info { "成功初始化通用 JavaScript 引擎" }
                return@lazy jsEngine
            }
            
            throw IllegalArgumentException("找不到可用的 JavaScript 引擎")
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize JavaScript engine" }
            throw RuntimeException("Failed to initialize JavaScript engine", e)
        }
    }
    
    // 备用的直接使用 Rhino API 的方法
    fun evaluateJavaScript(script: String, vararg args: Pair<String, Any>): Any? {
        try {
            val rhino = Context.enter()
            try {
                rhino.optimizationLevel = -1 // 禁用优化以提高兼容性
                val scope = rhino.initStandardObjects()
                
                // 添加参数
                args.forEach { (name, value) ->
                    scope.put(name, scope, Context.javaToJS(value, scope))
                }
                
                return rhino.evaluateString(scope, script, "script", 1, null)
            } finally {
                Context.exit()
            }
        } catch (e: Exception) {
            logger.error(e) { "JavaScript 执行失败" }
            throw RuntimeException("JavaScript 执行失败", e)
        }
    }
}