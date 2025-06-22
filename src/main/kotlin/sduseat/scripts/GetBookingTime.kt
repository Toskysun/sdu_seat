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

import sduseat.api.Lib
import sduseat.bean.Config
import sduseat.utils.TimeParser
import sduseat.createAuth
import mu.KotlinLogging
import java.text.SimpleDateFormat
import java.util.*

private val logger = KotlinLogging.logger {}

/**
 * 获取预约时间脚本
 * 用于从服务器获取并解析预约开始时间
 */
object GetBookingTime {
    
    @JvmStatic
    fun main(args: Array<String>) {
        println("=== 图书馆座位预约系统 - 预约时间获取脚本 ===")
        println()
        
        try {
            // 初始化配置
            if (args.isNotEmpty()) {
                Config.initConfig(args)
            } else {
                println("使用默认配置文件...")
                Config.initConfig(arrayOf())
            }
            
            // 显示当前时间
            val currentTime = Date()
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            println("当前时间: ${timeFormat.format(currentTime)}")
            println()
            
            // 方式1: 从最后的响应消息中解析
            println("方式1: 从最后的响应消息中解析时间")
            println("-".repeat(40))
            
            val lastMessage = Lib.lastResponseMessage
            if (lastMessage != null) {
                println("最后的服务器响应: $lastMessage")
                val parseResult = Lib.parseBookingTimeFromMessage(lastMessage)
                displayParseResult(parseResult)
            } else {
                println("暂无服务器响应消息")
            }
            
            println()
            
            // 方式2: 通过认证获取时间信息
            println("方式2: 通过认证获取时间信息")
            println("-".repeat(40))
            
            if (sduseat.config != null) {
                try {
                    val auth = createAuth()
                    val timeResult = Lib.getBookingStartTime(auth)
                    displayParseResult(timeResult)
                } catch (e: Exception) {
                    println("获取时间信息失败: ${e.message}")
                    logger.error(e) { "获取时间信息时发生异常" }
                }
            } else {
                println("配置未初始化，无法创建认证实例")
            }
            
            println()
            
            // 方式3: 手动输入消息进行解析
            if (args.contains("--interactive") || args.contains("-i")) {
                println("方式3: 手动输入消息进行解析")
                println("-".repeat(40))
                interactiveMode()
            }

            // 显示帮助信息
            if (args.contains("--help") || args.contains("-h")) {
                showHelp()
            }
            
            // 显示使用建议
            println("\n=== 使用建议 ===")
            println("1. 确保配置文件正确设置了用户名和密码")
            println("2. 运行主程序后，可以通过 Lib.lastResponseMessage 获取最新的服务器响应")
            println("3. 使用 --interactive 或 -i 参数进入交互模式")
            println("4. 解析到的时间可以用于调整预约策略")
            
        } catch (e: Exception) {
            println("脚本执行失败: ${e.message}")
            logger.error(e) { "脚本执行时发生异常" }
        }
    }
    
    /**
     * 显示解析结果
     */
    private fun displayParseResult(result: TimeParser.ParseResult) {
        if (result.success) {
            println("✓ 解析成功!")
            println("  预约开始时间: ${result.time}")
            
            result.time?.let { timeStr ->
                // 显示更多时间信息
                val todayDate = TimeParser.parseTimeToDate(timeStr)
                val tomorrowDate = TimeParser.parseTimeToTomorrowDate(timeStr)
                val currentTimeStr = TimeParser.getCurrentTimeString()
                val isAfter = TimeParser.isCurrentTimeAfter(timeStr)
                
                println("  今天的预约时间: $todayDate")
                println("  明天的预约时间: $tomorrowDate")
                println("  当前时间: $currentTimeStr")
                println("  当前时间是否已过预约时间: ${if (isAfter) "是" else "否"}")
                
                if (!isAfter) {
                    val comparison = TimeParser.compareTime(currentTimeStr, timeStr)
                    if (comparison < 0) {
                        println("  状态: 等待预约开始")
                    }
                } else {
                    println("  状态: 可以开始预约")
                }
            }
        } else {
            println("✗ 解析失败: ${result.message}")
        }
    }
    
    /**
     * 交互模式
     */
    private fun interactiveMode() {
        println("请输入服务器响应消息 (输入 'quit' 退出):")
        
        while (true) {
            print("> ")
            val input = readLine()?.trim()
            
            if (input.isNullOrEmpty()) {
                continue
            }
            
            if (input.lowercase() == "quit" || input.lowercase() == "exit") {
                println("退出交互模式")
                break
            }
            
            val result = TimeParser.parseBookingStartTime(input)
            displayParseResult(result)
            println()
        }
    }
    
    /**
     * 显示帮助信息
     */
    private fun showHelp() {
        println("""
            用法: GetBookingTime [选项] [配置文件路径]
            
            选项:
              --interactive, -i    进入交互模式，手动输入消息进行解析
              --help, -h          显示此帮助信息
            
            示例:
              GetBookingTime                           # 使用默认配置
              GetBookingTime config.json               # 使用指定配置文件
              GetBookingTime --interactive             # 进入交互模式
        """.trimIndent())
    }
}
