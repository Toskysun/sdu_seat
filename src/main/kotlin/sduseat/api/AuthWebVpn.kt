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
import sduseat.constant.Const
import mu.KotlinLogging
import sduseat.http.*
import sduseat.utils.centerString
import org.jsoup.Jsoup

private val logger = KotlinLogging.logger {}

class AuthWebVpn(
    userid: String,
    password: String,
    deviceId: String,
    retry: Int = 0
) : IAuth(userid, password, deviceId, retry) {
    override val authUrl: String = "https://webvpn.sdu.edu.cn/https/77726476706e69737468656265737421e0f6528f69236c45300d8db9d6562d/cas/login?service=http%3A%2F%2Flibseat.sdu.edu.cn%2Fcas%2Findex.php%3Fcallback%3Dhttp%3A%2F%2Flibseat.sdu.edu.cn%2Fhome%2Fweb%2Ff_second"
    // Note: HTTP URL is intentionally embedded in HTTPS WebVPN URL for proper routing
    @Suppress("HttpUrlsUsage")
    private val libAuthUrl =
        "https://webvpn.sdu.edu.cn/http/77726476706e69737468656265737421e3f24088693c6152301b8db9d6502720e38b79/cas/index.php?callback=http://libseat.sdu.edu.cn/home/web/f_second"

    companion object {
        var lastSuccessLogin: Long = 0
    }

    override fun login() {
        //从统一身份认证界面获取必要信息
        gatherInfo()
        //获得rsa
        rsa = getRsa(userid, password, lt)
        //统一身份认证、图书馆认证
        auth()
        //获取access_token
        accessToken = fetchAccessToken()
        lastSuccessLogin = System.currentTimeMillis()
    }

    private fun gatherInfo() {
        val res = getProxyClient().newCallResponseText(retry) {
            url(libAuthUrl)
        }
        gatherInfo(res)
    }

    private fun auth() {
        //登录webvpn
        var res = getProxyClient().newCallResponse(retry) {
            url(authUrl)
            postForm(mapOf(
                "rsa" to rsa,
                "ul" to userid.length,
                "pl" to password.length,
                "lt" to lt,
                "execution" to execution,
                "_eventId" to eventId
            ))
        }
        res.use {
            if (it.code != 200) {
                throw AuthException("阶段1/3：响应状态码为${it.code}, 信息化门户认证失败")
            }
            val resText = it.body?.text() ?: throw AuthException("阶段1：信息化门户认证失败")
            val doc = Jsoup.parse(resText)
            val title = doc.title()
            if (title == "山东大学信息化公共服务平台") {
                name = doc.selectFirst("#user-btn-01")?.text() ?: ""
                logger.info { "阶段1/3：信息化门户认证成功，欢迎$name" }
            } else {
                throw AuthException("阶段1/3：响应页面为$title, 信息化门户认证失败")
            }
        }

        //登录图书馆
        res = getProxyClient().newCallResponse(retry) {
            url(libAuthUrl)
        }
        res.use {
            if (it.code != 200) {
                throw AuthException("阶段2/3：响应状态码为${it.code}, 图书馆认证失败")
            }
            val resText = it.body?.text() ?: throw AuthException("阶段2/3：图书馆认证失败")
            val doc = Jsoup.parse(resText)
            val title = doc.title()
            if (title == "跳转提示") {
                logger.info { "阶段2/3：图书馆认证成功" }
            } else {
                throw AuthException("阶段2/3：响应页面为$title, 图书馆认证失败")
            }
        }
    }

    private fun fetchAccessToken(): String {
        val res = getProxyClient().newCallResponse(retry) {
            url(Const.LIB_FIRST_URL)
        }
        return res.use {
            if (it.code != 200) {
                throw AuthException("阶段3/3：响应状态码为${it.code}, access_token获取失败")
            }
            val resText = it.body?.text() ?: throw AuthException("阶段3：access_token获取失败")
            val accessToken = resText.centerString("'access_token':\"", "\"")
            if (accessToken.isEmpty()) {
                throw AuthException("阶段3/3：access_token获取失败")
            } else {
                logger.info { "阶段3/3：access_token获取成功，access_token=$accessToken" }
            }
            accessToken
        }
    }

    override fun isExpire(): Boolean {
        return System.currentTimeMillis() - lastSuccessLogin > 30 * 60 * 1000
    }
}