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

import mu.KotlinLogging
import java.text.SimpleDateFormat
import java.util.*

private val logger = KotlinLogging.logger {}

/**
 * 时间解析工具类
 * 专门用于解析服务器返回的预约时间信息
 */
object TimeParser {
    
    /**
     * 从服务器响应消息中解析预约开始时间
     * @param serverMessage 服务器返回的消息
     * @return 解析结果，包含是否成功和时间信息
     */
    fun parseBookingStartTime(serverMessage: String?): ParseResult {
        if (serverMessage.isNullOrBlank()) {
            return ParseResult(false, null, "服务器消息为空")
        }
        
        logger.info { "开始解析服务器消息：$serverMessage" }
        
        // 使用扩展函数进行解析
        val timeStr = serverMessage.parseBookingStartTime()
        
        if (timeStr != null) {
            logger.info { "成功解析到预约开始时间：$timeStr" }
            return ParseResult(true, timeStr, "解析成功")
        } else {
            logger.warn { "未能从服务器消息中解析到有效的时间信息" }
            return ParseResult(false, null, "未找到有效的时间格式")
        }
    }
    
    /**
     * 将时间字符串转换为今天的Date对象
     * @param timeStr 时间字符串，格式为 HH:mm
     * @return Date对象，如果解析失败则返回null
     */
    fun parseTimeToDate(timeStr: String): Date? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm")
            val today = SimpleDateFormat("yyyy-MM-dd").format(Date())
            sdf.parse("$today $timeStr")
        } catch (e: Exception) {
            logger.error(e) { "时间字符串解析失败：$timeStr" }
            null
        }
    }
    
    /**
     * 将时间字符串转换为明天的Date对象
     * @param timeStr 时间字符串，格式为 HH:mm
     * @return Date对象，如果解析失败则返回null
     */
    fun parseTimeToTomorrowDate(timeStr: String): Date? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm")
            val tomorrow = SimpleDateFormat("yyyy-MM-dd").format(Date(System.currentTimeMillis() + 86400000L))
            sdf.parse("$tomorrow $timeStr")
        } catch (e: Exception) {
            logger.error(e) { "时间字符串解析失败：$timeStr" }
            null
        }
    }
    
    /**
     * 获取当前系统时间的字符串表示
     * @return 当前时间字符串，格式为 HH:mm
     */
    fun getCurrentTimeString(): String {
        return SimpleDateFormat("HH:mm").format(Date())
    }
    
    /**
     * 比较两个时间字符串
     * @param time1 时间1，格式为 HH:mm
     * @param time2 时间2，格式为 HH:mm
     * @return 负数表示time1早于time2，0表示相等，正数表示time1晚于time2
     */
    fun compareTime(time1: String, time2: String): Int {
        val date1 = parseTimeToDate(time1)
        val date2 = parseTimeToDate(time2)
        
        return when {
            date1 == null && date2 == null -> 0
            date1 == null -> -1
            date2 == null -> 1
            else -> date1.compareTo(date2)
        }
    }
    
    /**
     * 检查当前时间是否已经超过指定时间
     * @param targetTime 目标时间，格式为 HH:mm
     * @return true表示当前时间已超过目标时间
     */
    fun isCurrentTimeAfter(targetTime: String): Boolean {
        val currentTime = getCurrentTimeString()
        return compareTime(currentTime, targetTime) > 0
    }
    
    /**
     * 解析结果数据类
     */
    data class ParseResult(
        val success: Boolean,
        val time: String?,
        val message: String
    )
}
