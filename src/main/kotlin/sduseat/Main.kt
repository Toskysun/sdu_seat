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

@file:JvmName("Main")

package sduseat

import sduseat.api.*
import sduseat.bean.AreaBean
import sduseat.bean.Config
import sduseat.bean.WeChatSessionConfig
import sduseat.bean.PeriodBean
import sduseat.bean.SeatBean
import sduseat.constant.Const
import sduseat.constant.Const.ONE_DAY
import sduseat.constant.Const.dateFormat
import sduseat.utils.GSON
import sduseat.utils.parseString
import sduseat.http.getProxyClient
import sduseat.http.newCallResponseText
import io.github.oshai.kotlinlogging.KotlinLogging as KLogger
import sduseat.http.cookieCathe
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private val logger = KLogger.logger {}

var config: Config? = null
var configFilePath: String = "" // 存储配置文件路径
var date: String = ""
var area: AreaBean? = null
var auth: IAuth? = null
val allSeats = LinkedHashMap<Int, List<SeatBean>>()
val querySeats = LinkedHashMap<Int, List<SeatBean>>()
var periods = LinkedHashMap<Int, PeriodBean>()
var success = LinkedHashMap<Int, Boolean>()
var needReLogin = false // 是否需要重新登录
var directSeatsCache: Map<String, Map<String, SeatBean>>? = null // 缓存直接获取的座位信息
val threadPool: ExecutorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2).apply {
    Runtime.getRuntime().addShutdownHook(Thread {
        shutdown()
        try {
            if (!awaitTermination(Const.THREAD_POOL_SHUTDOWN_TIMEOUT, TimeUnit.SECONDS)) {
                shutdownNow()
            }
        } catch (_: InterruptedException) {
            shutdownNow()
        }
    })
}

/**
 * 从API实时获取区域信息
 */
fun getAreaFromAPI(libName: String, subLibName: String): AreaBean? {
    try {
        val today = java.time.LocalDate.now().toString()
        val apiUrl = "http://seatwx.lib.sdu.edu.cn:85/api.php/v3areas?date=$today"

        logger.info { "从API获取区域信息: $libName-$subLibName" }
        logger.debug { "API URL: $apiUrl" }

        val res = sduseat.http.getProxyClient().newCallResponseText(3) {
            url(apiUrl)
            header("Referer", "http://seatwx.lib.sdu.edu.cn/")
        }

        val json = sduseat.utils.GSON.parseString(res).asJsonObject
        val status = json.get("status").asInt

        if (status != 1) {
            logger.warn { "API返回错误状态: $status" }
            return getKnownAreaByName(libName, subLibName)
        }

        val dataObj = json.getAsJsonObject("data")
        if (!dataObj.has("list")) {
            logger.warn { "API响应缺少list字段" }
            return getKnownAreaByName(libName, subLibName)
        }

        val listObj = dataObj.getAsJsonObject("list")
        if (!listObj.has("seatinfo")) {
            logger.warn { "API响应缺少seatinfo字段" }
            return getKnownAreaByName(libName, subLibName)
        }

        val seatinfoArray = listObj.getAsJsonArray("seatinfo")

        // 构建区域层级映射
        val areaMap = mutableMapOf<Int, AreaInfo>()
        seatinfoArray.forEach { item ->
            val areaItem = item.asJsonObject
            val id = areaItem.get("id").asInt
            val name = areaItem.get("name").asString
            val parentId = if (areaItem.has("parentId")) {
                areaItem.get("parentId").asInt
            } else {
                0
            }
            val totalCount = if (areaItem.has("TotalCount")) {
                areaItem.get("TotalCount").asInt
            } else {
                0
            }
            val unavailableSpace = if (areaItem.has("UnavailableSpace")) {
                areaItem.get("UnavailableSpace").asInt
            } else {
                0
            }

            areaMap[id] = AreaInfo(id, name, parentId, totalCount, unavailableSpace)
        }

        logger.debug { "从API获取到 ${areaMap.size} 个区域" }

        // 查找匹配的区域
        val result = findMatchingArea(areaMap, libName, subLibName)
        if (result != null) {
            return AreaBean(result.id, result.name, result.totalCount - result.unavailableSpace, result.totalCount)
        } else {
            logger.warn { "API中未找到匹配的区域: $libName-$subLibName，使用备用方案" }
            return getKnownAreaByName(libName, subLibName)
        }

    } catch (e: Exception) {
        logger.warn(e) { "从API获取区域信息失败，使用备用方案" }
        return getKnownAreaByName(libName, subLibName)
    }
}

/**
 * 区域信息数据类
 */
data class AreaInfo(
    val id: Int,
    val name: String,
    val parentId: Int,
    val totalCount: Int,
    val unavailableSpace: Int
)

/**
 * 在区域映射中查找匹配的区域
 */
fun findMatchingArea(areaMap: Map<Int, AreaInfo>, libName: String, subLibName: String): AreaInfo? {
    // 首先查找顶级图书馆
    val topLevelLib = areaMap.values.find { it.parentId == 0 && it.name == libName }
    if (topLevelLib == null) {
        logger.warn { "未找到顶级图书馆: $libName" }
        return null
    }

    logger.debug { "找到顶级图书馆: ${topLevelLib.name} (ID: ${topLevelLib.id})" }

    // 如果子区域名称就是图书馆名称，直接返回顶级图书馆
    if (subLibName == libName) {
        return topLevelLib
    }

    // 查找子区域
    val subArea = areaMap.values.find { it.parentId == topLevelLib.id && it.name == subLibName }
    if (subArea != null) {
        logger.debug { "找到子区域: ${subArea.name} (ID: ${subArea.id})" }

        // 检查是否有更具体的子区域（如阅览室）
        val specificAreas = areaMap.values.filter { it.parentId == subArea.id }
        if (specificAreas.isNotEmpty()) {
            logger.debug { "找到 ${specificAreas.size} 个具体区域: ${specificAreas.map { it.name }.joinToString(", ")}" }

            // 根据seats配置选择最合适的具体区域
            val configuredSeatAreas = config?.seats?.keys ?: emptySet()
            for (seatAreaName in configuredSeatAreas) {
                val matchingSpecificArea = specificAreas.find { it.name == seatAreaName }
                if (matchingSpecificArea != null) {
                    logger.info { "根据seats配置选择具体区域: ${matchingSpecificArea.name} (ID: ${matchingSpecificArea.id})" }
                    return matchingSpecificArea
                }
            }

            // 如果没有匹配的具体区域，返回第一个具体区域
            logger.info { "未找到匹配的具体区域，使用第一个: ${specificAreas.first().name}" }
            return specificAreas.first()
        }

        return subArea
    }

    // 如果没有找到子区域，可能子区域名称就是具体的阅览室名称
    val directMatch = areaMap.values.find { it.name.contains(subLibName) }
    if (directMatch != null) {
        logger.debug { "找到直接匹配的区域: ${directMatch.name} (ID: ${directMatch.id})" }
        return directMatch
    }

    logger.warn { "未找到匹配的子区域: $subLibName" }
    return null
}

/**
 * 备用的已知区域映射（当API失败时使用）
 */
fun getKnownAreaByName(libName: String, subLibName: String): AreaBean? {
    logger.info { "使用备用区域映射: $libName-$subLibName" }
    return when (libName) {
        "蒋震馆" -> AreaBean(1, "蒋震馆-$subLibName", 0, 0)
        "千佛山馆" -> AreaBean(12, "千佛山馆-$subLibName", 0, 0)
        "趵突泉馆" -> AreaBean(202, "趵突泉阅览室112", 0, 0) // 默认使用112阅览室
        "主楼" -> AreaBean(208, "主楼-$subLibName", 0, 0)
        "图东区" -> AreaBean(209, "图东区-$subLibName", 0, 0)
        "电子阅览室" -> AreaBean(210, "电子阅览室-$subLibName", 0, 0)
        else -> {
            logger.warn { "未知的图书馆: $libName，使用趵突泉馆的ID" }
            AreaBean(202, "$libName-$subLibName", 0, 0)
        }
    }
}

/**
 * 获取用于时间段查询的正确图书馆ID
 * 某些子区域需要使用主图书馆的ID来查询时间段信息
 */
fun getLibraryIdForArea(libName: String, areaId: Int): Int {
    return when (libName) {
        "趵突泉馆" -> 202  // 趵突泉馆的所有子区域都使用主馆ID 202
        "蒋震馆" -> 1      // 蒋震馆使用主馆ID 1
        "千佛山馆" -> 12   // 千佛山馆使用主馆ID 12
        "主楼" -> 208      // 主楼使用主馆ID 208
        "图东区" -> 209    // 图东区使用主馆ID 209
        "电子阅览室" -> 210 // 电子阅览室使用主馆ID 210
        else -> areaId     // 其他情况使用原区域ID
    }
}

val spiderRunnable = Runnable {
    try {
        getAllSeats()
    } catch (e: Exception) {
        logger.error(e) { "获取座位信息失败" }
        needReLogin = true
    }
}

/**
 * 创建认证实例
 */
fun createAuth(): IAuth {
    val auth = Auth(config!!.userid!!, "wechat_auth", config!!.deviceId!!, 50)

    config!!.wechatSession?.let { sessionConfig ->
        if (sessionConfig.autoInject &&
            !sessionConfig.userObj.isNullOrEmpty() &&
            !sessionConfig.user.isNullOrEmpty()) {
            try {
                auth.injectSessionCookies(
                    userObjData = sessionConfig.userObj!!,
                    schoolData = sessionConfig.school ?: "",
                    dinepoData = sessionConfig.dinepo ?: "",
                    userData = sessionConfig.user!!,
                    connectSidData = sessionConfig.connectSid ?: ""
                )

                // 检查注入的会话状态
                if (auth.isExpire()) {
                    logger.warn { "注入的微信会话已过期，将在登录时自动处理" }
                } else {
                    val remainingMinutes = auth.getRemainingMinutes()
                    if (remainingMinutes > 0) {
                        logger.info { "注入的微信会话有效，剩余时间: ${remainingMinutes} 分钟" }
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "自动注入微信会话数据失败，将使用正常认证流程" }
            }
        }
    }

    return auth
    }

/**
 * 根据配置的时间格式创建对应的显示格式
 */
fun createDisplayFormat(): SimpleDateFormat {
    return when (config!!.time!!.length) {
        12 -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
        8 -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        else -> SimpleDateFormat("yyyy-MM-dd HH:mm")
    }
}

val authRunnable = Runnable {
    auth = createAuth()
    auth!!.login()
    needReLogin = false
}

fun main(args: Array<String>) {
    printInfo()

    // 处理帮助命令
    if (args.isNotEmpty() && (args[0] == "--help" || args[0] == "-h")) {
        printHelp()
        return
    }

    Config.initConfig(args)

    // 检查是否需要输入Cookie
    if (needCookieInput()) {
        if (!promptForCookieInput()) {
            return // 用户选择退出或输入失败
        }
    }

    date = dateFormat.format(System.currentTimeMillis() + ONE_DAY * config!!.delta)
    if (config!!.bookOnce) {
        startBook()
    } else {
        try {
            loginAndGetSeats()
        } catch (e: Exception) {
            // 登录失败，显示登录指南并退出
            showWeChatLoginGuide()
            return
        }
    }
    val sdf = SimpleDateFormat("yyyy-MM-dd " + config!!.time)
    var startTime = when (config!!.time!!.length) {
        12 -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").parse(sdf.format(Date()))
        8 -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(sdf.format(Date()))
        else -> SimpleDateFormat("yyyy-MM-dd HH:mm").parse(sdf.format(Date()))
    }
    // 如果已过当天设置时间，修改首次运行时间为明天
    if (System.currentTimeMillis() > startTime.time) {
        startTime = Date(startTime.time + ONE_DAY)
    }
    // 根据配置的时间格式创建对应的显示格式
    val displayFormat = createDisplayFormat()
    val formattedTime = displayFormat.format(startTime)

    // 增强预约时间显示
    logger.info { "=" + "=".repeat(59) }
    logger.info { "[预约时间] $formattedTime" }
    logger.info { "[当前时间] ${displayFormat.format(Date())}" }
    logger.info { "[等待时间] ${(startTime.time - System.currentTimeMillis()) / 1000}秒" }
    logger.info { "=" + "=".repeat(59) }
    logger.info { "请等待到 $formattedTime 开始预约..." }
    
    val time = Timer()



    // 预约前最后刷新任务（预约前10秒）
    val preBookRefreshTask = object : TimerTask() {
        override fun run() {
            logger.info { "[预约前刷新] 最后刷新座位信息..." }
            try {
                getAllSeats()
                logger.info { "[刷新完成] 座位信息刷新完成，准备预约" }
            } catch (e: Exception) {
                logger.warn(e) { "预约前座位信息刷新失败，将使用现有数据" }
            }
        }
    }

    val bookTask = object : TimerTask() {
        override fun run() {
            startBook()
        }
    }



    // 预约前刷新座位信息
    val preRefreshTime = Date(startTime.time - Const.PRE_BOOK_REFRESH_TIME)
    if (preRefreshTime.time > System.currentTimeMillis()) {
        time.schedule(preBookRefreshTask, preRefreshTime)
    }

    // 正常预约任务
    time.scheduleAtFixedRate(bookTask, startTime, ONE_DAY)
}

/**
 * 检查是否需要输入Cookie
 */
fun needCookieInput(): Boolean {
    val sessionConfig = config!!.wechatSession

    // 如果没有微信会话配置，需要输入
    if (sessionConfig == null) {
        return true
    }

    // 如果缺少必要的固定字段，需要完整配置
    if (sessionConfig.userObj.isNullOrEmpty() ||
        sessionConfig.school.isNullOrEmpty() ||
        sessionConfig.dinepo.isNullOrEmpty()) {
        return true
    }

    // 如果缺少会过期的字段，需要输入
    if (sessionConfig.user.isNullOrEmpty() || sessionConfig.connectSid.isNullOrEmpty()) {
        return true
    }

    // 检查会话是否过期 - 简化版本，避免重复日志
    try {
        // 解析user字段中的过期时间
        sessionConfig.user?.let { userValue ->
            val decodedUser = java.net.URLDecoder.decode(userValue, "UTF-8")
            val userJson = GSON.fromJson(decodedUser, com.google.gson.JsonObject::class.java)
            val expireTimeStr = userJson.get("expire")?.asString

            if (expireTimeStr != null) {
                val expireTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(expireTimeStr)
                val currentTime = Date()
                val timeDiff = expireTime.time - currentTime.time
                val remainingMinutes = timeDiff / (1000 * 60)

                if (remainingMinutes <= 0) {
                    logger.info { "检测到微信会话已过期，需要更新Cookie" }
                    return true
                }

                if (remainingMinutes <= 5) {
                    logger.info { "微信会话即将过期（剩余${remainingMinutes}分钟），建议更新Cookie" }
                    return true
                }
            }
        }
    } catch (e: Exception) {
        logger.warn { "检查会话状态失败，需要重新输入Cookie" }
        return true
    }

    return false
}

/**
 * 提示用户输入Cookie
 */
fun promptForCookieInput(): Boolean {
    val sessionConfig = config!!.wechatSession

    // 检查是否需要完整配置还是只需要更新过期字段
    val needFullConfig = sessionConfig == null ||
                        sessionConfig.userObj.isNullOrEmpty() ||
                        sessionConfig.school.isNullOrEmpty() ||
                        sessionConfig.dinepo.isNullOrEmpty()

    if (needFullConfig) {
        println("""
            |
            |==================== 首次配置微信Cookie ====================
            |
            |检测到缺少微信会话配置，需要完整的Cookie信息。
            |
            |请按照以下步骤获取Cookie：
            |1. 在微信中打开：http://seatwx.lib.sdu.edu.cn/
            |2. 完成登录后，按F12打开开发者工具
            |3. 在Network标签页中刷新页面
            |4. 复制任意请求的完整Cookie头信息
            |
            |========================================================
            |
        """.trimMargin())

        println("请输入完整的Cookie字符串（支持多行输入，输入空行结束）:")
        println("或者输入 'exit' 退出")
        print("> ")

        val inputLines = mutableListOf<String>()
        var line = readLine()?.trim()

        // 处理特殊命令
        if (line == null || line == "exit") {
            println("已取消配置。")
            return false
        }

        // 收集多行输入
        while (line != null && line.isNotEmpty()) {
            inputLines.add(line)
            print("> ")
            line = readLine()?.trim()
        }

        if (inputLines.isEmpty()) {
            println("未输入任何内容。")
            return false
        }

        val input = inputLines.joinToString("\n")
        return parseCookieAndUpdateConfig(input)
    } else {
        println("""
            |
            |==================== 更新过期Cookie字段 ====================
            |
            |检测到微信会话已过期或即将过期，只需更新以下字段：
            |- user（用户会话数据）
            |- connect.sid（会话ID）
            |
            |请按照以下步骤获取最新Cookie：
            |1. 在微信中打开：http://seatwx.lib.sdu.edu.cn/
            |2. 完成登录后，按F12打开开发者工具
            |3. 在Network标签页中刷新页面
            |4. 复制任意请求的Cookie头信息
            |
            |您可以输入：
            |- 完整Cookie字符串（程序会自动提取需要的字段）
            |- 或者只输入需要的字段，支持多行格式：
            |  connect.sid: 值
            |  user: 值
            |
            |========================================================
            |
        """.trimMargin())

        println("请输入Cookie字符串（支持多行输入，输入空行结束）:")
        println("格式示例:")
        println("  connect.sid: 值")
        println("  user: 值")
        println("或者输入 'skip' 跳过，'exit' 退出")
        print("> ")

        val inputLines = mutableListOf<String>()
        var line = readLine()?.trim()

        // 处理特殊命令
        if (line == null || line == "exit") {
            println("已取消更新。")
            return false
        }

        if (line == "skip") {
            println("已跳过Cookie更新，将使用现有配置。")
            return true
        }

        // 收集多行输入
        while (line != null && line.isNotEmpty()) {
            inputLines.add(line)
            print("> ")
            line = readLine()?.trim()
        }

        if (inputLines.isEmpty()) {
            println("未输入任何内容。")
            return false
        }

        val input = inputLines.joinToString("\n")
        return parseAndUpdateExpiredCookies(input)
    }
}

/**
 * 解析并更新过期的Cookie字段
 */
fun parseAndUpdateExpiredCookies(cookieString: String): Boolean {
    try {
        // 解析Cookie字符串
        val cookieMap = mutableMapOf<String, String>()

        // 支持三种格式：
        // 1. 标准HTTP Cookie格式: "key1=value1; key2=value2"
        // 2. 换行分隔格式: "key1: value1\nkey2: value2"
        // 3. 单行冒号格式: "key1: value1"
        if (cookieString.contains('\n')) {
            // 换行格式
            cookieString.split('\n').forEach { line ->
                val trimmedLine = line.trim()
                if (trimmedLine.isNotEmpty() && trimmedLine.contains(':')) {
                    val parts = trimmedLine.split(':', limit = 2)
                    if (parts.size == 2) {
                        cookieMap[parts[0].trim()] = parts[1].trim()
                    }
                }
            }
        } else if (cookieString.contains(':') && !cookieString.contains('=')) {
            // 单行冒号格式
            val parts = cookieString.split(':', limit = 2)
            if (parts.size == 2) {
                cookieMap[parts[0].trim()] = parts[1].trim()
            }
        } else {
            // 标准Cookie格式
            cookieString.split(';').forEach { pair ->
                val trimmedPair = pair.trim()
                if (trimmedPair.isNotEmpty() && trimmedPair.contains('=')) {
                    val parts = trimmedPair.split('=', limit = 2)
                    if (parts.size == 2) {
                        cookieMap[parts[0].trim()] = parts[1].trim()
                    }
                }
            }
        }

        // 调试：显示解析到的Cookie字段
        println("解析到的Cookie字段: ${cookieMap.keys.joinToString(", ")}")

        // 检查是否包含必要的过期字段
        if (!cookieMap.containsKey("user") && !cookieMap.containsKey("connect.sid")) {
            println("错误：Cookie中未找到 user 或 connect.sid 字段")
            println("可用字段: ${cookieMap.keys}")
            return false
        }

        // 获取当前配置
        val currentSession = config!!.wechatSession ?: WeChatSessionConfig()

        // 只更新过期的字段
        var updated = false
        if (cookieMap.containsKey("user")) {
            currentSession.user = cookieMap["user"]
            updated = true
            println("[成功] 已更新 user 字段")
        }
        if (cookieMap.containsKey("connect.sid")) {
            currentSession.connectSid = cookieMap["connect.sid"]
            updated = true
            println("[成功] 已更新 connect.sid 字段")
        }

        if (!updated) {
            println("警告：未找到需要更新的字段")
            return false
        }

        // 如果有user字段，验证并显示过期时间
        cookieMap["user"]?.let { userValue ->
            try {
                val decodedUser = java.net.URLDecoder.decode(userValue, "UTF-8")
                val userJson = GSON.fromJson(decodedUser, com.google.gson.JsonObject::class.java)
                val expireTime = userJson.get("expire")?.asString
                val userId = userJson.get("userid")?.asString

                if (expireTime != null && userId != null) {
                    println("[信息] 用户ID: $userId")
                    println("[信息] 过期时间: $expireTime")
                }
            } catch (e: Exception) {
                logger.warn { "解析用户信息失败，但Cookie已更新" }
            }
        }

        // 保存配置
        config!!.wechatSession = currentSession
        Config.saveConfig()

        // 清除现有的auth对象，强制重新创建
        auth = null
        cookieCathe.clear()

        println("[成功] Cookie已成功更新并保存到配置文件！")
        return true

    } catch (e: Exception) {
        logger.error(e) { "解析Cookie失败" }
        println("解析Cookie失败: ${e.message}")
        return false
    }
}

// 打印帮助信息
fun printHelp() {
    println("""
        山东大学图书馆自动选座脚本使用说明

        用法: java -jar sdu-seat-2.0.jar [配置文件路径]

        参数:
          配置文件路径    JSON格式的配置文件路径，默认为当前目录下的config.json
          --help, -h     显示此帮助信息

        Cookie管理:
          程序启动时会自动检查微信会话状态：
          - 如果会话过期或缺失，会提示输入最新的Cookie
          - 只需输入会过期的字段（user 和 connect.sid）
          - 固定字段（userObj, school, dinepo）只需在配置文件中设置一次
        
        配置文件示例:
        {
          "userid": "你的学号",
          "deviceId": "设备ID（可选）",
          "area": "趵突泉馆-一楼",
          "seats": {
            "趵突泉阅览室112": ["21","20","11","12","18","19","13","06","07","08","10","14","05","09"],
            "趵突泉专业阅览室104": ["11","08","14","04"]
          },
          "filterRule": "",
          "only": false,
          "time": "06:02",
          "retry": 10,
          "retryInterval": 1,
          "delta": 0,
          "bookOnce": false,
          "wechatSession": {
            "autoInject": true,
            "userObj": "固定字段 - 从微信Cookie中获取的用户对象信息（JSON格式，URL编码）",
            "school": "固定字段 - 从微信Cookie中获取的学校配置信息（JSON格式，URL编码）",
            "dinepo": "固定字段 - 从微信Cookie中获取的微信OpenID",
            "user": "需要定期更新 - 格式: %7B%22userid%22%3A%22学号%22%2C%22access_token%22%3A%22token%22%2C%22expire%22%3A%22过期时间%22%7D",
            "connectSid": "需要定期更新 - 格式: s%3Asession_id.signature",
            "redirectUri": ""
          },
          "emailNotification": {
            "enable": false,
            "smtpHost": "smtp.qq.com",
            "smtpPort": 465,
            "username": "your-email@qq.com",
            "password": "your-auth-code",
            "recipientEmail": "recipient@example.com",
            "sslEnable": true
          }
        }
        
        详细说明请参考 README.md 文件或项目文档。
    """.trimIndent())
}

fun loginAndGetSeats(judgeExpire: Boolean = true) {
    val loginStartTime = System.currentTimeMillis()
    logger.info { "开始登录和获取座位信息，时间: ${java.text.SimpleDateFormat("HH:mm:ss.SSS").format(Date(loginStartTime))}" }

    val spiderRes: Future<*> = threadPool.submit(spiderRunnable)

    if (auth == null || (judgeExpire && auth!!.isExpire()) || needReLogin) {
        cookieCathe.clear()
        val authRes = threadPool.submit(authRunnable)
        try {
            authRes.get()
            val loginEndTime = System.currentTimeMillis()
            logger.info { "[登录完成] 耗时: ${loginEndTime - loginStartTime}ms" }
            logger.info { "================================================================" }
        } catch (e: Exception) {
            // 检查是否是需要Cookie的认证异常
            val isAuthCookieNeeded = e.cause?.message?.contains("需要微信登录Cookie") == true ||
                                   e.message?.contains("需要微信登录Cookie") == true

            if (!isAuthCookieNeeded) {
                handleLoginError(e)
            }
            throw e
        }
    } else {
        logger.info { "[跳过登录] 使用现有认证" }
    }

    try {
        spiderRes.get()
    } catch (e: Exception) {
        handleSpiderError(e)
        throw e
    }
}

private fun handleLoginError(e: Exception) {
    if (e.cause is SocketTimeoutException) {
        logger.error { "登录失败：网络请求超时" }
    } else {
        logger.error(e) { "登录失败" }
    }
}

private fun handleSpiderError(e: Exception) {
    if (e.cause is SocketTimeoutException) {
        logger.error { "获取座位信息失败：网络请求超时" }
    } else {
        logger.error(e) { "获取座位信息失败" }
    }
}

fun startBook() {
    val currentTime = Date()
    val displayFormat = createDisplayFormat()

    // 显著的预约开始提示
    logger.info { "[开始]" + "=".repeat(56) + "[开始]" }
    logger.info { "[预约任务] 正式开始！" }
    logger.info { "[开始时间] ${displayFormat.format(currentTime)}" }
    logger.info { "[预约日期] ${dateFormat.format(System.currentTimeMillis() + ONE_DAY * config!!.delta)}" }
    logger.info { "[目标区域] ${config!!.area}" }
    logger.info { "[开始]" + "=".repeat(56) + "[开始]" }

    date = dateFormat.format(System.currentTimeMillis() + ONE_DAY * config!!.delta)
    success.clear()
    var allAttemptsFailed = true // 跟踪是否所有尝试都失败了
    
    for (i in 0..config!!.retry) {
        try {
            bookTask()
            allAttemptsFailed = false // 至少一次尝试成功
            break
        } catch (e: Exception) {
            // 检查是否是访问频繁异常，如果是则不再重试
            if (e.message?.contains("访问频繁") == true) {
                logger.error { "预约失败：访问频繁，停止尝试预约" }
                break
            }
            
            // 检查是否是所有预设座位均无法预约且only为true的情况，如果是则不再重试
            if (e.message?.contains("所有预设座位均不可预约") == true && config!!.only) {
                logger.error { "预约失败：${e.message}，停止尝试预约" }
                break
            }
            
            // 其他异常情况，继续重试
            logger.error(e) { "预约失败，将重试" }
        }
        if (i < config!!.retry) {
            logger.info { "尝试预约${i + 1}/${config!!.retry}失败，将在${config!!.retryInterval}秒后重试..." }
            Thread.sleep((config!!.retryInterval * 1000).toLong())
        }
    }
    
    // 如果所有尝试都失败，发送邮件通知
    if (allAttemptsFailed) {
        logger.error { "所有预约尝试均失败，将发送邮件通知" }
        config!!.emailNotification?.let { emailConfig ->
            val subject = "图书馆座位预约失败通知"
            val content = """
                |预约失败！
                |日期：$date
                |
                |失败原因：
                |尝试了${config!!.retry + 1}次预约，但均失败。
                |
                |可能的原因：
                |1. 所有座位均不可预约，可能是预约时间未到或预约已结束
                |2. 如果设置了只预约预设座位，可以考虑关闭"只预约预设座位"选项
                |3. 如果遇到访问频繁，请稍后再试
                |4. 如果问题持续存在，请尝试手动预约或检查配置
            """.trimMargin()
            
            sduseat.utils.EmailUtils.sendEmail(emailConfig, subject, content)
        }
    }
}

fun bookTask() {
    val startTime = System.currentTimeMillis()
    logger.info { "[预约任务] 启动，时间: ${java.text.SimpleDateFormat("HH:mm:ss.SSS").format(Date(startTime))}" }

    if (auth == null || needReLogin) {
        logger.warn { "需要重新登录，这可能会增加延迟..." }
        loginAndGetSeats()
    } else if (auth!!.isExpire()) {
        logger.warn { "会话已过期，需要重新登录..." }
        needReLogin = true
        loginAndGetSeats()
    } else {
        // 额外检查：验证会话剩余时间
        if (auth is Auth) {
            val authInstance = auth as Auth
            val remainingMinutes = authInstance.getRemainingMinutes()
            if (remainingMinutes <= 0) {
                logger.warn { "会话剩余时间已耗尽，需要重新登录..." }
                needReLogin = true
                loginAndGetSeats()
                return
            } else {
                logger.info { "[登录状态] 使用现有登录状态，剩余时间: ${remainingMinutes} 分钟" }
            }
        } else {
            logger.info { "[登录状态] 使用现有登录状态，直接开始预约" }
        }

        if (querySeats.isEmpty() || querySeats.values.all { it.isNullOrEmpty() }) {
            logger.warn { "座位信息为空，快速获取..." }
            getAllSeats()
        }
    }

    // 检查是否需要重新登录
    if (needReLogin) {
        logger.error { "[认证失效] 检测到认证已失效，需要重新获取Cookie" }
        logger.error { "[认证失效] 请按照以下步骤重新获取微信Cookie：" }
        logger.error { "[认证失效] 1. 使用微信打开 http://seatwx.lib.sdu.edu.cn/" }
        logger.error { "[认证失效] 2. 登录后按F12打开开发者工具" }
        logger.error { "[认证失效] 3. 在Network标签页找到请求，复制Cookie" }
        logger.error { "[认证失效] 4. 更新配置文件中的Cookie信息" }
        throw LibException("认证已失效，需要重新获取Cookie")
    }

    for (periodKey in periods.keys) {
        // 确保success映射中有对应的键
        if (!success.containsKey(periodKey)) {
            success[periodKey] = false
        }

        if (!success[periodKey]!!) {
            try {
                val periodTime = "${periods[periodKey]!!.startTime}-${periods[periodKey]!!.endTime}"
                val bookStartTime = System.currentTimeMillis()
                logger.info { "[开始预约] ${date} ${periodTime}时间段座位，时间: ${java.text.SimpleDateFormat("HH:mm:ss.SSS").format(Date(bookStartTime))}" }

                val availablePreferredSeats = querySeats[periodKey]!!.filter { it.status == 1 }
                logger.info { "时间段${periodTime}有${availablePreferredSeats.size}个预设座位可预约" }

                val attemptDetails = mutableListOf<String>()

                querySeats[periodKey]!!.filter { it.status != 1 }.forEach { seat ->
                    attemptDetails.add("预设座位 ${seat.area.name}-${seat.name} 状态为 ${getSeatStatusDescription(seat.status)}")
                }
                
                var curSuccess = false
                val allPreferredSeatsUnavailable = availablePreferredSeats.isEmpty()

                if (allPreferredSeatsUnavailable && config!!.only) {
                    logger.info { "时间段${periodTime}所有预设座位均不可预约，且设置了只预约预设座位，停止尝试预约" }
                    attemptDetails.add("所有预设座位均不可预约，且设置了只预约预设座位")
                    success[periodKey] = false
                    throw LibException("所有预设座位均不可预约")
                }

                curSuccess = when {
                    availablePreferredSeats.isNotEmpty() -> {
                        val seatToBook = availablePreferredSeats.first()
                        logger.info { "尝试预约座位: ${seatToBook.area.name}-${seatToBook.name}" }
                        val result = bookSingleSeat(seatToBook, periodKey, periodTime)
                        if (!result) {
                            attemptDetails.add("尝试预约座位 ${seatToBook.area.name}-${seatToBook.name} 失败")
                            // 检查是否因为认证问题失败
                            if (needReLogin) {
                                logger.warn { "检测到认证失效，停止当前预约尝试" }
                                return
                            }
                        }
                        result
                    }
                    !config!!.only -> {
                        logger.info { "预约${periodTime}时间段座位：预设座位均无法预约，将预约预设区域的空闲座位" }
                        val availableSeats = allSeats[periodKey]!!.filter { it.status == 1 }

                        if (availableSeats.isEmpty()) {
                            logger.info { "时间段${periodTime}区域内没有可用座位，但会继续尝试" }
                            attemptDetails.add("区域内没有可用座位，将继续尝试预约")
                            success[periodKey] = false
                            false
                        } else {
                            val seatToBook = availableSeats.first()
                            logger.info { "尝试预约座位: ${seatToBook.area.name}-${seatToBook.name}" }
                            val result = bookSingleSeat(seatToBook, periodKey, periodTime)
                            if (!result) {
                                attemptDetails.add("尝试预约座位 ${seatToBook.area.name}-${seatToBook.name} 失败")
                                // 检查是否因为认证问题失败
                                if (needReLogin) {
                                    logger.warn { "检测到认证失效，停止当前预约尝试" }
                                    return
                                }
                            }
                            result
                        }
                    }
                    else -> {
                        attemptDetails.add("没有可用的预设座位，且设置了只预约预设座位")
                        false
                    }
                }
                
                success[periodKey] = curSuccess

                if (!curSuccess) {
                    logger.warn { "预约${periodTime}时间段座位：所有座位均无法预约，将继续尝试" }
                }
            } catch (e: Exception) {
                logger.error(e) { "预约时段 ${periods[periodKey]!!.startTime}-${periods[periodKey]!!.endTime} 时发生异常" }

                if (e.message?.contains("访问频繁") == true) {
                    logger.info { "检测到访问频繁限制，停止尝试预约" }
                }

                throw e
            }
        }
    }
    
    // 检查是否有预约失败的情况
    val failedPeriods = periods.keys.filter { !success.getOrDefault(it, false) }
    if (failedPeriods.isNotEmpty()) {
        val failureMessages = failedPeriods.map { periodKey -> 
            val periodTime = "${periods[periodKey]!!.startTime}-${periods[periodKey]!!.endTime}"
            val seats = querySeats[periodKey]!!
            val availableSeats = seats.filter { it.status == 1 }
            val allSeatsInArea = allSeats[periodKey]!!
            val otherAvailableSeats = if (!config!!.only) {
                allSeatsInArea.filter { it.status == 1 }
            } else {
                emptyList()
            }
            
            val details = StringBuilder()
            details.append("时间段：$periodTime\n")
            details.append("预设座位状态：\n")
            
            if (seats.isEmpty()) {
                details.append("- 未找到任何预设座位\n")
            } else {
                seats.forEach { seat ->
                    details.append("- ${seat.area.name}-${seat.name}: ${getSeatStatusDescription(seat.status)}\n")
                }
            }
            
            if (!config!!.only && otherAvailableSeats.isNotEmpty()) {
                details.append("\n区域内其他可用座位：\n")
                otherAvailableSeats.take(5).forEach { seat ->
                    details.append("- ${seat.area.name}-${seat.name}\n")
                }
                if (otherAvailableSeats.size > 5) {
                    details.append("- ... 等共${otherAvailableSeats.size}个座位\n")
                }
            }
            
            details.append("\n预约失败原因：\n")
            if (availableSeats.isEmpty()) {
                if (config!!.only) {
                    details.append("- 所有预设座位均不可预约，且设置了只预约预设座位\n")
                } else if (otherAvailableSeats.isEmpty()) {
                    details.append("- 所有座位均不可预约\n")
                }
            } else {
                details.append("- 预约过程中发生错误，具体原因请查看日志\n")
            }
            
            details.toString()
    }
        
        // 发送一个详细的汇总邮件
            config!!.emailNotification?.let { emailConfig ->
            val subject = "图书馆座位预约失败通知"
                val content = """
                |预约失败！
                    |日期：$date
                    |
                    |失败详情：
                    |${failureMessages.joinToString("\n|")}
                    |
                |建议操作：
                |1. 如果所有座位均不可预约，可能是预约时间未到或预约已结束
                |2. 如果只有预设座位不可预约，可以考虑关闭"只预约预设座位"选项
                |3. 如果遇到访问频繁，请稍后再试
                |4. 如果问题持续存在，请尝试手动预约或联系管理员处理
                """.trimMargin()
                
                sduseat.utils.EmailUtils.sendEmail(emailConfig, subject, content)
            }
        
        throw LibException("部分时段预约失败")
    }
    
    clear()
}

/**
 * 预约单个座位
 */
fun bookSingleSeat(
    seat: SeatBean,
    periodIndex: Int,
    periodTime: String
): Boolean {
    // 移除固定延迟，立即进行预约请求以提高速度

    logger.info { "正在预约座位: ${seat.area.name}-${seat.name} (区域ID: ${seat.area.id}, 座位ID: ${seat.id})" }
    logger.info { "预约时间段: $periodTime (时段索引: $periodIndex)" }
    logger.info { "预约日期: $date" }

    val res = Lib.book(seat, date, auth!!, periodIndex, config!!.retry)

    // 检查是否返回访问频繁的信息
    if (Lib.lastResponseMessage?.contains("访问频繁") == true) {
        // 抛出异常，让上层处理
        throw LibException("访问频繁！${Lib.lastResponseMessage}")
    }

    // 检查是否需要重新认证
    if (res == 2) {
        logger.warn { "座位 ${seat.area.name}-${seat.name} 预约失败，需要重新认证: ${Lib.lastResponseMessage}" }
        logger.warn { "需要重新认证，请重新获取微信Cookie并更新配置文件" }
        needReLogin = true
        return false
    }

    // 检查是否返回了预约失败的信息
    if (res == 0 && Lib.lastResponseMessage != null) {
        logger.error { "座位 ${seat.area.name}-${seat.name} 预约失败: ${Lib.lastResponseMessage}" }

        // 如果是因为座位已被预约，则不再尝试
        if (Lib.lastResponseMessage!!.contains("已被预约") ||
            Lib.lastResponseMessage!!.contains("已被占用") ||
            Lib.lastResponseMessage!!.contains("已被选择")) {
            logger.info { "座位 ${seat.area.name}-${seat.name} 已被他人预约，停止尝试" }
        }

        return false
    }
    
            if (res == 3) {
        logger.info { "座位 ${seat.area.name}-${seat.name} 当前状态无法预约" }
        return false
            } else if (res == 2) {
                needReLogin = true
        logger.info { "座位 ${seat.area.name}-${seat.name} 需要重新登录" }
        return false
            }
    
    val success = res == 1
            
            if (success) {
                // 发送邮件通知
                config!!.emailNotification?.let { emailConfig ->
                    val subject = "图书馆座位预约成功通知"
                    val content = """
                        |预约成功！
                        |日期：$date
                        |时间段：$periodTime
                        |区域：${seat.area.name}
                        |座位号：${seat.name}
                        |
                        |祝您学习愉快！
                    """.trimMargin()
                    
                    sduseat.utils.EmailUtils.sendEmail(emailConfig, subject, content)
                }
    }
    
    return success
}

/**
 * 获取座位状态的描述
 */
fun getSeatStatusDescription(status: Int): String {
    return when (status) {
        0 -> "不可用"
        1 -> "可预约"
        2 -> "已预约"
        3 -> "暂离"
        4 -> "使用中"
        else -> "未知状态($status)"
    }
}

/**
 * 标准化座位号格式
 * 将2位数座位号转换为3位数格式（如"11" -> "011"）
 * 已经是3位数的保持不变
 */
fun normalizeSeatNumber(seatNumber: String): String {
    return when {
        seatNumber.length == 2 && seatNumber.all { it.isDigit() } -> "0$seatNumber"
        else -> seatNumber
    }
}

/**
 * 查找座位，支持2位数和3位数格式的智能匹配
 */
fun findSeatByNumber(seats: Map<String, SeatBean>, configSeatNumber: String): SeatBean? {
    // 首先尝试直接匹配
    if (seats.containsKey(configSeatNumber)) {
        return seats[configSeatNumber]
    }

    // 如果直接匹配失败，尝试标准化后匹配
    val normalizedSeatNumber = normalizeSeatNumber(configSeatNumber)
    if (seats.containsKey(normalizedSeatNumber)) {
        return seats[normalizedSeatNumber]
    }

    // 如果配置的是3位数，也尝试匹配2位数版本
    if (configSeatNumber.length == 3 && configSeatNumber.startsWith("0")) {
        val twoDigitVersion = configSeatNumber.substring(1)
        if (seats.containsKey(twoDigitVersion)) {
            return seats[twoDigitVersion]
        }
    }

    return null
}

fun getAllSeats() {
    periods.clear()
    allSeats.clear()
    querySeats.clear()

    // 提取图书馆名称，用于后续的ID映射
    val libName = config!!.area!!.substringBefore("-")
    val subLibName = config!!.area!!.substringAfter("-")

    logger.info { "配置的区域: ${config!!.area}" }
    logger.info { "解析的图书馆: $libName, 子区域: $subLibName" }
    logger.info { "配置的座位区域: ${config!!.seats!!.keys.joinToString(", ")}" }

    if (area == null) {
        try {
            //获取图书馆信息
            val libs = Spider.getLibs(config!!.retry)
            logger.info { "可用图书馆: ${libs.keys.joinToString(", ")}" }

            // 优先使用API实时获取区域信息
            area = getAreaFromAPI(libName, subLibName)
            if (area != null) {
                // Area retrieved successfully from API
            } else {
                // API获取失败，尝试传统方式
                val lib = libs[libName]
                if (lib == null) {
                    logger.warn { "无法找到图书馆: $libName，尝试直接使用已知区域ID" }
                    area = getKnownAreaByName(libName, subLibName)
                    logger.info { "使用已知区域映射: ${area?.name} (ID: ${area?.id})" }
                } else {
                    logger.info { "正在获取子区域信息: $subLibName" }
                    val subLibs = Spider.getAreas(lib, date, config!!.retry)
                    logger.info { "可用子区域: ${subLibs.keys.joinToString(", ")}" }

                    area = subLibs[subLibName]
                    if (area == null) {
                        logger.warn { "无法找到子区域: $subLibName，尝试从API获取" }
                        area = getAreaFromAPI(libName, subLibName)
                        if (area == null) {
                            area = getKnownAreaByName(libName, subLibName)
                            logger.info { "使用已知区域映射: ${area?.name} (ID: ${area?.id})" }
                        }
                    } else {
                        logger.info { "找到匹配的子区域: ${area!!.name} (ID: ${area!!.id})" }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "获取图书馆信息失败，尝试从API直接获取区域信息" }
            area = getAreaFromAPI(libName, subLibName)
            if (area == null) {
                area = getKnownAreaByName(libName, subLibName)
                logger.info { "使用已知区域映射: ${area?.name} (ID: ${area?.id})" }
            } else {
                // Area retrieved successfully from API
            }
        }

        if (area == null) {
            throw SpiderException("无法找到区域: $libName-$subLibName，请检查配置")
        }
    }



    //直接获取座位信息（跳过复杂的区域层级）
    logger.info { "正在获取座位信息" }
    val seatsByArea = try {
        Spider.getSeatsForConfiguredAreas(config!!.seats!!.keys, date, "08:00", "22:30", config!!.retry)
    } catch (e: Exception) {
        logger.error(e) { "获取座位信息失败，使用之前的缓存或默认方式" }
        // 如果有缓存，使用缓存
        if (directSeatsCache != null) {
            logger.info { "使用缓存的座位信息" }
            directSeatsCache!!
        } else {
            // 返回空的座位信息，让后续逻辑处理
            logger.warn { "没有可用的座位信息缓存，将使用传统方式获取" }
            emptyMap()
        }
    }

    // 将座位信息转换为区域信息
    val subLib = mutableMapOf<String, AreaBean>()
    if (seatsByArea.isNotEmpty()) {
        // 只获取一次时间段信息，避免重复请求
        val libId = getLibraryIdForArea(libName, area!!.id!!)
        val periods = Spider.getAreaPeriods(libId, date, 1)

        seatsByArea.forEach { (areaName, seats) ->
            val availableSeats = seats.values.count { it.status == 1 }
            val totalSeats = seats.size
            subLib[areaName] = AreaBean(area!!.id, areaName, availableSeats, totalSeats, periods)
        }
    } else {
        logger.warn { "未获取到座位信息，尝试使用传统方式获取区域信息" }
        // 如果直接获取座位失败，尝试使用传统的区域获取方式
        try {
            val libs = Spider.getLibs(config!!.retry)
            val lib = libs.values.firstOrNull { it.id == area!!.id }
            if (lib != null) {
                val areas = Spider.getAreas(lib, date, config!!.retry)
                subLib.putAll(areas)
                logger.info { "使用传统方式成功获取到 ${subLib.size} 个区域" }
            }
        } catch (e: Exception) {
            logger.error(e) { "传统方式获取区域信息也失败" }
        }
    }

    // 保存座位信息供后续使用
    directSeatsCache = seatsByArea



    if (subLib.values.isNotEmpty()) {
        val curPeriods = subLib.values.first().periods
        val getSeatTasks = mutableListOf<Future<*>>()
        if (curPeriods.isNullOrEmpty()) {
            logger.warn { "未获取到可预约时间段，使用默认时间段。请使用http://seatwx.lib.sdu.edu.cn:85/api.php/areadays/${area!!.id}" }
            getSeatTasks.add(threadPool.submit { getSeats(subLib) })
        } else {
            curPeriods.forEachIndexed { i, p ->
                periods[i] = p
                if (!success.containsKey(i)) success[i] = false
                getSeatTasks.add(threadPool.submit {
                    getSeats(subLib, i, "${p.startTime}-${p.endTime}")
                })
            }
        }
        getSeatTasks.forEach {
            it.get()
        }
    } else {
        throw SpiderException("未获取到任何座位区域信息")
    }
}

fun getSeats(subLib: Map<String, AreaBean>, periodIndex: Int = 0, periodTime: String = "08:00-22:30") {
    var log = "\n-------------获取$date ${periodTime}时间段座位-------------\n"
    val curQuerySeats = mutableListOf<SeatBean>()
    val curAllSeats = mutableListOf<SeatBean>()

    // 优先使用缓存的座位信息
    if (directSeatsCache != null) {
        logger.debug { "使用缓存的座位信息，缓存区域数量: ${directSeatsCache!!.size}" }
        logger.debug { "缓存区域列表: ${directSeatsCache!!.keys.joinToString(", ")}" }

        config!!.seats!!.forEach { (k, v) ->
            if (directSeatsCache!!.containsKey(k)) {
                val curSeats = directSeatsCache!![k]!!
                logger.debug { "区域 $k 座位列表: ${curSeats.keys.take(10).joinToString(", ")}${if (curSeats.size > 10) "..." else ""}" }
                curAllSeats.addAll(curSeats.values)

                v.forEach { seatName ->
                    val foundSeat = findSeatByNumber(curSeats, seatName)
                    if (foundSeat != null) {
                        curQuerySeats.add(foundSeat)
                    } else {
                        log += "无法查找到座位[$k-$seatName]，可用座位: ${curSeats.keys.joinToString(", ")}\n"
                        logger.warn { "座位匹配失败: $k-$seatName，可用座位: ${curSeats.keys.take(20).joinToString(", ")}" }
                    }
                }
            } else {
                log += "无法查找到区域[$k]，可用区域: ${directSeatsCache!!.keys.joinToString(", ")}\n"
                logger.warn { "区域匹配失败: $k，可用区域: ${directSeatsCache!!.keys.joinToString(", ")}" }
            }
        }
    } else {
        // 使用传统方式获取座位信息
        logger.info { "使用传统方式获取座位信息，可用区域数量: ${subLib.size}" }
        logger.debug { "可用区域列表: ${subLib.keys.joinToString(", ")}" }

        config!!.seats!!.forEach { (k, v) ->
            if (subLib.keys.contains(k)) {
                //获取座位信息
                val curSeats = Spider.getSeats(subLib[k], date, periodIndex, config!!.retry)
                logger.debug { "区域 $k 获取到 ${curSeats.size} 个座位: ${curSeats.keys.take(10).joinToString(", ")}${if (curSeats.size > 10) "..." else ""}" }
                curAllSeats.addAll(curSeats.values)
                v.forEach { seatName ->
                    val foundSeat = findSeatByNumber(curSeats, seatName)
                    if (foundSeat != null) {
                        curQuerySeats.add(foundSeat)
                    } else {
                        log += "无法查找到座位[$k-$seatName]，请检查提供的区域信息\n"
                        logger.warn { "座位匹配失败: $k-$seatName，可用座位: ${curSeats.keys.take(20).joinToString(", ")}" }
                    }
                }
            } else {
                log += "无法查找到区域[$k]，请检查提供的座位信息\n"
                logger.warn { "区域匹配失败: $k，可用区域: ${subLib.keys.joinToString(", ")}" }
            }
        }
    }

    querySeats[periodIndex] = curQuerySeats
    allSeats[periodIndex] = curAllSeats
    if (allSeats[periodIndex].isNullOrEmpty()) {
        throw SpiderException("获取${periodTime}时间段座位：未查找到任何预设区域，请检查提供的区域信息")
    }
    if (!querySeats[periodIndex].isNullOrEmpty()) {
        val seatsInfo = querySeats[periodIndex]!!.joinToString(", ", "[", "]") { "${it.area.name}-${it.name}" }
        log += "成功获取到${querySeats[periodIndex]!!.size}个预设座位信息：\n${seatsInfo}"
    } else {
        log += "未获取到预设座位信息，将预约预设区域的空闲座位"
    }
    logger.info { log }
}



fun printInfo() {
    println(Const.javaClass.getResource("/banner.txt")?.readText())
}

fun clear() {
    allSeats.clear()
    querySeats.clear()
    periods.clear()
    success.clear()
}

/**
 * 显示微信登录指南
 */
fun showWeChatLoginGuide() {
    // 检查是否有二维码文件
    val qrCodeExists = java.io.File("wechat_login_qr.png").exists()

    if (qrCodeExists) {
        println("""
            |
            |==================== 微信登录指南 ====================
            |
            |SDU图书馆系统需要通过微信获取登录Cookie。
            |
            |操作步骤：
            |1. 在微信中打开：http://seatwx.lib.sdu.edu.cn/ 或扫描二维码：wechat_login_qr.png
            |2. 完成登录后，按F12打开开发者工具
            |3. 在Network标签页中刷新页面
            |4. 复制任意请求的Cookie头信息
            |5. 将固定字段(userObj, school, dinepo)配置到config.json文件
            |6. 重新运行程序，或直接输入会过期的字段
            |
            |快速更新方式 - 只需输入会过期的字段：
            |
            |格式1 - 标准格式：
            |user=%7B%22userid%22%3A123...; connect.sid=s%3Asession...
            |
            |格式2 - 换行格式：
            |user: %7B%22userid%22%3A123...
            |connect.sid: s%3Asession...
            |
            |注意：userObj, school, dinepo 是固定的，只需在配置文件中设置一次
            |只有 user 和 connect.sid 会过期，需要定期更新
            |
            |或者，您可以选择手动输入过期的Cookie字段：
            |
            |====================================================
            |
        """.trimMargin())
    } else {
        println("""
            |
            |==================== 微信登录指南 ====================
            |
            |SDU图书馆系统需要通过微信获取登录Cookie。
            |
            |操作步骤：
            |1. 在微信中打开：http://seatwx.lib.sdu.edu.cn/
            |2. 完成登录后，按F12打开开发者工具
            |3. 在Network标签页中刷新页面
            |4. 复制任意请求的Cookie头信息
            |5. 将固定字段(userObj, school, dinepo)配置到config.json文件
            |6. 重新运行程序，或直接输入会过期的字段
            |
            |快速更新方式 - 只需输入会过期的字段：
            |
            |格式1 - 标准格式：
            |user=%7B%22userid%22%3A123...; connect.sid=s%3Asession...
            |
            |格式2 - 换行格式：
            |user: %7B%22userid%22%3A123...
            |connect.sid: s%3Asession...
            |
            |注意：userObj, school, dinepo 是固定的，只需在配置文件中设置一次
            |只有 user 和 connect.sid 会过期，需要定期更新
            |
            |或者，您可以选择手动输入过期的Cookie字段：
            |
            |====================================================
            |
        """.trimMargin())
    }

    print("请输入完整的Cookie字符串（输入'skip'跳过）: ")
    val input = readLine()?.trim()

    if (input != null && input.isNotEmpty() && input != "skip") {
        try {
            // 解析Cookie并更新配置
            if (parseCookieAndUpdateConfig(input)) {
                println("Cookie已成功解析并保存到配置文件！")
                println("正在重新启动预约程序...")

                // 重新加载配置并启动
                Config.initConfig(arrayOf())
                date = dateFormat.format(System.currentTimeMillis() + ONE_DAY * config!!.delta)

                try {
                    loginAndGetSeats()
                    if (config!!.bookOnce) {
                        startBook()
                    } else {
                        // 启动定时预约（使用main函数中的逻辑）
                        startTimerBooking()
                    }
                } catch (e: Exception) {
                    logger.error(e) { "使用新Cookie登录失败" }
                    println("Cookie可能无效，请重新获取。")
                }
            } else {
                println("Cookie格式无效，请检查后重新输入。")
            }
        } catch (e: Exception) {
            logger.error(e) { "处理Cookie时发生错误" }
            println("处理Cookie时发生错误: ${e.message}")
        }
    }
}

/**
 * 启动定时预约
 */
fun startTimerBooking() {
    val sdf = SimpleDateFormat("yyyy-MM-dd " + config!!.time)
    var startTime = when (config!!.time!!.length) {
        12 -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").parse(sdf.format(Date()))
        8 -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(sdf.format(Date()))
        else -> SimpleDateFormat("yyyy-MM-dd HH:mm").parse(sdf.format(Date()))
    }
    // 如果已过当天设置时间，修改首次运行时间为明天
    if (System.currentTimeMillis() > startTime.time) {
        startTime = Date(startTime.time + ONE_DAY)
    }
    // 根据配置的时间格式创建对应的显示格式
    val displayFormat = createDisplayFormat()
    val formattedTime = displayFormat.format(startTime)

    // 增强预约时间显示
    logger.info { "=" + "=".repeat(59) }
    logger.info { "[预约时间] $formattedTime" }
    logger.info { "[当前时间] ${displayFormat.format(Date())}" }
    logger.info { "[等待时间] ${(startTime.time - System.currentTimeMillis()) / 1000}秒" }
    logger.info { "=" + "=".repeat(59) }
    logger.info { "请等待到 $formattedTime 开始预约..." }

    val time = Timer()



    // 预约任务
    val bookTask = object : TimerTask() {
        override fun run() {
            try {
                logger.info { "[预约任务] 启动，时间: ${SimpleDateFormat("HH:mm:ss.SSS").format(Date())}" }
                bookTask()
            } catch (e: Exception) {
                logger.error(e) { "预约任务执行失败" }
            }
        }
    }



    // 启动预约任务
    time.schedule(bookTask, startTime, config!!.retryInterval.toLong() * 1000)
}

/**
 * 解析Cookie字符串并更新配置文件
 */
fun parseCookieAndUpdateConfig(cookieString: String): Boolean {
    try {
        // 解析Cookie字符串
        val cookieMap = mutableMapOf<String, String>()

        // 支持两种格式：
        // 1. 标准HTTP Cookie格式: "key1=value1; key2=value2"
        // 2. 换行分隔格式: "key1: value1\nkey2: value2"

        if (cookieString.contains("\n") || cookieString.contains(": ")) {
            // 换行分隔格式
            cookieString.split("\n").forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    val colonIndex = trimmed.indexOf(": ")
                    if (colonIndex > 0) {
                        val key = trimmed.substring(0, colonIndex).trim()
                        val value = trimmed.substring(colonIndex + 2).trim()
                        cookieMap[key] = value
                    }
                }
            }
        } else {
            // 标准HTTP Cookie格式
            cookieString.split(";").forEach { pair ->
                val trimmed = pair.trim()
                val equalIndex = trimmed.indexOf("=")
                if (equalIndex > 0) {
                    val key = trimmed.substring(0, equalIndex).trim()
                    val value = trimmed.substring(equalIndex + 1).trim()
                    cookieMap[key] = value
                }
            }
        }

        // 检查必需的动态Cookie字段（只需要会过期的字段）
        val requiredFields = listOf("user")
        val missingFields = requiredFields.filter { !cookieMap.containsKey(it) }

        if (missingFields.isNotEmpty()) {
            println("Cookie缺少必需字段: ${missingFields.joinToString(", ")}")
            println("提示：只需要输入会过期的字段 user 和 connect.sid（可选）")
            return false
        }

        // 解析user字段获取过期时间
        val userValue = cookieMap["user"]!!
        val decodedUser = java.net.URLDecoder.decode(userValue, "UTF-8")
        val userJson = GSON.fromJson(decodedUser, com.google.gson.JsonObject::class.java)

        val userId = userJson.get("userid")?.asString ?: ""
        val accessToken = userJson.get("access_token")?.asString ?: ""
        val expireTime = userJson.get("expire")?.asString ?: ""

        if (userId.isEmpty() || accessToken.isEmpty() || expireTime.isEmpty()) {
            println("Cookie中的用户信息不完整")
            return false
        }

        // 更新配置 - 只更新动态字段，保留固定字段
        val currentSession = config!!.wechatSession ?: WeChatSessionConfig()

        // 只更新会过期的字段
        if (cookieMap.containsKey("user")) {
            currentSession.user = cookieMap["user"]
        }
        if (cookieMap.containsKey("connect.sid")) {
            currentSession.connectSid = cookieMap["connect.sid"]
        }

        config!!.wechatSession = currentSession

        // 保存配置到文件
        Config.saveConfig()

        // 清除现有的auth对象，强制重新创建
        auth = null
        cookieCathe.clear()

        println("Cookie解析成功:")
        println("  用户ID: $userId")
        println("  过期时间: $expireTime")

        return true

    } catch (e: Exception) {
        logger.error(e) { "解析Cookie失败" }
        println("解析Cookie失败: ${e.message}")
        return false
    }
}

