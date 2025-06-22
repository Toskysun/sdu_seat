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

package sduseat.scripts

import sduseat.utils.TimeParser
import sduseat.utils.parseBookingStartTime

/**
 * 时间解析测试脚本
 * 用于测试从服务器响应中解析预约时间的功能
 */
object TimeParserTest {
    
    @JvmStatic
    fun main(args: Array<String>) {
        println("=== 图书馆座位预约系统 - 时间解析测试脚本 ===")
        println()
        
        // 测试用例
        val testMessages = listOf(
            "最新一天的系统开始预约时间为：12:30",
            "最新一天的系统开始预约时间为：06:00",
            "系统开始预约时间：08:30",
            "开始预约时间为：14:15",
            "预约时间：22:00",
            "今日预约已开始，开始时间12:30",
            "预约失败，系统将在12:30开始接受预约",
            "无效消息，没有时间信息",
            "",
            null
        )
        
        println("测试不同格式的服务器响应消息：")
        println("=".repeat(50))
        
        testMessages.forEachIndexed { index, message ->
            println("测试用例 ${index + 1}:")
            println("输入消息: ${message ?: "null"}")
            
            val result = TimeParser.parseBookingStartTime(message)
            
            println("解析结果: ${if (result.success) "成功" else "失败"}")
            if (result.success) {
                println("解析时间: ${result.time}")
                
                // 测试时间转换
                result.time?.let { timeStr ->
                    val todayDate = TimeParser.parseTimeToDate(timeStr)
                    val tomorrowDate = TimeParser.parseTimeToTomorrowDate(timeStr)
                    println("今天日期: $todayDate")
                    println("明天日期: $tomorrowDate")
                }
            } else {
                println("错误信息: ${result.message}")
            }
            println("-".repeat(30))
        }
        
        // 测试时间比较功能
        println("\n测试时间比较功能：")
        println("=".repeat(50))
        
        val currentTime = TimeParser.getCurrentTimeString()
        println("当前时间: $currentTime")
        
        val testTimes = listOf("06:00", "12:30", "18:00", "23:59")
        testTimes.forEach { testTime ->
            val comparison = TimeParser.compareTime(currentTime, testTime)
            val isAfter = TimeParser.isCurrentTimeAfter(testTime)
            println("$currentTime vs $testTime: ${when {
                comparison < 0 -> "早于"
                comparison > 0 -> "晚于"
                else -> "等于"
            }} (当前时间是否已过: $isAfter)")
        }
        
        // 测试字符串扩展函数
        println("\n测试字符串扩展函数：")
        println("=".repeat(50))
        
        val extTestMessages = listOf(
            "最新一天的系统开始预约时间为：12:30",
            "系统开始预约时间：08:30",
            "预约时间：22:00"
        )
        
        extTestMessages.forEach { message ->
            val extractedTime = message.parseBookingStartTime()
            println("消息: $message")
            println("提取时间: $extractedTime")
            println("-".repeat(20))
        }

        // 演示实际使用
        demonstrateUsage()

        println("\n=== 测试完成 ===")
    }

    /**
     * 演示如何在实际应用中使用时间解析功能
     */
    private fun demonstrateUsage() {
        println("\n=== 实际使用示例 ===")

        // 模拟服务器响应
        val serverResponse = "最新一天的系统开始预约时间为：12:30"

        // 解析时间
        val parseResult = TimeParser.parseBookingStartTime(serverResponse)

        if (parseResult.success) {
            val bookingTime = parseResult.time!!
            println("解析到预约开始时间: $bookingTime")

            // 检查当前时间是否已经超过预约时间
            if (TimeParser.isCurrentTimeAfter(bookingTime)) {
                println("当前时间已超过预约开始时间，可以开始预约")
            } else {
                println("当前时间未到预约开始时间，需要等待")
            }

            // 转换为具体的日期时间
            val tomorrowBookingTime = TimeParser.parseTimeToTomorrowDate(bookingTime)
            println("明天的预约时间: $tomorrowBookingTime")

        } else {
            println("解析失败: ${parseResult.message}")
        }
    }
}


