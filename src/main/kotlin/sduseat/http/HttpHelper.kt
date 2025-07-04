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

package sduseat.http

import okhttp3.*
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val proxyClientCache: ConcurrentHashMap<String, OkHttpClient> by lazy {
    ConcurrentHashMap()
}

val cookieCathe = mutableMapOf<String, MutableMap<String, Cookie>>()

val okHttpClient: OkHttpClient by lazy {
    val specs = arrayListOf(
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.COMPATIBLE_TLS,
        ConnectionSpec.CLEARTEXT
    )

    val builder = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, SSLHelper.unsafeTrustManager)
        .retryOnConnectionFailure(true)
        .hostnameVerifier(SSLHelper.unsafeHostnameVerifier)
        .connectionSpecs(specs)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
                .newBuilder()
                .addHeader("Keep-Alive", "300")
                .addHeader("Connection", "Keep-Alive")
                .addHeader("Cache-Control", "no-cache")
                .build()
            chain.proceed(request)

        }).cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (!cookieCathe.contains(url.host)) {
                    cookieCathe[url.host] = mutableMapOf()
                }
                var hasImportantCookie = false
                cookies.forEach {
                    cookieCathe[url.host]!![it.name] = it
                    // 只记录重要的Cookie
                    if (it.name in listOf("userObj", "user", "school", "dinepo")) {
                        println("✅ 保存重要Cookie: ${it.name} = ${it.value.take(50)}... (主机: ${url.host})")
                        hasImportantCookie = true
                    }
                }
                // 只在有重要Cookie时显示保存信息
                if (hasImportantCookie) {
                    println("✅ 从 ${url.host} 保存了 ${cookies.size} 个Cookie")
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val hostCookies = cookieCathe[url.host]?.values?.toList() ?: emptyList()
                // 减少日志输出，只在调试模式下显示
                return hostCookies
            }
        })
    builder.build()
}

val okHttpClientNoRedirect by lazy {
    okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

/**
 * 缓存代理okHttp
 */
fun getProxyClient(proxy: String? = null, allowRedirect: Boolean = true): OkHttpClient {
    val client = if (allowRedirect) {
        okHttpClient
    } else {
        okHttpClientNoRedirect
    }
    if (proxy.isNullOrBlank()) {
        return client
    }
    proxyClientCache[proxy]?.let {
        return it
    }
    val r = Regex("(http|socks4|socks5)://(.*):(\\d{2,5})(@.*@.*)?")
    val ms = r.findAll(proxy)
    val group = ms.first()
    var username = ""       //代理服务器验证用户名
    var password = ""       //代理服务器验证密码
    val type = if (group.groupValues[1] == "http") "http" else "socks"
    val host = group.groupValues[2]
    val port = group.groupValues[3].toInt()
    if (group.groupValues[4] != "") {
        username = group.groupValues[4].split("@")[1]
        password = group.groupValues[4].split("@")[2]
    }
    if (host.isNotEmpty()) {
        val builder = client.newBuilder()
        if (type == "http") {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
        } else {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
        }
        if (username != "" && password != "") {
            builder.proxyAuthenticator { _, response -> //设置代理服务器账号密码
                val credential: String = Credentials.basic(username, password)
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }
        val proxyClient = builder.build()
        proxyClientCache[proxy] = proxyClient
        return proxyClient
    }
    return client
}