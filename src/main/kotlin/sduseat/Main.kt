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
import sduseat.utils.JsUtils
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
        } catch (e: InterruptedException) {
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

val authRunnable = Runnable {
    auth = if (config!!.webVpn) AuthWebVpn(config!!.userid!!, config!!.passwd!!, config!!.deviceId!!, config!!.maxLoginAttempts)
    else Auth(config!!.userid!!, config!!.passwd!!, config!!.deviceId!!, config!!.maxLoginAttempts)
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
        } catch (ignored: Exception) {
        }
    }
    val sdf = SimpleDateFormat("yyyy-MM-dd " + config!!.time)
    var startTime = if (config!!.time!!.length == 8) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(sdf.format(Date()))
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm").parse(sdf.format(Date()))
    }
    // 如果已过当天设置时间，修改首次运行时间为明天
    if (System.currentTimeMillis() > startTime.time) {
        startTime = Date(startTime.time + ONE_DAY)
    }
    logger.info { "请等待到${sdf.format(startTime)}" }
    
    val earlyStartTime = Date(startTime.time - TimeUnit.MINUTES.toMillis(config!!.earlyLoginMinutes.toLong()))
    logger.info { "将在${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(earlyStartTime)}提前开始登录尝试" }
    
    val time = Timer()
    val earlyLoginTask = object : TimerTask() {
        override fun run() {
            startEarlyLogin(startTime)
        }
    }
    val bookTask = object : TimerTask() {
        override fun run() {
            startBook()
        }
    }
    
    // 提前登录任务
    time.schedule(earlyLoginTask, earlyStartTime)
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
          "time": "06:02",
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
                logger.error() { "登录失败：网络请求超时" }
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
            logger.error() { "获取座位信息失败：网络请求超时" }
        } else {
            logger.error(e) { }
        }
        throw e
    }
}

fun startBook() {
    date = dateFormat.format(System.currentTimeMillis() + ONE_DAY * config!!.delta)
    success.clear()
    for (i in 0..config!!.retry) {
        try {
            bookTask()
            break
        } catch (ignored: Exception) {
        }
        if (i < config!!.retry)
            logger.info { "尝试预约${i + 1}/${config!!.retry}失败，将在${config!!.retryInterval}秒后重试..." }
        Thread.sleep((config!!.retryInterval * 1000).toLong())
    }
}

fun bookTask() {
    loginAndGetSeats()
    val bookSeatTasks = mutableListOf<Future<*>>()
    periods.keys.forEach {
        if (!success[it]!!) {
            bookSeatTasks.add(threadPool.submit {
                val periodTime = "${periods[it]!!.startTime}-${periods[it]!!.endTime}"
                var curSuccess = bookSeat(querySeats[it]!!, it, needFilter = false)
                if (curSuccess) return@submit
                if (!config!!.only) {
                    logger.info { "预约${periodTime}时间段座位：预设座位均无法预约，将预约预设区域的空闲座位" }
                    curSuccess = bookSeat(allSeats[it]!!, it)
                }
                success[it] = curSuccess
                if (!curSuccess) {
                    val errorMsg = "预约${periodTime}时间段座位：所有座位均无法预约，预约失败"
                    // 发送预约失败邮件通知
                    config!!.emailNotification?.let { emailConfig ->
                        val subject = "图书馆座位预约失败通知"
                        val content = """
                            |预约失败！
                            |日期：$date
                            |时间段：$periodTime
                            |失败原因：所有座位均无法预约
                            |
                            |请尝试手动预约或联系管理员处理。
                        """.trimMargin()
                        
                        sduseat.utils.EmailUtils.sendEmail(emailConfig, subject, content)
                    }
                    throw LibException(errorMsg)
                }
            })
        }
    }
    var hasFailed = false
    val failureMessages = mutableListOf<String>()
    bookSeatTasks.forEach {
        try {
            it.get()
        } catch (e: Exception) {
            if (e.cause is LibException) {
                logger.error() { e.message }
                e.message?.let { msg -> failureMessages.add(msg) }
            } else {
                logger.error(e) { }
                failureMessages.add("发生异常: ${e.message ?: "未知错误"}")
            }
            hasFailed = true
        }
    }
    if (hasFailed) {
        // 如果存在多个时间段的失败，发送一个汇总邮件
        if (failureMessages.size > 1) {
            config!!.emailNotification?.let { emailConfig ->
                val subject = "图书馆座位预约失败汇总通知"
                val content = """
                    |预约失败汇总！
                    |日期：$date
                    |
                    |失败详情：
                    |${failureMessages.joinToString("\n|")}
                    |
                    |请尝试手动预约或联系管理员处理。
                """.trimMargin()
                
                sduseat.utils.EmailUtils.sendEmail(emailConfig, subject, content)
            }
        }
        throw LibException("")
    }
    clear()
}

fun bookSeat(
    seats: List<SeatBean>,
    periodIndex: Int = 0,
    periodTime: String = "08:00-22:30",
    needFilter: Boolean = true
): Boolean {
    var success = false
    var mySeats = seats
    if (needFilter && !config!!.filterRule.isNullOrEmpty()) {
        try {
            mySeats = JsUtils.filterSeats(config!!.filterRule!!, seats)
        } catch (e: Exception) {
            logger.error(e) { "过滤座位时出错，将使用原座位进行预约" }
        }
    }
    
    // 记录预约尝试的详细信息
    val attemptDetails = mutableListOf<String>()
    
    for (seat in mySeats) {
        if (seat.status == 1) {
            val res = Lib.book(seat, date, auth!!, periodIndex, config!!.retry)
            if (res == 3) {
                attemptDetails.add("座位 ${seat.area.name}-${seat.name} 当前状态无法预约")
                continue // 尝试下一个座位，而不是立即抛出异常
            } else if (res == 2) {
                needReLogin = true
                attemptDetails.add("座位 ${seat.area.name}-${seat.name} 需要重新登录")
            }
            success = res == 1
            
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
                return true
            }
        } else {
            attemptDetails.add("座位 ${seat.area.name}-${seat.name} 状态为 ${getSeatStatusDescription(seat.status)}")
        }
    }
    
    // 如果所有座位都尝试失败，发送详细的失败邮件
    if (!success && attemptDetails.isNotEmpty()) {
        config!!.emailNotification?.let { emailConfig ->
            val subject = "图书馆座位预约详细失败通知"
            val content = """
                |预约失败详细信息
                |日期：$date
                |时间段：$periodTime
                |
                |尝试详情：
                |${attemptDetails.joinToString("\n|")}
                |
                |总结：所有指定座位均无法预约。
                |请尝试手动预约或联系管理员处理。
            """.trimMargin()
            
            sduseat.utils.EmailUtils.sendEmail(emailConfig, subject, content)
        }
    }
    
    return false
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
    
    while (attemptCount < config!!.maxLoginAttempts && System.currentTimeMillis() < bookTime.time && !loginSuccess) {
        attemptCount++
        try {
            logger.info { "登录尝试 $attemptCount/${config!!.maxLoginAttempts}" }
            // 清除之前的cookie，强制重新登录
            cookieCathe.clear()
            auth = if (config!!.webVpn) AuthWebVpn(config!!.userid!!, config!!.passwd!!, config!!.deviceId!!, config!!.maxLoginAttempts)
            else Auth(config!!.userid!!, config!!.passwd!!, config!!.deviceId!!, config!!.maxLoginAttempts)
            auth!!.login()
            
            // 提前获取座位信息
            getAllSeats()
            needReLogin = false
            
            // 如果登录成功，记录日志并设置标志位
            logger.info { "提前登录成功，已准备好进行预约" }
            loginSuccess = true
            break // 登录成功后立即退出循环
        } catch (e: Exception) {
            val errorMsg = "提前登录尝试失败: ${e.message}"
            logger.error { errorMsg }
            loginErrors.add("尝试 $attemptCount: $errorMsg")
        }
    }
    
    // 最终状态报告
    if (loginSuccess) {
        val bookTimeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(bookTime)
        logger.info { "提前登录任务完成，登录状态正常，等待预约时间：$bookTimeStr" }
    } else {
        val warningMsg = "提前登录任务完成，但登录状态异常或已过期，将在预约时重新尝试"
        logger.warn { warningMsg }
        
        // 发送登录失败邮件通知
        config!!.emailNotification?.let { emailConfig ->
            val subject = "图书馆座位预约系统登录失败通知"
            val content = """
                |提前登录失败！
                |尝试次数：$attemptCount/${config!!.maxLoginAttempts}
                |预约时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(bookTime)}
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