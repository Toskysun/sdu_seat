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

import com.google.gson.JsonElement
import sduseat.constant.Const
import mu.KotlinLogging
import sduseat.utils.GSON
import sduseat.utils.Utf8BomUtils
import sduseat.utils.parseString
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.math.pow

private val logger = KotlinLogging.logger {}

fun OkHttpClient.newCallResponse(
    retry: Int = 0,
    builder: Request.Builder.() -> Unit
): Response {
    val requestBuilder = Request.Builder()
    requestBuilder.header(Const.UA_NAME, Const.USER_AGENT)
    requestBuilder.apply(builder)
    var lastException: IOException? = null
    val request = requestBuilder.build()
    
    for (i in 0..retry) {
        try {
            val response = newCall(request).execute()
            if (response.isSuccessful || response.isRedirect) {
                return response
            }
            
            // 记录更详细的错误信息
            val errorBody = response.body?.string() ?: ""
            val errorMessage = "HTTP ${response.code}: ${response.message}" +
                if (errorBody.isNotEmpty() && errorBody.length < 100) " - $errorBody" else ""
            
            lastException = IOException(errorMessage)
            
            // 记录日志但继续重试
            logger.warn { "请求失败(${i+1}/${retry+1}): ${request.url}, 状态码: ${response.code}" }
            
        } catch (e: IOException) {
            lastException = e
            logger.warn { "请求异常(${i+1}/${retry+1}): ${request.url}, 错误: ${e.message}" }
            
            if (i < retry) {
                val delayMs = (2.0.pow(i.toDouble()) * 1000).toLong().coerceAtMost(10000)
                Thread.sleep(delayMs)
            }
        }
    }
    throw lastException ?: IOException("Unknown error")
}

fun OkHttpClient.newCallResponseBody(
    retry: Int = 0,
    builder: Request.Builder.() -> Unit
): ResponseBody {
    val requestBuilder = Request.Builder()
    requestBuilder.header(Const.UA_NAME, Const.USER_AGENT)
    requestBuilder.apply(builder)
    var lastException: IOException? = null
    val request = requestBuilder.build()
    
    for (i in 0..retry) {
        try {
            val response = newCall(request).execute()
            if (response.isSuccessful || response.isRedirect) {
                return response.body ?: throw IOException("Empty response body")
            }
            
            // 记录更详细的错误信息
            val errorBody = response.body?.string() ?: ""
            val errorMessage = "HTTP ${response.code}: ${response.message}" +
                if (errorBody.isNotEmpty() && errorBody.length < 100) " - $errorBody" else ""
            
            lastException = IOException(errorMessage)
            
            // 记录日志但继续重试
            logger.warn { "请求失败(${i+1}/${retry+1}): ${request.url}, 状态码: ${response.code}" }
            
        } catch (e: IOException) {
            lastException = e
            logger.warn { "请求异常(${i+1}/${retry+1}): ${request.url}, 错误: ${e.message}" }
            
            if (i < retry) {
                val delayMs = (2.0.pow(i.toDouble()) * 1000).toLong().coerceAtMost(10000)
                Thread.sleep(delayMs)
            }
        }
    }
    throw lastException ?: IOException("Unknown error")
}

fun ResponseBody.text(encode: String? = null): String {
    val responseBytes = Utf8BomUtils.removeUTF8BOM(bytes())
    val charsetName: String? = encode

    charsetName?.let {
        return String(responseBytes, Charset.forName(charsetName))
    }

    // 根据http头判断
    contentType()?.charset()?.let {
        return String(responseBytes, it)
    }

    return String(responseBytes, Charset.forName(charsetName ?: "UTF-8"))
}

fun ResponseBody.json(encode: String? = null): JsonElement {
    return GSON.parseString(text(encode))
}

fun Request.Builder.addHeaders(headers: Map<String, String>) {
    headers.forEach {
        if (it.key == Const.UA_NAME) {
            //防止userAgent重复
            removeHeader(Const.UA_NAME)
        }
        addHeader(it.key, it.value)
    }
}

fun Request.Builder.get(url: String, queryMap: Map<String, Any>, encoded: Boolean = false) {
    val httpBuilder = url.toHttpUrl().newBuilder()
    queryMap.forEach {
        if (encoded) {
            httpBuilder.addEncodedQueryParameter(it.key, it.value.toString())
        } else {
            httpBuilder.addQueryParameter(it.key, it.value.toString())
        }
    }
    url(httpBuilder.build())
}

fun Request.Builder.postForm(form: Map<String, Any>, encoded: Boolean = false) {
    val formBody = FormBody.Builder()
    form.forEach {
        if (encoded) {
            formBody.addEncoded(it.key, it.value.toString())
        } else {
            formBody.add(it.key, it.value.toString())
        }
    }
    post(formBody.build())
}

fun Request.Builder.postMultipart(type: String?, form: Map<String, Any>) {
    val multipartBody = MultipartBody.Builder()
    type?.let {
        multipartBody.setType(type.toMediaType())
    }
    form.forEach {
        when (val value = it.value) {
            is Map<*, *> -> {
                val fileName = value["fileName"] as String
                val file = value["file"]
                val mediaType = (value["contentType"] as? String)?.toMediaType()
                val requestBody = when (file) {
                    is File -> {
                        file.asRequestBody(mediaType)
                    }
                    is ByteArray -> {
                        file.toRequestBody(mediaType)
                    }
                    is String -> {
                        file.toRequestBody(mediaType)
                    }
                    else -> {
                        GSON.toJson(file).toRequestBody(mediaType)
                    }
                }
                multipartBody.addFormDataPart(it.key, fileName, requestBody)
            }
            else -> multipartBody.addFormDataPart(it.key, it.value.toString())
        }
    }
    post(multipartBody.build())
}

fun Request.Builder.postJson(json: String?) {
    json?.let {
        val requestBody = json.toRequestBody("application/json; charset=UTF-8".toMediaType())
        post(requestBody)
    }
}