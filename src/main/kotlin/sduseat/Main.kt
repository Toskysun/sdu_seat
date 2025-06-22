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
import sduseat.bean.PeriodBean
import sduseat.bean.SeatBean
import sduseat.constant.Const
import sduseat.constant.Const.ONE_DAY
import sduseat.constant.Const.dateFormat
import mu.KotlinLogging
import sduseat.http.cookieCathe
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

var config: Config? = null
var date: String = ""
var area: AreaBean? = null
var auth: IAuth? = null
val allSeats = LinkedHashMap<Int, List<SeatBean>>()
val querySeats = LinkedHashMap<Int, List<SeatBean>>()
var periods = LinkedHashMap<Int, PeriodBean>()
var success = LinkedHashMap<Int, Boolean>()
var needReLogin = false // 是否需要重新登录
val threadPool: ExecutorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2).apply {
    Runtime.getRuntime().addShutdownHook(Thread {
        shutdown()
        try {
            if (!awaitTermination(60, TimeUnit.SECONDS)) {
                shutdownNow()
            }
        } catch (_: InterruptedException) {
            shutdownNow()
        }
    })
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
    return if (config!!.webVpn) {
        AuthWebVpn(config!!.userid!!, config!!.passwd!!, config!!.deviceId!!, config!!.maxLoginAttempts)
    } else {
        Auth(config!!.userid!!, config!!.passwd!!, config!!.deviceId!!, config!!.maxLoginAttempts)
    }
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
    date = dateFormat.format(System.currentTimeMillis() + ONE_DAY * config!!.delta)
    if (config!!.bookOnce) {
        startBook()
    } else {
        try {
            loginAndGetSeats()
        } catch (_: Exception) {
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
    logger.info { "请等待到${displayFormat.format(startTime)}" }
    
    val time = Timer()
    
    // 只有在启用提前登录时才创建提前登录任务
    if (config!!.enableEarlyLogin) {
        val earlyStartTime = Date(startTime.time - TimeUnit.MINUTES.toMillis(config!!.earlyLoginMinutes.toLong()))
        val currentTime = System.currentTimeMillis()

        // 检查当前时间是否已经在提前登录时间范围内
        if (currentTime >= earlyStartTime.time) {
            logger.info { "当前时间已在提前登录时间范围内，跳过提前登录任务" }
            logger.info { "提前登录时间：${displayFormat.format(earlyStartTime)}" }
            logger.info { "当前时间：${displayFormat.format(Date(currentTime))}" }
        } else {
            logger.info { "将在${displayFormat.format(earlyStartTime)}提前开始登录尝试" }

            val earlyLoginTask = object : TimerTask() {
                override fun run() {
                    startEarlyLogin(startTime)
                }
            }
            // 提前登录任务
            time.schedule(earlyLoginTask, earlyStartTime)
        }
    } else {
        logger.info { "提前登录功能已禁用" }
    }
    
    val bookTask = object : TimerTask() {
        override fun run() {
            startBook()
        }
    }
    
    // 正常预约任务
    time.scheduleAtFixedRate(bookTask, startTime, ONE_DAY)
}

// 打印帮助信息
fun printHelp() {
    println("""
        山东大学图书馆自动选座脚本使用说明
        
        用法: java -jar sdu-seat-3.0.jar [配置文件路径]
        
        参数:
          配置文件路径    JSON格式的配置文件路径，默认为当前目录下的config.json
          --help, -h     显示此帮助信息
        
        配置文件示例:
        {
          "userid": "学号",
          "passwd": "密码",
          "deviceId": "设备ID",
          "area": "中心馆-图东区(3-4)",
          "seats": {
            "电子阅览室": ["001", "002"]
          },
          "filterRule": "",
          "only": false,
          "time": "06:02:30.500",
          "period": "08:00-22:30",
          "retry": 10,
          "retryInterval": 2,
          "delta": 0,
          "bookOnce": false,
          "webVpn": false,
          "maxLoginAttempts": 50,
          "earlyLoginMinutes": 5
        }
        
        详细说明请参考 README.md 文件或项目文档。
    """.trimIndent())
}

fun loginAndGetSeats(judgeExpire: Boolean = true) {
    var spiderRes: Future<*>? = null
    if (!config!!.webVpn) {
        spiderRes = threadPool.submit(spiderRunnable)
    }
    if (auth == null || (judgeExpire && auth!!.isExpire()) || needReLogin) {
        cookieCathe.clear()
        val authRes = threadPool.submit(authRunnable)
        try {
            authRes.get()
        } catch (e: Exception) {
            if (e.cause is SocketTimeoutException) {
                logger.error { "登录失败：网络请求超时" }
            } else {
                logger.error(e) { }
            }
            throw e
        }
    }
    if (config!!.webVpn) {
        spiderRes = threadPool.submit(spiderRunnable)
    }
    try {
        spiderRes?.get()
    } catch (e: Exception) {
        if (e.cause is SocketTimeoutException) {
            logger.error { "获取座位信息失败：网络请求超时" }
        } else {
            logger.error(e) { }
        }
        throw e
    }
}

fun startBook() {
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
    loginAndGetSeats()
    
    // 改为串行处理预约，避免并发请求导致访问频繁限制
    for (periodKey in periods.keys) {
        if (!success[periodKey]!!) {
            try {
                val periodTime = "${periods[periodKey]!!.startTime}-${periods[periodKey]!!.endTime}"
                logger.info { "开始预约${date} ${periodTime}时间段座位" }
                
                // 首先，检查预设座位中哪些是可预约的
                val availablePreferredSeats = querySeats[periodKey]!!.filter { it.status == 1 }
                logger.info { "时间段${periodTime}有${availablePreferredSeats.size}个预设座位可预约" }
                
                // 记录尝试详情，用于失败日志
                val attemptDetails = mutableListOf<String>()
                
                // 记录不可用的预设座位信息
                querySeats[periodKey]!!.filter { it.status != 1 }.forEach { seat ->
                    attemptDetails.add("预设座位 ${seat.area.name}-${seat.name} 状态为 ${getSeatStatusDescription(seat.status)}")
                }
                
                var curSuccess = false
                
                // 检查是否所有座位都不可预约
                val allPreferredSeatsUnavailable = availablePreferredSeats.isEmpty()
                
                // 如果所有预设座位都不可预约，且不允许预约其他座位，则直接结束此时段的预约
                if (allPreferredSeatsUnavailable && config!!.only) {
                    logger.info { "时间段${periodTime}所有预设座位均不可预约，且设置了只预约预设座位，停止尝试预约" }
                    attemptDetails.add("所有预设座位均不可预约，且设置了只预约预设座位")
                    success[periodKey] = false
                    
                    // 抛出特定异常，让上层处理
                    throw LibException("所有预设座位均不可预约")
                }
                
                if (availablePreferredSeats.isNotEmpty()) {
                    // 只尝试预约第一个可用的预设座位
                    val seatToBook = availablePreferredSeats.first()
                    logger.info { "尝试预约座位: ${seatToBook.area.name}-${seatToBook.name}" }
                    curSuccess = bookSingleSeat(seatToBook, periodKey, periodTime)
                    if (!curSuccess) {
                        attemptDetails.add("尝试预约座位 ${seatToBook.area.name}-${seatToBook.name} 失败")
                    }
                } else {
                    // 如果没有可用的预设座位，且不限制只预约预设座位，则尝试预约其他座位
                if (!config!!.only) {
                    logger.info { "预约${periodTime}时间段座位：预设座位均无法预约，将预约预设区域的空闲座位" }
                        val availableSeats = allSeats[periodKey]!!.filter { it.status == 1 }
                        
                        if (availableSeats.isEmpty()) {
                            // 如果区域内没有可用座位，记录日志但不终止预约流程
                            logger.info { "时间段${periodTime}区域内没有可用座位，但会继续尝试" }
                            attemptDetails.add("区域内没有可用座位，将继续尝试预约")
                            
                            // 不再抛出异常，仅记录信息
                            success[periodKey] = false
                        } else {
                            // 只尝试预约第一个可用的座位
                            val seatToBook = availableSeats.first()
                            logger.info { "尝试预约座位: ${seatToBook.area.name}-${seatToBook.name}" }
                            curSuccess = bookSingleSeat(seatToBook, periodKey, periodTime)
                            if (!curSuccess) {
                                attemptDetails.add("尝试预约座位 ${seatToBook.area.name}-${seatToBook.name} 失败")
                }
                        }
                    } else {
                        attemptDetails.add("没有可用的预设座位，且设置了只预约预设座位")
                    }
                }
                
                success[periodKey] = curSuccess
                
                if (!curSuccess) {
                    val errorMsg = "预约${periodTime}时间段座位：所有座位均无法预约，将继续尝试"
                    logger.warn { errorMsg }
                    
                    // 不再抛出异常，继续后续尝试
                }
            } catch (e: Exception) {
                logger.error(e) { "预约时段 ${periods[periodKey]!!.startTime}-${periods[periodKey]!!.endTime} 时发生异常" }
                
                // 检查是否是访问频繁异常
                if (e.message?.contains("访问频繁") == true) {
                    logger.info { "检测到访问频繁限制，停止尝试预约" }
                    
                    // 直接抛出异常，让上层处理
                    throw e
                }
                
                // 重新抛出异常，让上层处理
                throw e
            }

            // 移除固定延迟，只使用配置文件中的 retryInterval 设置
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

    val res = Lib.book(seat, date, auth!!, periodIndex, config!!.retry)
    
    // 检查是否返回访问频繁的信息
    if (Lib.lastResponseMessage?.contains("访问频繁") == true) {
        // 抛出异常，让上层处理
        throw LibException("访问频繁！${Lib.lastResponseMessage}")
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

fun getAllSeats() {
    periods.clear()
    allSeats.clear()
    querySeats.clear()
    if (area == null) {
        val libName = config!!.area!!.substringBefore("-")
        val subLibName = config!!.area!!.substringAfter("-")
        //获取图书馆信息
        val lib = Spider.getAreas(Spider.getLibs()[libName], date, config!!.retry)
        area = lib[subLibName]
    }
    //获取子区域信息
    val subLib = Spider.getAreas(area, date, config!!.retry)
    //val subLib = Spider.getAreas(AreaBean(208, "主楼(3-12)", 0, 0), date, config!!.retry)
    /*
        AreaBean(id=209, name=图东区(3-4), unusedSeats=7, allSeats=110, periods=null)
        AreaBean(id=210, name=电子阅览室, unusedSeats=0, allSeats=0, periods=null)
        AreaBean(id=208, name=主楼(3-12), unusedSeats=7, allSeats=1130, periods=null)
    */
    if (subLib.values.isNotEmpty()) {
        val curPeriods = subLib.values.first().periods
        val getSeatTasks = mutableListOf<Future<*>>()
        if (curPeriods.isNullOrEmpty()) {
            logger.warn { "未获取到可预约时间段，将无法进行预约" }
            getSeatTasks.add(threadPool.submit { getSeats(subLib) })
        } else {
            curPeriods.forEachIndexed { i, p ->
                if (isInPeriod(p)) {
                    periods[i] = p
                    if (!success.containsKey(i)) success[i] = false
                    getSeatTasks.add(threadPool.submit {
                        getSeats(subLib, i, "${p.startTime}-${p.endTime}")
                    })
                }
            }
        }
        getSeatTasks.forEach {
            it.get()
        }
    }
}

fun getSeats(subLib: Map<String, AreaBean>, periodIndex: Int = 0, periodTime: String = "08:00-22:30") {
    var log = "\n-------------获取$date ${periodTime}时间段座位-------------\n"
    val curQuerySeats = mutableListOf<SeatBean>()
    val curAllSeats = mutableListOf<SeatBean>()
    config!!.seats!!.forEach { (k, v) ->
        if (subLib.keys.contains(k)) {
            //获取座位信息
            val curSeats = Spider.getSeats(subLib[k], date, periodIndex, config!!.retry)
            curAllSeats.addAll(curSeats.values)
            v.forEach {
                if (curSeats.containsKey(it)) {
                    curQuerySeats.add(curSeats[it]!!)
                } else {
                    log += "无法查找到座位[$k-$it]，请检查提供的区域信息\n"
                }
            }
        } else {
            log += "无法查找到区域[$k]，请检查提供的座位信息\n"
        }
    }
    querySeats[periodIndex] = curQuerySeats
    allSeats[periodIndex] = curAllSeats
    if (allSeats[periodIndex].isNullOrEmpty()) {
        throw SpiderException("获取${periodTime}时间段座位：未查找到任何预设区域，请检查提供的区域信息")
    }
    if (!querySeats[periodIndex].isNullOrEmpty()) {
        var seatsInfo = "["
        querySeats[periodIndex]!!.forEach {
            seatsInfo += "${it.area.name}-${it.name},"
        }
        seatsInfo = seatsInfo.substring(0, seatsInfo.length - 1)
        seatsInfo += "]"
        log += "成功获取到${querySeats[periodIndex]!!.size}个预设座位信息：\n${seatsInfo}"
    } else {
        log += "未获取到预设座位信息，将预约预设区域的空闲座位"
    }
    logger.info { log }
}

fun isInPeriod(periodBean: PeriodBean): Boolean {
    val start = config!!.period!!.substringBefore("-")
    val end = config!!.period!!.substringAfter("-")
    val left = if (start < periodBean.startTime) periodBean.startTime else start
    val right = if (end > periodBean.endTime) periodBean.endTime else end
    return left < right
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
 * 提前登录功能
 * @param bookTime 预约的时间点
 */
fun startEarlyLogin(bookTime: Date) {
    logger.info { "开始提前登录尝试，将尝试${config!!.maxLoginAttempts}次登录，直到预约时间" }
    
    var attemptCount = 0
    var loginSuccess = false
    val loginErrors = mutableListOf<String>()
    
    while (attemptCount < config!!.maxLoginAttempts && System.currentTimeMillis() < bookTime.time) {
        if (loginSuccess) break

        attemptCount++
        try {
            logger.info { "登录尝试 $attemptCount/${config!!.maxLoginAttempts}" }
            // 清除之前的cookie，强制重新登录
            cookieCathe.clear()
            auth = createAuth()
            auth!!.login()

            // 提前获取座位信息
            getAllSeats()
            needReLogin = false

            // 如果登录成功，记录日志并设置标志位
            logger.info { "提前登录成功，已准备好进行预约" }
            loginSuccess = true
        } catch (e: Exception) {
            val errorMsg = "提前登录尝试失败: ${e.message}"
            logger.error { errorMsg }
            loginErrors.add("尝试 $attemptCount: $errorMsg")
        }
    }
    
    // 最终状态报告
    if (loginSuccess) {
        // 根据配置的时间格式创建对应的显示格式
        val displayFormat = createDisplayFormat()
        val bookTimeStr = displayFormat.format(bookTime)
        logger.info { "提前登录任务完成，登录状态正常，等待预约时间：$bookTimeStr" }
    } else {
        val warningMsg = "提前登录任务完成，但登录状态异常或已过期，将在预约时重新尝试"
        logger.warn { warningMsg }
        
        // 发送登录失败邮件通知
        config!!.emailNotification?.let { emailConfig ->
            // 根据配置的时间格式创建对应的显示格式
            val displayFormat = createDisplayFormat()

            val subject = "图书馆座位预约系统登录失败通知"
            val content = """
                |提前登录失败！
                |尝试次数：$attemptCount/${config!!.maxLoginAttempts}
                |预约时间：${displayFormat.format(bookTime)}
                |
                |失败详情：
                |${loginErrors.joinToString("\n|")}
                |
                |系统将在预约时间重新尝试登录。
            """.trimMargin()

            sduseat.utils.EmailUtils.sendEmail(emailConfig, subject, content)
        }
    }
}