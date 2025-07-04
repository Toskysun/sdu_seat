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

package sduseat.api

import com.google.gson.JsonObject
import sduseat.LibException
import sduseat.bean.IBookBean
import sduseat.bean.PeriodBean
import sduseat.bean.SeatBean
import sduseat.constant.Const.LIB_URL
import io.github.oshai.kotlinlogging.KotlinLogging as KLogger
import sduseat.http.getProxyClient
import sduseat.http.newCallResponseText
import sduseat.http.postForm
import sduseat.utils.GSON
import sduseat.utils.parseString
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private val logger = KLogger.logger {}

object Lib {
    /**
     * 存储最后一次请求的响应消息
     */
    var lastResponseMessage: String? = null

    /**
     * 保活机制的调度器
     */
    private var keepAliveScheduler: ScheduledExecutorService? = null

    /**
     * 当前的认证对象
     */
    private var currentAuth: IAuth? = null

    /**
     * 验证会话是否有效
     * @return true: 会话有效, false: 会话无效需要重新登录
     */
    fun validateSession(auth: IAuth): Boolean {
        return try {
            val res = getProxyClient().newCallResponseText(1) {
                url("$LIB_URL/api.php/profile")
                postForm(mapOf(
                    "access_token" to auth.accessToken,
                    "userid" to auth.userid
                ))
                header("Referer", "$LIB_URL/")
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "zh-CN,zh;q=0.9")
            }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt
            val msg = json.get("msg")?.asString ?: ""

            if (status == 1) {
                logger.debug { "会话验证成功" }
                true
            } else {
                logger.warn { "会话验证失败: $msg" }
                // 检查是否是认证相关错误
                msg.contains("重新登录") || msg.contains("access_token") ||
                msg.contains("登录") || msg.contains("认证") ||
                msg.contains("过期") || msg.contains("无效") || msg.contains("token")
                false
            }
        } catch (e: Exception) {
            logger.warn(e) { "会话验证请求失败" }
            false
        }
    }

    /**
     * 启动会话保活机制
     * @param auth 认证对象
     * @param intervalSeconds 保活间隔（秒），默认30秒
     */
    fun startKeepAlive(auth: IAuth, intervalSeconds: Long = 30) {
        // 停止之前的保活任务
        stopKeepAlive()

        currentAuth = auth
        keepAliveScheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "KeepAlive-Thread").apply {
                isDaemon = true
            }
        }

        logger.info { "启动会话保活机制，间隔: ${intervalSeconds}秒" }

        keepAliveScheduler?.scheduleAtFixedRate({
            try {
                performKeepAlive()
            } catch (e: Exception) {
                logger.warn(e) { "保活请求失败" }
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS)
    }

    /**
     * 停止会话保活机制
     */
    fun stopKeepAlive() {
        keepAliveScheduler?.let { scheduler ->
            logger.debug { "停止会话保活机制" }
            scheduler.shutdown()
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow()
                }
            } catch (e: InterruptedException) {
                scheduler.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        keepAliveScheduler = null
        currentAuth = null
    }

    /**
     * 保活策略计数器
     */
    private var keepAliveCounter = 0

    /**
     * 执行保活请求
     */
    private fun performKeepAlive() {
        val auth = currentAuth ?: return

        try {
            // 使用更激进的保活策略：模拟真实的座位查询操作
            val success = when (keepAliveCounter % 3) {
                0 -> performRealSeatQueryKeepAlive(auth)
                1 -> performRealAreaQueryKeepAlive(auth)
                else -> performRealBookingQueryKeepAlive(auth)
            }

            keepAliveCounter++

            if (success) {
                logger.info { "[保活] 会话保活成功 (真实操作策略${(keepAliveCounter - 1) % 3 + 1})" }
            }
        } catch (e: Exception) {
            logger.debug(e) { "[保活] 保活请求异常" }
        }
    }

    /**
     * 策略1：获取图书馆列表保活
     */
    private fun performLibraryListKeepAlive(auth: IAuth): Boolean {
        return try {
            val today = java.time.LocalDate.now().toString()
            val res = getProxyClient().newCallResponseText(1) {
                url("$LIB_URL/api.php/v3areas?date=$today")
                header("Referer", "$LIB_URL/")
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "zh-CN,zh;q=0.9")
            }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt
            status == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 策略2：获取当前使用状态保活
     */
    private fun performCurrentUseKeepAlive(auth: IAuth): Boolean {
        return try {
            val res = getProxyClient().newCallResponseText(1) {
                url("$LIB_URL/api.php/currentuse?user=${auth.userid}")
                header("Referer", "$LIB_URL/")
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "zh-CN,zh;q=0.9")
            }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt
            val msg = json.get("msg")?.asString ?: ""

            if (status != 1 && (msg.contains("重新登录") || msg.contains("access_token") ||
                msg.contains("登录") || msg.contains("认证") ||
                msg.contains("过期") || msg.contains("无效") || msg.contains("token"))) {
                logger.warn { "[保活] 检测到会话已失效，停止保活机制" }
                stopKeepAlive()
                return false
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 策略3：获取用户资料保活
     */
    private fun performProfileKeepAlive(auth: IAuth): Boolean {
        return try {
            val res = getProxyClient().newCallResponseText(1) {
                url("$LIB_URL/api.php/profile")
                postForm(mapOf(
                    "access_token" to auth.accessToken,
                    "userid" to auth.userid
                ))
                header("Referer", "$LIB_URL/")
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "zh-CN,zh;q=0.9")
            }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt
            val msg = json.get("msg")?.asString ?: ""

            if (status != 1) {
                logger.warn { "[保活] Profile保活失败: $msg" }
                // 检查是否是认证相关错误
                if (msg.contains("重新登录") || msg.contains("access_token") ||
                    msg.contains("登录") || msg.contains("认证") ||
                    msg.contains("过期") || msg.contains("无效") || msg.contains("token")) {
                    logger.warn { "[保活] 检测到会话已失效，停止保活机制" }
                    stopKeepAlive()
                    return false
                }
            }

            status == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 策略4：获取活动信息保活
     */
    private fun performActivityInfoKeepAlive(auth: IAuth): Boolean {
        return try {
            val res = getProxyClient().newCallResponseText(1) {
                url("$LIB_URL/api.php/activityinfo")
                header("Referer", "$LIB_URL/")
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "zh-CN,zh;q=0.9")
            }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt
            status == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 策略5：获取区域时间段保活
     */
    private fun performAreaDaysKeepAlive(auth: IAuth): Boolean {
        return try {
            val res = getProxyClient().newCallResponseText(1) {
                url("$LIB_URL/api.php/areadays/1")
                header("Referer", "$LIB_URL/")
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "zh-CN,zh;q=0.9")
            }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt
            status == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 策略6：获取预约声明保活
     */
    private fun performBookDeclareKeepAlive(auth: IAuth): Boolean {
        return try {
            val res = getProxyClient().newCallResponseText(1) {
                url("$LIB_URL/api.php/bookdeclare/zh")
                header("Referer", "$LIB_URL/")
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "zh-CN,zh;q=0.9")
            }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt
            status == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 策略7：获取通知信息保活
     */
    private fun performNoticeInfoKeepAlive(auth: IAuth): Boolean {
        return try {
            val res = getProxyClient().newCallResponseText(1) {
                url("$LIB_URL/api.php/noticeinfo")
                header("Referer", "$LIB_URL/")
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "zh-CN,zh;q=0.9")
            }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt
            status == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 策略8：获取用户预约记录保活
     */
    private fun performProfileBooksKeepAlive(auth: IAuth): Boolean {
        return try {
            val res = getProxyClient().newCallResponseText(1) {
                url("$LIB_URL/api.php/profile/books?access_token=${auth.accessToken}&userid=${auth.userid}&count=1")
                header("Referer", "$LIB_URL/")
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "zh-CN,zh;q=0.9")
            }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt
            val msg = json.get("msg")?.asString ?: ""

            if (status != 1 && (msg.contains("重新登录") || msg.contains("access_token") ||
                msg.contains("登录") || msg.contains("认证") ||
                msg.contains("过期") || msg.contains("无效") || msg.contains("token"))) {
                logger.warn { "[保活] 检测到会话已失效，停止保活机制" }
                stopKeepAlive()
                return false
            }

            status == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 策略9：获取我的预约按钮状态保活
     */
    private fun performMyYuyueButtonKeepAlive(auth: IAuth): Boolean {
        return try {
            val res = getProxyClient().newCallResponseText(1) {
                url("$LIB_URL/api.php/v3myyuyuebutton")
                header("Referer", "$LIB_URL/")
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "zh-CN,zh;q=0.9")
            }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt
            status == 1
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 预约座位
     * @return 0：失败，1：成功，2：重新登录，3：预约已停止|未开始预约
     */
    fun book(seat: SeatBean?, date: String, auth: IAuth, periodIndex: Int = 0, retry: Int = 0): Int {
        if (seat == null) throw LibException("无法查找到对应的座位，请检查提供的座位信息")
        val area = seat.area

        // 检查periods是否为空
        val periods = area.periods
        if (periods.isNullOrEmpty()) {
            throw LibException("座位区域 ${area.name} 没有时间段信息")
        }

        // 检查periodIndex是否有效
        if (periodIndex >= periods.size) {
            throw LibException("时间段索引 $periodIndex 超出范围，区域 ${area.name} 只有 ${periods.size} 个时间段")
        }

        // 预约前验证会话状态
        if (!validateSession(auth)) {
            logger.warn { "会话验证失败，需要重新认证" }
            lastResponseMessage = "会话已失效，需要重新认证"
            return 2
        }

        val period = periods[periodIndex]
        val bookUrl = "$LIB_URL/api.php/spaces/${seat.id}/book"
        var json: JsonObject? = null
        for (i in 0..retry) {
            val res = try {
                getProxyClient().newCallResponseText(retry) {
                    url(bookUrl)
                    postForm(mapOf(
                        "access_token" to auth.accessToken,
                        "userid" to auth.userid,
                        "segment" to period.id,
                        "type" to 1,
                        "operateChannel" to 2
                    ))
                    header(
                        "Referer",
                        "$LIB_URL/web/seat3?area=${area.id}&segment=${period.id}&day=$date&startTime=${period.startTime}&endTime=${period.endTime}"
                    )
                    header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
                    header("Accept", "application/json, text/plain, */*")
                    header("Accept-Language", "zh-CN,zh;q=0.9")
                    header("X-Requested-With", "XMLHttpRequest")
                }
            } catch (_: SocketTimeoutException) {
                logger.error { "预约座位失败：网络请求超时，正在重试" }
                continue
            } catch (e: Exception) {
                logger.error(e) { "预约座位失败：网络请求出错，正在重试" }
                continue
            }
            try {
                json = GSON.parseString(res).asJsonObject
            } catch (_: Exception) {
                continue
            }
            if (!json.get("msg").asString.contains("预约超时"))
                break
        }
        val finalJson = json ?: throw LibException("预约失败：无法解析服务器响应")
        val status = finalJson.get("status").asInt
        val msg = finalJson.get("msg").asString
        lastResponseMessage = msg
        logger.info { "预约$date ${period.startTime}-${period.endTime}时间段座位[${area.name}-${seat.name}]返回信息: $msg" }
        if (status == 1) {
            logger.info {
                "座位[${area.name}-${seat.name}]预约成功，" +
                        "用户：${auth.name}-${auth.userid}，" +
                        "时间：$date ${period.startTime}-${period.endTime}"
            }
        }

        // 检查是否访问频繁
        if (msg.contains("访问频繁")) {
            throw LibException("访问频繁！$msg")
        }

        val result = if (status == 1 || msg.contains("不可重复预约")) {
            1
        } else if (msg.contains("重新登录") || msg.contains("access_token") ||
                  msg.contains("登录") || msg.contains("认证") ||
                  msg.contains("过期") || msg.contains("无效") || msg.contains("token")) {
            logger.warn { "检测到认证相关错误: $msg" }
            2
        } else if (msg.contains("预约已停止|开始预约时间".toRegex())) {
            3
        } else {
            0
        }
        return result
    }

    /**
     * 取消选座
     */
    fun cancelBook(bookBean: IBookBean, auth: IAuth): Boolean {
        val res = getProxyClient().newCallResponseText {
            url("$LIB_URL/api.php/profile/books/${bookBean.id}")
            postForm(
                mapOf(
                    "_method" to "delete",
                    "id" to bookBean.id,
                    "userid" to auth.userid,
                    "access_token" to auth.accessToken,
                    "operateChannel" to 2
                )
            )
            header(
                "Referer",
                "$LIB_URL/user/index/book"
            )
        }
        val json = GSON.parseString(res).asJsonObject
        val status = json.get("status").asInt
        val msg = json.get("msg").asString
        logger.info { "取消座位[${bookBean.title}]返回信息: $msg" }
        return status == 1
    }

    /**
     * 真实操作策略1：查询座位信息保活
     */
    private fun performRealSeatQueryKeepAlive(auth: IAuth): Boolean {
        return try {
            val today = java.time.LocalDate.now().toString()
            val res = getProxyClient().newCallResponseText(1) {
                url("$LIB_URL/api.php/spaces_old?area=202&day=$today&startTime=08:00&endTime=22:30")
                header("Referer", "$LIB_URL/web/seat3?area=202&day=$today")
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "zh-CN,zh;q=0.9")
                header("X-Requested-With", "XMLHttpRequest")
                header("Sec-Fetch-Dest", "empty")
                header("Sec-Fetch-Mode", "cors")
                header("Sec-Fetch-Site", "same-origin")
            }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt
            val msg = json.get("msg")?.asString ?: ""

            if (status != 1 && (msg.contains("重新登录") || msg.contains("access_token") ||
                msg.contains("登录") || msg.contains("认证") ||
                msg.contains("过期") || msg.contains("无效") || msg.contains("token"))) {
                logger.warn { "[保活] 检测到会话已失效，停止保活机制" }
                stopKeepAlive()
                return false
            }

            status == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 真实操作策略2：查询区域信息保活
     */
    private fun performRealAreaQueryKeepAlive(auth: IAuth): Boolean {
        return try {
            val today = java.time.LocalDate.now().toString()
            val res = getProxyClient().newCallResponseText(1) {
                url("$LIB_URL/api.php/v3areas?date=$today")
                header("Referer", "$LIB_URL/")
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "zh-CN,zh;q=0.9")
                header("X-Requested-With", "XMLHttpRequest")
                header("Sec-Fetch-Dest", "empty")
                header("Sec-Fetch-Mode", "cors")
                header("Sec-Fetch-Site", "same-origin")
                header("Cache-Control", "no-cache")
                header("Pragma", "no-cache")
            }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt
            status == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 真实操作策略3：查询预约状态保活
     */
    private fun performRealBookingQueryKeepAlive(auth: IAuth): Boolean {
        return try {
            val res = getProxyClient().newCallResponseText(1) {
                url("$LIB_URL/api.php/profile/books")
                postForm(mapOf(
                    "access_token" to auth.accessToken,
                    "userid" to auth.userid,
                    "count" to "5",
                    "page" to "1"
                ))
                header("Referer", "$LIB_URL/user/index/book")
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "zh-CN,zh;q=0.9")
                header("X-Requested-With", "XMLHttpRequest")
                header("Content-Type", "application/x-www-form-urlencoded")
                header("Origin", "$LIB_URL")
                header("Sec-Fetch-Dest", "empty")
                header("Sec-Fetch-Mode", "cors")
                header("Sec-Fetch-Site", "same-origin")
            }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt
            val msg = json.get("msg")?.asString ?: ""

            if (status != 1 && (msg.contains("重新登录") || msg.contains("access_token") ||
                msg.contains("登录") || msg.contains("认证") ||
                msg.contains("过期") || msg.contains("无效") || msg.contains("token"))) {
                logger.warn { "[保活] 检测到会话已失效，停止保活机制" }
                stopKeepAlive()
                return false
            }

            status == 1
        } catch (e: Exception) {
            false
        }
    }
}
