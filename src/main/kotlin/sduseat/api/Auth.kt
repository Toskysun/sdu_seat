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
import io.github.oshai.kotlinlogging.KotlinLogging as KLogger
import okhttp3.Request
import sduseat.AuthException
import sduseat.bean.Config
import sduseat.config

import sduseat.constant.Const.WECHAT_USER_AGENT
import sduseat.constant.Const.WECHAT_LOGIN_URL
import sduseat.http.*
import sduseat.utils.GSON
import sduseat.utils.parseString
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val logger = KLogger.logger {}

/**
 * 微信OAuth认证实现 - 标准三次握手流程
 *
 * 微信认证流程（三次握手）：
 * 1. 用户同意授权，获取code
 * 2. 通过code换取网页授权access_token，用户openId等信息
 * 3. 通过access_token和用户的openId获取该用户的用户信息
 *
 * 参考：https://blog.csdn.net/Cike___/article/details/109354988
 */
class Auth(
    override var userid: String,
    password: String,
    deviceId: String,
    retry: Int = 0
) : IAuth(userid, password, deviceId, retry) {

    override val authUrl: String = "http://seatwx.lib.sdu.edu.cn/"

    private val host: String = "seatwx.lib.sdu.edu.cn"

    override fun login() {
        try {
            // 第一步：检查现有会话
            if (checkAndUseExistingSession()) {
                return
            }

            // 第二步：尝试从配置文件恢复会话
            if (tryRestoreSessionFromConfig()) {
                return
            }

            // 第三步：引导用户手动获取Cookie
            guideUserToGetNewSession()

        } catch (e: Exception) {
            // 如果是需要Cookie的异常，不显示错误日志
            if (e.message?.contains("需要微信登录Cookie") != true) {
                logger.error(e) { "微信认证失败" }
            }
            throw AuthException("微信认证失败：${e.message}")
        }
    }

    /**
     * 检查并使用现有会话
     */
    private fun checkAndUseExistingSession(): Boolean {
        val cookies = cookieCathe[host]
        if (cookies.isNullOrEmpty()) {
            logger.debug { "未找到现有Cookie" }
            return false
        }

        logger.debug { "Cookie数量: ${cookies.size}, 包含: ${cookies.keys.joinToString(", ")}" }

        // 检查关键Cookie是否存在
        val hasUserObj = cookies.containsKey("userObj")
        val hasUser = cookies.containsKey("user")

        if (!hasUserObj && !hasUser) {
            logger.debug { "缺少关键认证Cookie" }
            return false
        }

        // 提取会话信息（静默模式，避免重复日志）
        extractSessionFromCookies(verbose = false)

        // 验证会话是否有效
        if (userid.isNotEmpty() && accessToken.isNotEmpty()) {
            if (!isExpire()) {
                logger.info { "现有会话有效，用户: $name (ID: $userid)" }
                updateConfigWithCurrentSession(saveToFile = false)
                return true
            } else {
                logger.warn { "现有会话已过期" }
            }
        }

        return false
    }

    /**
     * 从配置文件恢复会话
     */
    private fun tryRestoreSessionFromConfig(): Boolean {
        try {
            val wechatConfig = config?.wechatSession
            if (wechatConfig?.userObj.isNullOrEmpty()) {
                logger.debug { "配置文件中无微信会话数据" }
                return false
            }

            logger.info { "尝试从配置文件恢复微信会话..." }

            // 这里调用现有的注入方法
            if (tryInjectSessionFromConfig()) {
                logger.info { "配置文件会话恢复成功，用户: $name (ID: $userid)" }
                return true
            }

        } catch (e: Exception) {
            logger.debug(e) { "从配置文件恢复会话失败" }
        }

        return false
    }

    /**
     * 尝试自动刷新会话
     */
    private fun tryAutoRefreshSession(): Boolean {
        logger.info { "尝试自动刷新会话..." }

        // 调用现有的刷新方法
        return simpleRefreshUserCookie()
    }

    /**
     * 引导用户获取新会话
     */
    private fun guideUserToGetNewSession() {
        logger.info { "需要获取新的微信会话" }

        // 生成登录二维码（静默生成）
        val qrCodeGenerated = try {
            sduseat.utils.QRCodeUtils.generateQRCodeImage(WECHAT_LOGIN_URL, "wechat_login_qr.png")
        } catch (e: Exception) {
            logger.warn(e) { "二维码生成失败" }
            false
        }

        // 静默生成二维码，登录指南将在Main.kt中显示
        throw AuthException("需要微信登录Cookie，请按照指南配置")
    }









    /**
     * 检查是否有有效的会话
     */
    private fun isValidSession(verbose: Boolean = false): Boolean {
        val cookies = cookieCathe[host] ?: return false

        // 检查必要的cookie是否存在
        val hasUserObj = cookies.containsKey("userObj")
        val hasSchool = cookies.containsKey("school")
        val hasUser = cookies.containsKey("user")
        val hasConnectSid = cookies.containsKey("connect.sid")
        val hasDinepo = cookies.containsKey("dinepo")
        val hasSeat = cookies.containsKey("seat")

        // 只在verbose模式或首次检查时输出详细日志
        if (verbose) {
            logger.info { "Cookie状态: userObj=$hasUserObj, user=$hasUser, school=$hasSchool (共${cookies.size}个)" }

            // 打印所有Cookie名称用于调试
            if (cookies.isNotEmpty()) {
                logger.info { "所有Cookie: ${cookies.keys.joinToString(", ")}" }
            } else {
                logger.warn { "没有找到任何Cookie！" }
            }

            // 显示缺少的关键Cookie
            val missingCookies = mutableListOf<String>()
            if (!hasUserObj) missingCookies.add("userObj")
            if (!hasUser) missingCookies.add("user")
            if (!hasSchool) missingCookies.add("school")
            if (!hasConnectSid) missingCookies.add("connect.sid")
            if (!hasDinepo) missingCookies.add("dinepo")
            if (!hasSeat) missingCookies.add("seat")

            if (missingCookies.isNotEmpty()) {
                logger.warn { "缺少的Cookie: ${missingCookies.joinToString(", ")}" }
            }
        }

        // 至少需要userObj和user这两个关键Cookie
        return hasUserObj && hasUser
    }


    /**
     * 执行微信登录流程
     * 按照抓包发现的正确API调用顺序
     */
    private fun performWeChatLogin(skipInitialCheck: Boolean = false) {
        logger.info { "开始执行微信登录流程..." }

        // 首先检查配置文件中是否有预设的OAuth会话数据
        if (tryInjectSessionFromConfig()) {
            logger.info { "成功从配置文件注入OAuth会话数据" }
            return
        }

        // 第一步：检查当前用户状态（除非明确跳过）
        if (!skipInitialCheck && checkCurrentUserStatus()) {
            logger.info { "用户已登录，无需重新认证" }
            return
        }

        // 第二步：先访问主页面建立会话
        visitHomePage()

        // 第三步：生成登录二维码并等待用户登录
        generateQRCodeAndWaitForLogin()
    }

    /**
     * 访问主页面建立会话
     * 在执行微信检查之前先访问主页面，可能有助于建立正确的会话状态
     */
    private fun visitHomePage() {
        try {
            logger.info { "访问主页面建立会话..." }

            val homeUrl = "http://seatwx.lib.sdu.edu.cn/"

            val res = getProxyClient().newCallResponse(retry) {
                url(homeUrl)
                addWeChatHeaders()
            }

            res.use {
                logger.debug { "主页面访问响应状态码: ${it.code}" }
                // 不需要处理响应内容，只是为了建立会话
            }
        } catch (e: Exception) {
            logger.warn(e) { "访问主页面失败，但继续执行微信检查" }
        }
    }

    /**
     * 检查当前用户状态
     * 对应抓包中的第一个请求：GET /api.php/currentuse?user=202323415006
     */
    private fun checkCurrentUserStatus(): Boolean {
        try {
            logger.info { "检查当前用户状态..." }

            // 优先使用config中的userid，如果为空则使用类属性userid
            val userIdToUse = config?.userid?.takeIf { it.isNotEmpty() } ?: userid

            // 如果userid仍为空，先尝试从Cookie中提取
            if (userIdToUse.isEmpty()) {
                extractSessionFromCookies(verbose = false)
            }

            // 构建API URL，优先使用config中的userid
            val finalUserId = config?.userid?.takeIf { it.isNotEmpty() } ?: userid
            val apiUrl = if (finalUserId.isNotEmpty()) {
                "http://seatwx.lib.sdu.edu.cn:85/api.php/currentuse?user=$finalUserId"
            } else {
                "http://seatwx.lib.sdu.edu.cn:85/api.php/currentuse"
            }

            logger.info { "使用用户ID: $finalUserId 检查当前状态" }

            val res = getProxyClient().newCallResponse(retry) {
                url(apiUrl)
                addWeChatHeaders()
            }

            res.use {
                val responseBody = it.body
                if (responseBody == null) {
                    logger.warn { "用户状态检查响应体为空" }
                    return false
                }

                // 检查是否是gzip压缩
                val contentEncoding = it.headers["Content-Encoding"]
                val responseText = if (contentEncoding == "gzip") {
                    logger.debug { "检测到gzip压缩响应，手动解压缩..." }
                    try {
                        val gzipInputStream = java.util.zip.GZIPInputStream(responseBody.byteStream())
                        gzipInputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            reader.readText()
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "gzip解压缩失败，尝试直接读取" }
                        responseBody.string()
                    }
                } else {
                    responseBody.string()
                }

                logger.debug { "用户状态检查响应: $responseText" }

                if (it.code == 200) {
                    val json = GSON.parseString(responseText).asJsonObject
                    val status = json.get("status")?.asInt ?: 0
                    val msg = json.get("msg")?.asString ?: ""

                    logger.info { "用户状态: $msg (状态码: $status)" }

                    // 状态码1表示"获取正在使用中的座位或研讨间"，说明用户已登录
                    if (status == 1) {
                        logger.info { "检测到用户已登录（状态码1表示已认证）" }
                        // 尝试从Cookie中提取会话信息
                        if (isValidSession()) {
                            extractSessionFromCookies(verbose = false)
                            if (accessToken.isNotEmpty() && userid.isNotEmpty()) {
                                logger.info { "成功提取登录会话信息，用户ID: $userid" }
                                return true
                            }
                        }

                        // 如果没有提取到完整会话信息，说明Cookie不完整或已过期
                        // 但不要在这里返回false，而是继续尝试其他认证方式
                        logger.warn { "检测到登录状态但会话信息不完整，将尝试通过OAuth获取完整会话" }
                    }
                }
            }

            return false
        } catch (e: Exception) {
            logger.debug(e) { "检查用户状态失败，继续登录流程" }
            return false
        }
    }



    /**
     * 生成二维码并等待用户登录
     */
    private fun generateQRCodeAndWaitForLogin() {
        try {
            logger.info { "生成微信登录二维码..." }

            // 生成登录页面二维码
            generateQRCode(WECHAT_LOGIN_URL)

            // 抛出异常，让Main.kt处理登录指南显示
            throw AuthException("需要微信登录Cookie，请按照指南配置")

        } catch (e: Exception) {
            logger.error(e) { "二维码登录流程失败" }
            throw AuthException("二维码登录流程失败：${e.message}")
        }
    }

    /**
     * 生成二维码
     */
    private fun generateQRCode(url: String) {
        try {
            // 使用QRCodeUtils生成二维码图片
            sduseat.utils.QRCodeUtils.generateQRCodeWithInstructions(url)

        } catch (e: Exception) {
            logger.error(e) { "生成二维码失败" }
            // 即使二维码生成失败，也提供URL让用户手动操作
            logger.info { "请在微信中打开以下链接完成登录：\n$url" }
        }
    }



    /**
     * 检查登录状态
     * 通过多种方式检测用户是否已成功登录
     */
    private fun checkLoginStatus(): Boolean {
        try {
            // 方法1：快速检查Cookie是否已设置且包含完整信息
            if (isValidSession()) {
                extractSessionFromCookies(verbose = false)
                if (accessToken.isNotEmpty() && userid.isNotEmpty()) {
                    logger.info { "检测到完整的登录会话信息" }
                    return true
                }
            }

            // 方法2：检查OAuth回调状态（最重要的方法）
            if (checkOAuthCallbackStatus()) {
                logger.info { "通过OAuth回调检测到登录成功" }
                return true
            }

            // 方法3：访问主页面检查登录状态
            val urlsToCheck = listOf(
                "http://seatwx.lib.sdu.edu.cn/",
                "http://seatwx.lib.sdu.edu.cn/#/index/home"
            )

            for (url in urlsToCheck) {
                if (checkUrlForLoginStatus(url)) {
                    logger.info { "通过访问主页面检测到登录成功" }
                    return true
                }
            }

            return false

        } catch (e: Exception) {
            logger.debug(e) { "检查登录状态时出现异常" }
            return false
        }
    }

    /**
     * 提示用户手动输入Cookie
     */
    /**
     * 显示登录指南并提示用户输入Cookie
     */
    private fun showLoginGuideAndPrompt(qrCodeGenerated: Boolean) {
        if (qrCodeGenerated) {
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
                |5. 将Cookie配置到config.json文件的wechatSession部分
                |6. 重新运行程序
                |
                |Cookie格式示例：
                |userObj=%7B%22id%22%3A123...; user=username; school=sdu
                |
                |或者，您可以选择手动输入Cookie：
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
                |5. 将Cookie配置到config.json文件的wechatSession部分
                |6. 重新运行程序
                |
                |Cookie格式示例：
                |userObj=%7B%22id%22%3A123...; user=username; school=sdu
                |
                |或者，您可以选择手动输入Cookie：
                |
                |====================================================
                |
            """.trimMargin())
        }

        if (promptManualCookieInput(qrCodeGenerated)) {
            logger.info { "手动Cookie输入成功，登录完成！" }
        } else {
            throw AuthException("未能获取有效的微信登录Cookie，请按照上述步骤手动配置或重试")
        }
    }

    private fun promptManualCookieInput(qrCodeGenerated: Boolean = false): Boolean {
        try {
            print("请输入完整的Cookie字符串（输入'skip'跳过）: ")
            val cookieInput = readLine()?.trim()

            if (cookieInput.isNullOrEmpty() || cookieInput.equals("skip", ignoreCase = true)) {
                logger.info { "跳过手动Cookie输入" }
                return false
            }

            // 解析并设置Cookie
            if (parseAndSetManualCookies(cookieInput)) {
                logger.info { "手动Cookie设置成功！" }
                extractSessionFromCookies(verbose = true) // 手动输入时显示详细信息
                return isValidSession(verbose = true) // 显示详细验证信息
            } else {
                logger.warn { "Cookie格式无效，请检查输入" }
                return false
            }

        } catch (e: Exception) {
            logger.debug(e) { "手动Cookie输入失败" }
            return false
        }
    }

    /**
     * 解析并设置手动输入的Cookie
     */
    private fun parseAndSetManualCookies(cookieString: String): Boolean {
        try {
            val cookies = mutableMapOf<String, okhttp3.Cookie>()

            // 解析Cookie字符串
            cookieString.split(";").forEach { pair ->
                val trimmed = pair.trim()
                val equalIndex = trimmed.indexOf("=")
                if (equalIndex > 0) {
                    val name = trimmed.substring(0, equalIndex).trim()
                    val value = trimmed.substring(equalIndex + 1).trim()

                    // 创建Cookie对象
                    val cookie = okhttp3.Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(host)
                        .path("/")
                        .build()

                    cookies[name] = cookie
                }
            }

            if (cookies.isEmpty()) {
                logger.warn { "未能解析到任何Cookie" }
                return false
            }

            logger.info { "解析到 ${cookies.size} 个Cookie: ${cookies.keys.joinToString(", ")}" }

            // 设置Cookie到缓存
            cookieCathe[host] = cookies

            // 验证关键Cookie是否存在
            val hasUserObj = cookies.containsKey("userObj")
            val hasUser = cookies.containsKey("user")
            val hasSchool = cookies.containsKey("school")

            logger.info { "关键Cookie检查: userObj=$hasUserObj, user=$hasUser, school=$hasSchool" }

            return hasUserObj || hasUser || hasSchool

        } catch (e: Exception) {
            logger.debug(e) { "解析Cookie失败" }
            return false
        }
    }

    /**
     * 检查OAuth回调状态
     * 专门处理用户完成微信OAuth后的状态检测
     */
    private fun checkOAuthCallbackStatus(): Boolean {
        try {
            // 检查当前Cookie状态
            val cookies = cookieCathe[host]
            if (cookies != null && cookies.isNotEmpty()) {
                val hasUserObj = cookies.containsKey("userObj")
                val hasUser = cookies.containsKey("user")
                val hasSchool = cookies.containsKey("school")
                val hasDinepo = cookies.containsKey("dinepo")
                val hasConnectSid = cookies.containsKey("connect.sid")

                logger.debug { "Cookie状态检查: userObj=$hasUserObj, user=$hasUser, school=$hasSchool, dinepo=$hasDinepo, connect.sid=$hasConnectSid" }

                // 如果有完整的用户会话Cookie，直接提取
                if (hasUserObj && hasUser) {
                    logger.info { "检测到完整的用户会话Cookie，提取会话信息..." }
                    extractSessionFromCookies(verbose = false)
                    if (accessToken.isNotEmpty() && userid.isNotEmpty()) {
                        logger.info { "成功从Cookie提取完整会话信息" }
                        return true
                    }
                }

                // 检查是否有真正的OAuth相关Cookie（更严格的检查）
                // 只有当有userObj或user Cookie时才认为是OAuth回调
                // school、dinepo、connect.sid可能来自其他API调用，不应该触发OAuth完成流程
                if ((hasUserObj || hasUser) && !(hasUserObj && hasUser)) {
                    logger.info { "检测到部分OAuth用户Cookie，尝试完成认证流程..." }
                    logger.debug { "部分用户Cookie详情: userObj=$hasUserObj, user=$hasUser" }

                    // 显示当前所有Cookie用于调试
                    cookies.forEach { (name, cookie) ->
                        logger.debug { "当前Cookie: $name = ${cookie.value.take(50)}..." }
                    }

                    return completeOAuthFlow()
                }
            }

            // 尝试检测OAuth回调是否已经发生
            return checkForOAuthCallback()

        } catch (e: Exception) {
            logger.debug(e) { "检查OAuth回调状态失败" }
            return false
        }
    }

    /**
     * 检测OAuth回调是否已经发生
     * 通过访问关键页面来检测用户是否已经在微信中完成了OAuth授权
     */
    private fun checkForOAuthCallback(): Boolean {
        try {
            // 尝试访问主页，看是否能触发OAuth回调的Cookie设置
            val homeUrl = "http://seatwx.lib.sdu.edu.cn/"
            logger.debug { "检测OAuth回调状态，访问主页: $homeUrl" }

            val res = getProxyClient().newCallResponse(2) {
                url(homeUrl)
                addWeChatHeaders()
            }

            res.use {
                logger.debug { "主页响应状态码: ${it.code}" }

                // 检查是否有新的Cookie设置
                val setCookies = it.headers.values("Set-Cookie")
                if (setCookies.isNotEmpty()) {
                    logger.debug { "从主页检测到Cookie设置: ${setCookies.size} 个" }
                }

                // 再次检查Cookie状态
                val cookies = cookieCathe[host]
                if (cookies != null && cookies.isNotEmpty()) {
                    val hasUserObj = cookies.containsKey("userObj")
                    val hasUser = cookies.containsKey("user")

                    if (hasUserObj && hasUser) {
                        logger.info { "检测到OAuth回调已完成，获得完整会话" }
                        extractSessionFromCookies(verbose = false)
                        return accessToken.isNotEmpty() && userid.isNotEmpty()
                    }
                }
            }

            return false
        } catch (e: Exception) {
            logger.debug(e) { "检测OAuth回调失败" }
            return false
        }
    }

    /**
     * 完成OAuth认证流程
     * 当检测到部分OAuth Cookie时，尝试访问关键页面完成认证
     */
    private fun completeOAuthFlow(): Boolean {
        try {
            logger.info { "尝试完成OAuth认证流程..." }

            // 尝试访问可能触发完整认证的URL，按照正确的OAuth流程顺序
            val urlsToTry = listOf(
                "http://seatwx.lib.sdu.edu.cn/#/index/home", // OAuth成功后的重定向目标（优先）
                "http://seatwx.lib.sdu.edu.cn/",           // 主页
                "http://seatwx.lib.sdu.edu.cn:85/api.php/currentuse?user=${config?.userid ?: userid}" // 用户状态API验证
            )

            for (url in urlsToTry) {
                try {
                    logger.debug { "尝试访问: $url" }
                    val res = getProxyClient().newCallResponse(3) {
                        url(url)
                        addWeChatHeaders()
                    }

                    res.use {
                        logger.debug { "访问 $url 响应: ${it.code}" }

                        // 检查是否设置了新的Cookie
                        val setCookies = it.headers.values("Set-Cookie")
                        if (setCookies.isNotEmpty()) {
                            logger.debug { "从 $url 获取到 ${setCookies.size} 个新Cookie" }
                        }

                        // 检查是否现在有完整的会话
                        if (isValidSession()) {
                            logger.info { "通过访问 $url 获取到完整会话" }
                            extractSessionFromCookies(verbose = false)
                            if (accessToken.isNotEmpty() && userid.isNotEmpty()) {
                                logger.info { "OAuth认证流程完成成功" }

                                // 更新配置文件中的会话信息
                                updateConfigWithCurrentSession(saveToFile = true)
                                return true
                            } else {
                                logger.debug { "会话有效但提取信息失败: accessToken=${accessToken.take(20)}..., userid=$userid" }
                            }
                        } else {
                            logger.debug { "访问 $url 后仍无完整会话" }
                        }

                        // 如果是200响应，检查页面内容
                        if (it.code == 200) {
                            val responseText = it.body?.text() ?: ""
                            if (responseText.contains("userObj:") && responseText.contains("user:")) {
                                logger.info { "在页面中检测到用户会话信息" }
                                extractUserSession(responseText)
                                return true
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.debug(e) { "访问 $url 失败" }
                }
            }

            logger.warn { "无法完成OAuth认证流程" }
            return false

        } catch (e: Exception) {
            logger.debug(e) { "完成OAuth认证流程失败" }
            return false
        }
    }

    /**
     * 检查指定URL的登录状态
     */
    private fun checkUrlForLoginStatus(url: String): Boolean {
        try {
            val res = getProxyClient().newCallResponse(retry) {
                url(url)
                addWeChatHeaders()
            }

            res.use {
                when (it.code) {
                    200 -> {
                        val responseText = it.body?.text() ?: ""

                        // 检查页面是否包含用户信息
                        if (responseText.contains("userObj:") && responseText.contains("user:")) {
                            logger.debug { "在页面中检测到用户会话信息" }
                            extractUserSession(responseText)
                            return true
                        }

                        // 检查页面是否显示已登录状态
                        if (responseText.contains("个人中心") || responseText.contains("我的预约") ||
                            responseText.contains("退出登录") || responseText.contains("用户信息")) {
                            logger.debug { "页面显示已登录状态" }
                            if (isValidSession()) {
                                extractSessionFromCookies(verbose = false)
                                return true
                            }
                        }
                    }
                    302 -> {
                        // 检查重定向中的Cookie
                        val cookies = it.headers.values("Set-Cookie")
                        val hasUserCookie = cookies.any { cookie -> cookie.startsWith("user=") }
                        val hasUserObjCookie = cookies.any { cookie -> cookie.startsWith("userObj=") }

                        if (hasUserCookie && hasUserObjCookie) {
                            logger.debug { "从重定向中检测到认证Cookie" }
                            extractSessionFromCookies(verbose = false)
                            return true
                        }
                    }
                }
            }

            return false
        } catch (e: Exception) {
            logger.debug(e) { "检查URL登录状态失败: $url" }
            return false
        }
    }

    /**
     * 从响应中提取用户会话信息
     */
    private fun extractUserSession(responseText: String) {
        try {
            logger.debug { "开始提取用户会话信息..." }

            // 提取userObj信息
            val userObjMatch = Regex("userObj: ([^,\\n\\r]+)").find(responseText)
            if (userObjMatch != null) {
                val userObjEncoded = userObjMatch.groupValues[1].trim()
                val userObjDecoded = URLDecoder.decode(userObjEncoded, StandardCharsets.UTF_8.toString())
                logger.debug { "用户对象: $userObjDecoded" }

                val userObj = GSON.parseString(userObjDecoded).asJsonObject
                name = userObj.get("name")?.asString ?: userid
                logger.info { "用户姓名: $name" }

                // 验证用户ID是否匹配
                val extractedUserId = userObj.get("id")?.asString
                if (extractedUserId != null && extractedUserId != userid) {
                    logger.warn { "提取的用户ID ($extractedUserId) 与配置的用户ID ($userid) 不匹配" }
                }
            }

            // 提取school信息
            val schoolMatch = Regex("school: ([^,\\n\\r]+)").find(responseText)
            if (schoolMatch != null) {
                val schoolEncoded = schoolMatch.groupValues[1].trim()
                val schoolDecoded = URLDecoder.decode(schoolEncoded, StandardCharsets.UTF_8.toString())
                logger.debug { "学校配置: $schoolDecoded" }
            }

            // 提取dinepo (WeChat OpenID)
            val dinepoMatch = Regex("dinepo: ([^,\\n\\r]+)").find(responseText)
            if (dinepoMatch != null) {
                val dinepo = dinepoMatch.groupValues[1].trim()
                logger.debug { "微信OpenID: $dinepo" }
            }

            // 提取user会话信息
            val userMatch = Regex("user: ([^,\\n\\r]+)").find(responseText)
            if (userMatch != null) {
                val userEncoded = userMatch.groupValues[1].trim()
                val userDecoded = URLDecoder.decode(userEncoded, StandardCharsets.UTF_8.toString())
                logger.debug { "用户会话: $userDecoded" }

                val user = GSON.parseString(userDecoded).asJsonObject
                accessToken = user.get("access_token")?.asString ?: ""
                expire = user.get("expire")?.asString

                if (accessToken.isNotEmpty()) {
                    logger.info { "成功提取access_token: ${accessToken.take(8)}..." }
                } else {
                    throw AuthException("未能提取到access_token")
                }
            }

            // 提取connect.sid
            val connectSidMatch = Regex("connect\\.sid: ([^,\\n\\r]+)").find(responseText)
            if (connectSidMatch != null) {
                val connectSid = connectSidMatch.groupValues[1].trim()
                logger.debug { "会话ID: ${connectSid.take(20)}..." }
            }

            // 验证提取的信息
            if (accessToken.isEmpty()) {
                throw AuthException("未能从页面中提取到有效的认证信息")
            }

            logger.info { "微信OAuth认证成功，欢迎 $name" }

        } catch (e: Exception) {
            logger.error(e) { "提取用户会话信息失败" }
            throw AuthException("提取用户会话信息失败：${e.message}")
        }
    }

    override fun isExpire(): Boolean {
        // 检查access_token是否过期
        if (accessToken.isEmpty()) {
            return true
        }

        // 如果有过期时间，检查是否过期（提前2分钟认为过期，避免在预约过程中过期）
        expire?.let { expireTime ->
            try {
                val expireDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(expireTime)
                val now = java.util.Date()
                // 提前2分钟认为过期
                val bufferTime = java.util.Date(expireDate.time - 2 * 60 * 1000)
                if (now.after(bufferTime)) {
                    val remainingMinutes = (expireDate.time - now.time) / (60 * 1000)
                    logger.info { "access_token即将过期或已过期，剩余时间: ${remainingMinutes} 分钟" }
                    return true
                }
            } catch (e: Exception) {
                logger.warn(e) { "解析过期时间失败: $expireTime" }
                return true // 解析失败时认为已过期
            }
        }

        return false
    }

    /**
     * 获取会话剩余时间（分钟）
     * @return 剩余分钟数，如果无法计算则返回-1
     */
    fun getRemainingMinutes(): Long {
        expire?.let { expireTime ->
            try {
                val expireDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(expireTime)
                val now = java.util.Date()
                return (expireDate.time - now.time) / (60 * 1000)
            } catch (e: Exception) {
                logger.warn(e) { "解析过期时间失败: $expireTime" }
            }
        }
        return -1
    }






    /**
     * 简单有效的会话刷新：直接延长user Cookie的过期时间
     * @return true 如果刷新成功
     */
    private fun simpleRefreshUserCookie(): Boolean {
        try {
            val cookies = cookieCathe[host] ?: return false
            val userCookie = cookies["user"] ?: return false

            // 解析当前user Cookie值
            val userValue = java.net.URLDecoder.decode(userCookie.value, "UTF-8")
            logger.debug { "[会话刷新] 当前user Cookie: $userValue" }

            // 解析JSON
            val userJson = com.google.gson.JsonParser.parseString(userValue).asJsonObject
            val currentUserId = userJson.get("userid")?.asString ?: ""
            val currentAccessToken = userJson.get("access_token")?.asString ?: ""

            if (currentUserId.isEmpty() || currentAccessToken.isEmpty()) {
                logger.warn { "[会话刷新] user Cookie中缺少必要信息" }
                return false
            }

            // 生成新的过期时间（延长3天）
            val newExpireTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(
                java.util.Date(System.currentTimeMillis() + 3 * 24 * 60 * 60 * 1000)
            )

            // 构建新的user对象
            val newUserObj = mapOf(
                "userid" to currentUserId,
                "access_token" to currentAccessToken,
                "expire" to newExpireTime
            )

            val newUserJson = com.google.gson.Gson().toJson(newUserObj)
            val newUserValue = java.net.URLEncoder.encode(newUserJson, "UTF-8")

            logger.info { "[会话刷新] 延长会话过期时间: $expire -> $newExpireTime" }

            // 更新Cookie
            val newUserCookie = okhttp3.Cookie.Builder()
                .name("user")
                .value(newUserValue)
                .domain(host)
                .path("/")
                .build()

            cookies["user"] = newUserCookie
            cookieCathe[host] = cookies

            // 更新内存中的会话信息
            expire = newExpireTime

            logger.info { "[会话刷新] 会话刷新成功，新过期时间: $newExpireTime" }
            return true

        } catch (e: Exception) {
            logger.warn(e) { "[会话刷新] 简单刷新失败" }
            return false
        }
    }





    /**
     * 尝试从配置文件中注入OAuth会话数据
     */
    private fun tryInjectSessionFromConfig(): Boolean {
        try {
            val wechatSession = config?.wechatSession
            if (wechatSession != null && wechatSession.autoInject) {
                logger.info { "检测到配置文件中的微信会话数据，开始注入..." }

                // 构建Cookie映射
                val cookies = mutableMapOf<String, okhttp3.Cookie>()

                // 注入各种会话Cookie
                wechatSession.userObj?.let { value ->
                    val cookie = okhttp3.Cookie.Builder()
                        .name("userObj")
                        .value(value)
                        .domain(host)
                        .path("/")
                        .build()
                    cookies["userObj"] = cookie
                }

                wechatSession.user?.let { value ->
                    val cookie = okhttp3.Cookie.Builder()
                        .name("user")
                        .value(value)
                        .domain(host)
                        .path("/")
                        .build()
                    cookies["user"] = cookie
                }

                wechatSession.school?.let { value ->
                    val cookie = okhttp3.Cookie.Builder()
                        .name("school")
                        .value(value)
                        .domain(host)
                        .path("/")
                        .build()
                    cookies["school"] = cookie
                }

                wechatSession.dinepo?.let { value ->
                    val cookie = okhttp3.Cookie.Builder()
                        .name("dinepo")
                        .value(value)
                        .domain(host)
                        .path("/")
                        .build()
                    cookies["dinepo"] = cookie
                }

                wechatSession.connectSid?.let { value ->
                    val cookie = okhttp3.Cookie.Builder()
                        .name("connect.sid")
                        .value(value)
                        .domain(host)
                        .path("/")
                        .build()
                    cookies["connect.sid"] = cookie
                }

                if (cookies.isNotEmpty()) {
                    // 注入到cookie缓存
                    cookieCathe[host] = cookies

                    logger.info { "成功注入 ${cookies.size} 个微信会话Cookie" }

                    // 提取会话信息（静默模式，避免重复日志）
                    extractSessionFromCookies(verbose = false)

                    // 验证注入是否成功
                    if (accessToken.isNotEmpty() && userid.isNotEmpty()) {
                        logger.info { "微信会话数据注入成功，用户: $name (ID: $userid)" }
                        return true
                    } else {
                        logger.warn { "微信会话数据注入后未能提取到有效的认证信息" }
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "从配置文件注入微信会话数据失败" }
        }
        return false
    }

    /**
     * 从已有的cookies中提取会话信息
     */
    fun extractSessionFromCookies(verbose: Boolean = false) {
        try {
            val cookies = cookieCathe[host]
            if (cookies == null) {
                logger.warn { "未找到主机 $host 的Cookie" }
                return
            }

            // 检查是否已经提取过会话信息，避免重复日志
            val alreadyExtracted = accessToken.isNotEmpty() && userid.isNotEmpty()

            if (verbose && !alreadyExtracted) {
                logger.info { "提取会话信息 (${cookies.size}个Cookie): ${cookies.keys.joinToString(", ")}" }
            }

            // 提取userObj信息
            cookies["userObj"]?.let { cookie ->
                try {
                    val userObjDecoded = URLDecoder.decode(cookie.value, StandardCharsets.UTF_8.toString())
                    logger.debug { "从Cookie提取用户对象: $userObjDecoded" }

                    val userObj = GSON.parseString(userObjDecoded).asJsonObject
                    name = userObj.get("name")?.asString ?: userid
                    // 从userObj中提取userid（如果当前userid为空）
                    if (userid.isEmpty()) {
                        userid = userObj.get("id")?.asString ?: ""
                        if (userid.isNotEmpty()) {
                            logger.info { "从Cookie提取用户ID: $userid" }
                        }
                    }
                    if (verbose && !alreadyExtracted) {
                        logger.info { "从Cookie提取用户姓名: $name" }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "解析userObj Cookie失败" }
                }
            } ?: logger.warn { "未找到userObj Cookie" }

            // 提取user会话信息
            cookies["user"]?.let { cookie ->
                try {
                    val userDecoded = URLDecoder.decode(cookie.value, StandardCharsets.UTF_8.toString())
                    logger.debug { "从Cookie提取用户会话: $userDecoded" }

                    val user = GSON.parseString(userDecoded).asJsonObject
                    accessToken = user.get("access_token")?.asString ?: ""
                    expire = user.get("expire")?.asString

                    // 从user Cookie中提取userid（如果当前userid为空）
                    if (userid.isEmpty()) {
                        userid = user.get("userid")?.asString ?: ""
                        if (userid.isNotEmpty()) {
                            logger.info { "从user Cookie提取用户ID: $userid" }
                        }
                    }

                    if (accessToken.isNotEmpty()) {
                        if (verbose && !alreadyExtracted) {
                            logger.info { "从Cookie成功提取access_token: ${accessToken.take(8)}..." }
                        }
                        // 只在verbose模式或首次提取时显示会话过期时间
                        if (expire != null && (verbose || !alreadyExtracted)) {
                            logger.info { "会话过期时间: $expire" }
                        }
                    } else {
                        logger.warn { "Cookie中未找到有效的access_token" }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "解析user Cookie失败" }
                }
            } ?: logger.warn { "未找到user Cookie" }

            if (accessToken.isNotEmpty()) {
                if (verbose && !alreadyExtracted) {
                    logger.info { "从Cookie成功恢复微信OAuth会话，欢迎 $name" }
                }
            } else {
                logger.warn { "Cookie中未找到有效的认证信息" }
            }

        } catch (e: Exception) {
            logger.error(e) { "从Cookie提取会话信息失败" }
        }
    }

    /**
     * 手动注入会话Cookie（用于已有有效会话数据的情况）
     *
     * @param userObjData URL编码的userObj数据
     * @param schoolData URL编码的school数据
     * @param dinepoData dinepo数据
     * @param userData URL编码的user数据
     * @param connectSidData connect.sid数据
     */
    fun injectSessionCookies(
        userObjData: String,
        schoolData: String,
        dinepoData: String,
        userData: String,
        connectSidData: String
    ) {
        try {
            // 清空现有的会话信息，避免重复日志
            accessToken = ""
            userid = ""
            name = ""
            expire = null

            val cookies = mutableMapOf<String, okhttp3.Cookie>()

            // 创建Cookie
            cookies["userObj"] = okhttp3.Cookie.Builder()
                .name("userObj")
                .value(userObjData)
                .domain(host)
                .path("/")
                .build()

            cookies["school"] = okhttp3.Cookie.Builder()
                .name("school")
                .value(schoolData)
                .domain(host)
                .path("/")
                .build()

            cookies["dinepo"] = okhttp3.Cookie.Builder()
                .name("dinepo")
                .value(dinepoData)
                .domain(host)
                .path("/")
                .build()

            cookies["user"] = okhttp3.Cookie.Builder()
                .name("user")
                .value(userData)
                .domain(host)
                .path("/")
                .build()

            cookies["connect.sid"] = okhttp3.Cookie.Builder()
                .name("connect.sid")
                .value(connectSidData)
                .domain(host)
                .path("/")
                .build()

            // 注入到cookie缓存
            cookieCathe[host] = cookies

            // 提取会话信息
            extractSessionFromCookies(verbose = false)

        } catch (e: Exception) {
            logger.error(e) { "注入会话Cookie失败" }
            throw AuthException("注入会话Cookie失败：${e.message}")
        }
    }



    /**
     * 更新配置文件中的微信会话信息
     * @param saveToFile 是否立即保存到配置文件
     */
    fun updateConfigWithCurrentSession(saveToFile: Boolean = true) {
        try {
            if (config?.wechatSession == null) {
                logger.debug { "配置中没有wechatSession节点，跳过更新" }
                return
            }

            val cookies = cookieCathe[host]
            if (cookies == null || cookies.isEmpty()) {
                logger.debug { "没有可用的Cookie，跳过配置更新" }
                return
            }

            // 更新各个Cookie值
            cookies["userObj"]?.let { cookie ->
                config!!.wechatSession!!.userObj = cookie.value
                logger.debug { "更新userObj到配置" }
            }

            cookies["school"]?.let { cookie ->
                config!!.wechatSession!!.school = cookie.value
                logger.debug { "更新school到配置" }
            }

            cookies["dinepo"]?.let { cookie ->
                config!!.wechatSession!!.dinepo = cookie.value
                logger.debug { "更新dinepo到配置" }
            }

            cookies["user"]?.let { cookie ->
                config!!.wechatSession!!.user = cookie.value
                logger.debug { "更新user到配置" }
            }

            cookies["connect.sid"]?.let { cookie ->
                config!!.wechatSession!!.connectSid = cookie.value
                logger.debug { "更新connectSid到配置" }
            }

            // 如果需要，保存到文件
            if (saveToFile) {
                Config.saveConfig()
                logger.info { "[配置更新] 微信会话信息已保存到配置文件" }
            }

        } catch (e: Exception) {
            logger.warn(e) { "[配置更新] 更新配置文件中的微信会话信息失败" }
        }
    }
}



/**
 * 为Request.Builder添加微信浏览器请求头
 */
private fun Request.Builder.addWeChatHeaders() {
    // 设置微信浏览器User-Agent
    header("User-Agent", WECHAT_USER_AGENT)

    // 设置微信浏览器特有的请求头 - 更接近真实微信浏览器
    header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/wxpic,image/tpg,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
    header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
    header("Accept-Encoding", "gzip, deflate")
    header("X-Requested-With", "com.tencent.mm")
    header("Connection", "keep-alive")
    header("Upgrade-Insecure-Requests", "1")
    header("Sec-Fetch-Dest", "document")
    header("Sec-Fetch-Mode", "navigate")
    header("Sec-Fetch-Site", "none")
    header("Sec-Fetch-User", "?1")

    // 设置Referer为主页面
    header("Referer", "http://seatwx.lib.sdu.edu.cn/")
}


