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
import sduseat.bean.SeatBean
import sduseat.constant.Const.LIB_URL
import mu.KotlinLogging
import sduseat.http.getProxyClient
import sduseat.http.newCallResponseBody
import sduseat.http.postForm
import sduseat.http.text
import sduseat.utils.GSON
import sduseat.utils.parseString
import java.net.SocketTimeoutException

private val logger = KotlinLogging.logger {}

object Lib {
    /**
     * 存储最后一次请求的响应消息
     */
    var lastResponseMessage: String? = null
    
    /**
     * 预约座位
     * @return 0：失败，1：成功，2：重新登录，3：预约已停止|未开始预约
     */
    fun book(seat: SeatBean?, date: String, auth: IAuth, periodIndex: Int = 0, retry: Int = 0): Int {
        //synchronized(auth) {
        if (seat == null) throw LibException("无法查找到对应的座位，请检查提供的座位信息")
        val area = seat.area
        val period = area.periods!![periodIndex]
        val bookUrl = "$LIB_URL/api.php/spaces/${seat.id}/book"
        var json: JsonObject? = null
        for (i in 0..retry) {
            val res = try {
                getProxyClient().newCallResponseBody(retry) {
                    url(bookUrl)
                    postForm(HashMap<String, Any>().apply {
                        put("access_token", auth.access_token)
                        put("userid", auth.userid)
                        put("segment", period.id) // bookTimeId
                        put("type", 1)
                        put("operateChannel", 2)
                    })
                    header(
                        "Referer",
                        "$LIB_URL/web/seat3?area=${area.id}&segment=${period.id}&day=$date&startTime=${period.startTime}&endTime=${period.endTime}"
                    )
                }.text()
            } catch (e: SocketTimeoutException) {
                logger.error() { "预约座位失败：网络请求超时，正在重试" }
                continue
            } catch (e: Exception) {
                logger.error(e) { "预约座位失败：网络请求出错，正在重试" }
                continue
            }
            try {
                json = GSON.parseString(res).asJsonObject
            } catch (e: Exception) {
                continue
            }
            if (!json.get("msg").asString.contains("预约超时"))
                break
        }
        val status = json!!.get("status").asInt
        val msg = json.get("msg").asString
        lastResponseMessage = msg  // 存储最后的响应消息
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
        } else if (msg.contains("重新登录")) {
            2
        } else if (msg.contains("预约已停止|开始预约时间".toRegex())) {
            3
        } else {
            0
        }
        return result
        //}
    }

    /**
     * 取消选座
     */
    fun cancelBook(bookBean: IBookBean, auth: IAuth): Boolean {
        val res = getProxyClient().newCallResponseBody {
            url("$LIB_URL/api.php/profile/books/${bookBean.id}")
            postForm(
                mapOf(
                    Pair("_method", "delete"),
                    Pair("id", bookBean.id),
                    Pair("userid", auth.userid),
                    Pair("access_token", auth.access_token),
                    Pair("operateChannel", 2)
                )
            )
            header(
                "Referer",
                "$LIB_URL/user/index/book"
            )
        }.text()
        val json = GSON.parseString(res).asJsonObject
        val status = json.get("status").asInt
        val msg = json.get("msg").asString
        logger.info { "取消座位[${bookBean.title}]返回信息: $msg" }
        return status == 1
    }
}
