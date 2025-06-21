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

import sduseat.AuthException
import sduseat.constant.Const.LIB_URL
import sduseat.constant.Const.timeDateFormat
import mu.KotlinLogging
import sduseat.http.*
import okhttp3.HttpUrl
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private val logger = KotlinLogging.logger {}

class Auth(
    userid: String,
    password: String,
    deviceId: String,
    retry: Int = 0
) : IAuth(userid, password, deviceId, retry) {
    override val authUrl: String = "$LIB_URL/cas/index.php?callback=https://libseat.sdu.edu.cn/home/web/f_second"

    private val host: String = "libseat.sdu.edu.cn"

    override fun login() {
        //GET 图书馆认证界面 302 -> 统一身份认证界面
        val res = getProxyClient().newCallResponse(retry) {
            url(authUrl)
        }
        val authUrl: HttpUrl
        res.use {
            //从统一身份认证界面获取必要信息
            gatherInfo(it.body?.text() ?: "")
            authUrl = it.request.url
        }
        //验证Device
        device(userid, password)
        //获得rsa
        rsa = getRsa(userid, password, lt)
        //统一身份认证、图书馆认证
        auth(authUrl)
        //如果最终获取到这几个必要cookie则说明登陆成功
        val cookies = cookieCathe[host] ?: throw AuthException("登录失败：未获取到任何 Cookie")
        if (cookies.contains("userid") && cookies.contains("user_name")
            && cookies.contains("access_token")
        ) {
            name = URLDecoder.decode(
                cookies["user_name"]?.value ?: throw AuthException("登录失败：用户名 Cookie 为空"),
                StandardCharsets.UTF_8
            )
            accessToken = cookies["access_token"]?.value ?: throw AuthException("登录失败：访问令牌 Cookie 为空")
            expire = cookies["expire"]?.value
            logger.info { "登录成功，欢迎$name" }
        } else {
            val missingCookies = listOfNotNull(
                if (!cookies.contains("userid")) "userid" else null,
                if (!cookies.contains("user_name")) "user_name" else null,
                if (!cookies.contains("access_token")) "access_token" else null
            ).joinToString(", ")
            throw AuthException("登录失败：缺少必要的 Cookie（$missingCookies）")
        }
    }

    private fun auth(url: HttpUrl) {
        //POST 统一身份认证 发送认证信息
        var res = getProxyClient(allowRedirect = false).newCallResponse(retry) {
            url(url)
            postForm(mapOf(
                "rsa" to rsa,
                "ul" to userid.length,
                "pl" to password.length,
                "lt" to lt,
                "execution" to execution,
                "_eventId" to eventId
            ))
        }
        var newUrl: String
        res.use {
            logger.debug { "Status code for auth-1-response is ${it.code}" }
            if (it.code != 302) {
                val errorBody = it.body?.text()
                throw AuthException("阶段1：响应状态码为${it.code}, 认证失败。服务器返回：$errorBody")
            }
            newUrl = it.headers["Location"]?.replace(" ", "") ?: ""
            if (newUrl.startsWith("/cas/login?service="))
                newUrl = newUrl.replace("/cas/login?service=", "")
        }

        // 切换HEADER绕过检查再进行重定向
        try {
            res = getProxyClient().newCallResponse(retry) {
                url(URLDecoder.decode(newUrl, StandardCharsets.UTF_8))
                header("Host", host)
            }
            res.use {
                logger.debug { "Status code for auth-2-response is ${it.code}" }
                if (it.code != 200) {
                    throw AuthException("阶段2：响应状态码为${it.code}, 认证失败")
                }
            }
        } catch (e: Exception) {
            if (e.message?.contains("HTTP 404") == true) {
                logger.warn { "阶段2：URL可能已更改，尝试直接访问图书馆主页" }
                // 尝试直接访问图书馆主页，跳过可能已更改的中间重定向
                res = getProxyClient().newCallResponse(retry) {
                    url("$LIB_URL/home/web/f_second")
                    header("Host", host)
                }
                res.use {
                    if (it.code != 200) {
                        throw AuthException("阶段2(备用)：响应状态码为${it.code}, 认证失败")
                    }
                }
            } else {
                throw e
            }
        }
    }

    override fun isExpire(): Boolean {
        cookieCathe[host]?.get("expire")?.value?.let {
            kotlin.runCatching {
                if (timeDateFormat.parse(URLDecoder.decode(it, StandardCharsets.UTF_8)).time > System.currentTimeMillis()) {
                    return false
                }
            }
        }
        return true
    }
}