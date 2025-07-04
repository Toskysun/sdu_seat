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

@file:Suppress("UNUSED_PARAMETER")

package sduseat.utils

import com.google.gson.*
import com.google.gson.internal.LinkedTreeMap
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonWriter
import sduseat.constant.Const.logger
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.lang.Exception
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.math.ceil

val GSON: Gson by lazy {
    GsonBuilder()
        .registerTypeAdapter(
            object : TypeToken<Map<String?, Any?>?>() {}.type,
            MapDeserializerDoubleAsIntFix()
        )
        .registerTypeAdapter(Int::class.java, IntJsonDeserializer())
        .registerTypeAdapter(
            object : TypeToken<ArrayList<String>>() {}.type,
            CompactArraySerializer()
        )
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()
}

inline fun <reified T> genericType(): Type = object : TypeToken<T>() {}.type

inline fun <reified T> Gson.fromJsonObject(json: String?): T? {
    //可转成任意类型
    return kotlin.runCatching {
        fromJson(json, genericType<T>()) as? T
    }.onFailure {
        logger.error(it) { json }
    }.getOrNull()
}

@Suppress("unused")
inline fun <reified T> Gson.fromJsonArray(json: String?): List<T>? {
    return kotlin.runCatching {
        fromJson(json, ParameterizedTypeImpl(T::class.java)) as? List<T>
    }.onFailure {
        logger.error(it) { json }
    }.getOrNull()
}

@Suppress("unused")
fun Gson.writeToOutputStream(out: OutputStream, any: Any) {
    val writer = JsonWriter(OutputStreamWriter(out, "UTF-8"))
    writer.setIndent("  ")
    if (any is List<*>) {
        writer.beginArray()
        any.forEach {
            it?.let {
                toJson(it, it::class.java, writer)
            }
        }
        writer.endArray()
    } else {
        toJson(any, any::class.java, writer)
    }
    writer.close()
}

@Suppress("unused", "UNUSED_PARAMETER", "EXTENSION_SHADOWED_BY_MEMBER")
fun Gson.parseString(json: String): JsonElement {
    try {
        return JsonParser.parseString(json)
    } catch (e: Exception) {
        logger.error { """
            json解析错误，内容如下:
            $json
        """.trimIndent() }
        throw e
    }
}

class ParameterizedTypeImpl(private val clazz: Class<*>) : ParameterizedType {
    override fun getRawType(): Type = List::class.java

    override fun getOwnerType(): Type? = null

    override fun getActualTypeArguments(): Array<Type> = arrayOf(clazz)
}

/**
 * int类型转化失败时跳过
 */
class IntJsonDeserializer : JsonDeserializer<Int?> {

    @Suppress("UNUSED_PARAMETER")
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): Int? {
        return when {
            json.isJsonPrimitive -> {
                val prim = json.asJsonPrimitive
                if (prim.isNumber) {
                    prim.asNumber.toInt()
                } else {
                    null
                }
            }

            else -> null
        }
    }

}

/**
 * 紧凑数组序列化器，保持数组横向排列
 */
class CompactArraySerializer : JsonSerializer<ArrayList<String>> {
    override fun serialize(
        src: ArrayList<String>?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?
    ): JsonElement {
        val array = JsonArray()
        src?.forEach { array.add(it) }
        return array
    }
}

/**
 * 修复Int变为Double的问题
 */
class MapDeserializerDoubleAsIntFix :
    JsonDeserializer<Map<String, Any?>?> {

    @Throws(JsonParseException::class)
    @Suppress("UNUSED_PARAMETER")
    override fun deserialize(
        jsonElement: JsonElement,
        type: Type,
        jsonDeserializationContext: JsonDeserializationContext
    ): Map<String, Any?>? {
        @Suppress("unchecked_cast")
        return read(jsonElement) as? Map<String, Any?>
    }

    fun read(json: JsonElement): Any? {
        when {
            json.isJsonArray -> {
                val list: MutableList<Any?> = ArrayList()
                val arr = json.asJsonArray
                for (anArr in arr) {
                    list.add(read(anArr))
                }
                return list
            }

            json.isJsonObject -> {
                val map: MutableMap<String, Any?> =
                    LinkedTreeMap()
                val obj = json.asJsonObject
                val entitySet =
                    obj.entrySet()
                for ((key, value) in entitySet) {
                    map[key] = read(value)
                }
                return map
            }

            json.isJsonPrimitive -> {
                val prim = json.asJsonPrimitive
                when {
                    prim.isBoolean -> {
                        return prim.asBoolean
                    }

                    prim.isString -> {
                        return prim.asString
                    }

                    prim.isNumber -> {
                        val num: Number = prim.asNumber
                        // here you can handle double int/long values
                        // and return any type you want
                        // this solution will transform 3.0 float to long values
                        return if (ceil(num.toDouble()) == num.toLong().toDouble()) {
                            num.toLong()
                        } else {
                            num.toDouble()
                        }
                    }
                }
            }
        }
        return null
    }

}