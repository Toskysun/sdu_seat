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

 /**
 * 取两个文本之间的文本值
 * @param left 文本前面
 * @param right 后面文本
 * @return 返回 String
 */
fun String.centerString(left: String?, right: String?): String {
    val startIndex = if (left.isNullOrEmpty()) {
        0
    } else {
        val leftIndex = indexOf(left)
        if (leftIndex > -1) leftIndex + left.length else 0
    }

    val endIndex = if (right.isNullOrEmpty()) {
        length
    } else {
        val rightIndex = indexOf(right, startIndex)
        if (rightIndex < 0) length else rightIndex
    }

    return substring(startIndex, endIndex)
}

/**
 * 从服务器响应消息中解析预约开始时间
 * 支持格式如："最新一天的系统开始预约时间为：12:30"
 * @return 解析到的时间字符串，格式为 HH:mm
 */
fun String.parseBookingStartTime(): String? {
    // 多种可能的匹配模式
    val patterns = listOf(
        // 匹配 "最新一天的系统开始预约时间为：12:30"
        "最新一天的系统开始预约时间为[：:](\\d{1,2}:\\d{2})".toRegex(),
        // 匹配 "系统开始预约时间：12:30"
        "系统开始预约时间[：:](\\d{1,2}:\\d{2})".toRegex(),
        // 匹配 "开始预约时间为：12:30"
        "开始预约时间为[：:](\\d{1,2}:\\d{2})".toRegex(),
        // 匹配 "预约时间：12:30"
        "预约时间[：:](\\d{1,2}:\\d{2})".toRegex(),
        // 通用时间格式匹配
        "([0-2]?[0-9]:[0-5][0-9])".toRegex()
    )

    for (pattern in patterns) {
        val matchResult = pattern.find(this)
        if (matchResult != null) {
            val timeStr = matchResult.groupValues[1]
            // 验证时间格式是否有效
            if (isValidTimeFormat(timeStr)) {
                return timeStr
            }
        }
    }

    return null
}

/**
 * 验证时间格式是否有效
 * @param timeStr 时间字符串，格式为 HH:mm
 * @return 是否为有效的时间格式
 */
private fun isValidTimeFormat(timeStr: String): Boolean {
    val parts = timeStr.split(":")
    if (parts.size != 2) return false

    val hour = parts[0].toIntOrNull() ?: return false
    val minute = parts[1].toIntOrNull() ?: return false

    return hour in 0..23 && minute in 0..59
}