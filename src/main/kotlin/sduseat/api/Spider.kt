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

import sduseat.SpiderException
import sduseat.bean.*
import sduseat.constant.Const.LIB_FIRST_URL
import sduseat.constant.Const.LIB_URL
import io.github.oshai.kotlinlogging.KotlinLogging as KLogger
import sduseat.constant.Const.statusMap
import sduseat.http.getProxyClient
import sduseat.http.newCallResponseText
import sduseat.http.postForm
import sduseat.utils.GSON
import sduseat.utils.centerString
import sduseat.utils.parseString
import org.jsoup.Jsoup

private val logger = KLogger.logger {}

object Spider {

    // 硬编码的区域映射，避免每次都从API获取（从v3areas.json提取的完整映射）
    private val AREA_ID_MAPPING = mapOf(
        // 蒋震馆
        "D410室东区" to 4,
        "D416室" to 6,
        "D603室" to 8,
        "D604室（安静区）" to 9,
        "D610室" to 11,
        "D616室" to 58,
        "D420室" to 144,

        // 千佛山馆
        "工学馆201科技一库" to 15,
        "工学馆208现刊阅览室" to 16,
        "工学馆二楼大厅" to 17,
        "工学馆301科技二库" to 18,

        // 青岛馆
        "青岛馆三楼东阅览区" to 50,
        "青岛馆三楼西阅览区" to 51,
        "青岛馆四楼东阅览区" to 52,
        "青岛馆四楼西阅览区" to 53,
        "青岛馆五楼东阅览区" to 54,
        "青岛馆五楼西阅览区" to 55,
        "青岛馆七楼东阅览区" to 56,
        "青岛馆七楼西阅览区" to 57,
        "青岛馆四楼南阅览区" to 146,
        "青岛馆四楼北阅览区" to 147,
        "青岛馆五楼南阅览区" to 148,
        "青岛馆五楼北阅览区" to 149,
        "青岛馆七楼南阅览区(无声阅览区)" to 150,
        "青岛馆七楼北阅览区" to 151,
        "青岛馆三楼北阅览区" to 183,
        "青岛六楼北" to 187,
        "青岛二楼北有声阅览区" to 189,
        "青岛馆4K显示器体验区1" to 199,
        "青岛馆607智造空间" to 200,
        "青岛馆701、702、703朗读间" to 201,
        "三楼中庭" to 203,
        "四楼中庭" to 204,
        "五楼中庭" to 205,
        "七楼中庭" to 206,
        "青岛馆4K显示器体验区2" to 207,

        // 中心馆
        "101室" to 93,
        "103室" to 94,
        "212室" to 95,
        "209室" to 96,
        "202室" to 98,
        "312室" to 99,
        "407室" to 100,
        "四楼大厅" to 101,
        "403室" to 102,
        "五楼大厅" to 103,
        "505室" to 186,

        // 洪家楼馆
        "政法馆109借书处" to 105,
        "政法馆107" to 106,
        "政法馆期刊阅览室" to 107,
        "政法馆216电子阅览室" to 108,
        "政法馆艺术阅览室" to 109,
        "政法馆报纸阅览室" to 110,
        "205有声阅读空间" to 111,
        "政法馆工具书阅览室" to 112,
        "政法馆214阅览室" to 163,

        // 兴隆山馆
        "兴隆山报刊阅览室" to 114,
        "兴隆山工业技术图书借阅区-2" to 116,
        "兴隆山工业技术图书借阅区-1" to 117,
        "二层自主学习区" to 118,
        "工业过刊库" to 119,
        "兴隆山通识图书借阅区-2" to 120,
        "兴隆山通识图书借阅区-1" to 121,
        "三层自主学习区" to 122,
        "文学书库-2" to 123,
        "文学书库-1" to 124,
        "四层自主学习区" to 125,
        "文学新书借阅区" to 126,
        "兴隆山特色空间" to 155,

        // 趵突泉馆
        "医学馆中文专业阅览室106" to 133,
        "医学馆外文现刊阅览室216" to 134,
        "医学馆中文现刊阅览室206—D" to 135,
        "医学馆中文现刊阅览室206—C" to 136,
        "医学馆信息共享空间314" to 137,
        "医学馆参考阅览室304-G" to 138,
        "医学馆参考阅览室304-F" to 139,
        "医学馆信息共享空间416" to 140,
        "医学馆综合阅览室404-J" to 142,
        "医学馆综合阅览室404-I" to 143,
        "医学馆214" to 145,
        "趵突泉专业阅览室104" to 184,
        "趵突泉阅览室310" to 185,
        "趵突泉专业阅览室114" to 194,
        "趵突泉阅览室112" to 202,

        // 软件园馆
        "软件园电子阅览室" to 176,
        "软件园现刊阅览室" to 177,
        "软件园学习空间" to 178,
        "软件园阅报区" to 179,
        "软件园专题书库阅览室" to 180,
        "软件园综合书库阅览区" to 181
    )

    fun getLibs(retry: Int = 0): Map<String, AreaBean> {
        try {
            // 首先尝试从API获取图书馆信息
            val today = java.time.LocalDate.now().toString()
            val apiUrl = "$LIB_URL/api.php/v3areas?date=$today"

            logger.debug { "尝试从API获取图书馆信息: $apiUrl" }

            val res = getProxyClient().newCallResponseText(retry) {
                url(apiUrl)
                header("Referer", "http://seatwx.lib.sdu.edu.cn/")
            }

            val libMap = HashMap<String, AreaBean>()
            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt

            if (status == 1) {
                val dataObj = json.getAsJsonObject("data")
                if (dataObj.has("list")) {
                    val listElement = dataObj.get("list")
                    if (listElement.isJsonObject) {
                        val listObj = listElement.asJsonObject
                        if (listObj.has("seatinfo")) {
                            val seatinfoArray = listObj.getAsJsonArray("seatinfo")

                            // 提取顶级图书馆（parentId = 0）
                            seatinfoArray.forEach { item ->
                                val areaItem = item.asJsonObject
                                val parentId = if (areaItem.has("parentId")) {
                                    areaItem.get("parentId").asInt
                                } else {
                                    -1
                                }

                                if (parentId == 0) {
                                    val id = areaItem.get("id").asInt
                                    val name = areaItem.get("name").asString
                                    val totalCount = if (areaItem.has("TotalCount")) {
                                        areaItem.get("TotalCount").asInt
                                    } else {
                                        0
                                    }
                                    val unavailableSpace = if (areaItem.has("UnavailableSpace")) {
                                        areaItem.get("UnavailableSpace").asInt
                                    } else {
                                        0
                                    }

                                    libMap[name] = AreaBean(id, name, totalCount - unavailableSpace, totalCount)
                                    logger.debug { "从API解析到图书馆: $name (ID: $id)" }
                                }
                            }
                        }
                    }
                }
            }

            if (libMap.isEmpty()) {
                logger.warn { "API解析失败，尝试页面解析" }
                return getLibsFromPage(retry)
            }

            logger.info { "从API成功获取到 ${libMap.size} 个图书馆" }
            return libMap

        } catch (e: Exception) {
            logger.warn(e) { "从API获取图书馆信息失败，尝试页面解析" }
            return getLibsFromPage(retry)
        }
    }

    /**
     * 从页面解析图书馆信息（备用方法）
     */
    private fun getLibsFromPage(retry: Int = 0): Map<String, AreaBean> {
        try {
            val res = getProxyClient().newCallResponseText(retry) {
                url(LIB_FIRST_URL)
            }
            val libMap = HashMap<String, AreaBean>()
            val doc = Jsoup.parse(res)
            val rooms = doc.select(".x_panel > div > .rooms")

            logger.debug { "页面解析结果: 找到 ${rooms.size} 个房间元素" }

            rooms.forEach {
                val id = it.selectFirst(".seat > a")?.attr("href")
                    ?.substringAfterLast("/")
                if (id != null) {
                    val name = it.selectFirst("div:nth-child(2) > b")?.text()
                    val seats = it.selectFirst("div:nth-child(3) > b")?.text()
                    val unusedSeats = seats?.centerString("今日剩余", "，")
                    val allSeats = seats?.centerString("总量", "")
                    name?.let { libName ->
                        libMap[libName] = AreaBean(
                            id.toInt(), libName, unusedSeats?.toInt() ?: 0, allSeats?.toInt() ?: 0
                        )
                        logger.debug { "解析到图书馆: $libName (ID: $id)" }
                    }
                }
            }

            // 如果页面解析也失败，使用已知的图书馆信息
            if (libMap.isEmpty()) {
                logger.warn { "页面解析也失败，使用预设的图书馆信息" }
                return getKnownLibs()
            }

            return libMap
        } catch (e: Exception) {
            logger.error(e) { "页面解析失败，使用预设信息" }
            return getKnownLibs()
        }
    }

    /**
     * 获取已知的图书馆信息（当页面解析失败时使用）
     */
    private fun getKnownLibs(): Map<String, AreaBean> {
        return mapOf(
            "蒋震馆" to AreaBean(1, "蒋震馆", 0, 0),
            "千佛山馆" to AreaBean(12, "千佛山馆", 0, 0),
            "趵突泉馆" to AreaBean(202, "趵突泉馆", 0, 0), // 保留您的配置
            "主楼" to AreaBean(208, "主楼", 0, 0),
            "图东区" to AreaBean(209, "图东区", 0, 0),
            "电子阅览室" to AreaBean(210, "电子阅览室", 0, 0)
        )
    }

    fun getAreas(area: AreaBean?, date: String, retry: Int = 0): Map<String, AreaBean> {
        if (area == null) throw SpiderException("getAreas:无法查找到对应的区域，请检查提供的区域信息")
        val url = "$LIB_URL/api.php/v3areas?date=$date"
        logger.debug { "获取区域信息URL: $url" }
        logger.debug { "请求参数: date=$date" }

        val res = getProxyClient().newCallResponseText(retry) {
            url(url)
            header("Referer", "http://seatwx.lib.sdu.edu.cn/")
        }

        logger.debug { "API响应内容: ${res.take(500)}..." }
        val areaMap = HashMap<String, AreaBean>()
        val json = GSON.parseString(res).asJsonObject
        val status = json.get("status").asInt
        if (status != 1) {
            throw SpiderException(json.get("msg").asString)
        }



        // 检查API响应结构
        val dataObj = json.getAsJsonObject("data")
        logger.debug { "API响应数据结构: ${dataObj}" }

        val areas = if (dataObj.has("list")) {
            val listElement = dataObj.get("list")
            if (listElement.isJsonObject) {
                val listObj = listElement.asJsonObject
                if (listObj.has("seatinfo")) {
                    // 真实的API结构：data.list.seatinfo 是数组
                    val seatinfoArray = listObj.getAsJsonArray("seatinfo")
                    seatinfoArray.map { it.asJsonObject }
                } else if (listObj.has("childArea")) {
                    // 旧的API结构：data.list.childArea 是数组
                    logger.info { "使用旧的API结构（data.list.childArea为数组）" }
                    listObj.getAsJsonArray("childArea").map { it.asJsonObject }
                } else {
                    throw SpiderException("无法解析API响应结构：data.list不包含seatinfo或childArea")
                }
            } else if (listElement.isJsonArray) {
                // 座位数据的API结构：data.list 是数组，可能直接包含座位信息
                val listArray = listElement.asJsonArray.map { it.asJsonObject }

                // 检查是否是座位数据（包含area_name字段）
                if (listArray.isNotEmpty() && listArray.first().has("area_name")) {
                    logger.info { "检测到座位数据，按区域分组" }
                    // 按区域名称分组座位数据
                    val groupedByArea = listArray.groupBy {
                        it.get("area_name").asString
                    }

                    // 为每个区域创建一个虚拟的区域对象
                    groupedByArea.map { (areaName, seats) ->
                        // 创建一个代表该区域的对象
                        val areaObj = com.google.gson.JsonObject()
                        areaObj.addProperty("id", area.id) // 使用父区域的ID
                        areaObj.addProperty("name", areaName)
                        areaObj.addProperty("area_name", areaName)
                        areaObj.addProperty("TotalCount", seats.size)
                        areaObj.addProperty("UnavailableSpace", seats.count { it.get("status").asInt != 1 })
                        areaObj
                    }
                } else {
                    // 普通的区域数据
                    logger.info { "使用新的API结构（data.list为区域数组）" }
                    listArray
                }
            } else {
                throw SpiderException("无法解析API响应结构：data.list既不是对象也不是数组")
            }
        } else {
            throw SpiderException("API响应中缺少data.list字段")
        }

        areas.forEach { areaItem ->
            // 处理不同的字段名
            val id = areaItem.get("id").asInt
            val name = if (areaItem.has("area_name")) {
                areaItem.get("area_name").asString
            } else if (areaItem.has("name")) {
                areaItem.get("name").asString
            } else {
                "未知区域"
            }

            // 检查parentId，只处理与当前area相关的子区域
            val parentId = if (areaItem.has("parentId")) {
                areaItem.get("parentId").asInt
            } else {
                0
            }

            // 如果这是顶级区域（parentId=0）且与当前area匹配，或者是当前area的子区域
            val isRelevant = (parentId == 0 && id == area.id) || (parentId == area.id)

            if (isRelevant) {
                // 计算座位数量
                val allSeats = if (areaItem.has("TotalCount")) {
                    areaItem.get("TotalCount").asInt
                } else {
                    1 // 对于单个座位，默认为1
                }

                val unusedSeats = if (areaItem.has("UnavailableSpace")) {
                    allSeats - areaItem.get("UnavailableSpace").asInt
                } else if (areaItem.has("status") && areaItem.get("status").asInt == 1) {
                    1 // 空闲状态
                } else {
                    0 // 已占用
                }

                val periods: List<PeriodBean> = if (areaItem.has("area_times")) {
                    val periodsList = mutableListOf<PeriodBean>()
                    kotlin.runCatching {
                        val periodArr = areaItem.getAsJsonObject("area_times").getAsJsonObject("data")
                            .getAsJsonArray("list").asJsonArray.map { period -> period.asJsonObject }
                        periodArr.forEach { period ->
                            val periodBean = PeriodBean(
                                period.get("bookTimeId").asInt,
                                period.get("startTime").asString,
                                period.get("endTime").asString,
                                if (period.has("beginTime"))
                                    period.getAsJsonObject("beginTime").get("date").asString
                                else ""
                            )
                            periodsList.add(periodBean)
                        }
                    }
                    periodsList
                } else {
                    // 如果没有时间段信息，返回空列表
                    emptyList()
                }

                logger.debug { "解析相关区域: id=$id, name=$name, parentId=$parentId, unusedSeats=$unusedSeats, allSeats=$allSeats, periods=${periods.size}" }
                areaMap[name] = AreaBean(id, name, unusedSeats, allSeats, periods)
            } else {
                logger.debug { "跳过不相关区域: id=$id, name=$name, parentId=$parentId (当前area.id=${area.id})" }
            }
        }

        logger.debug { "成功解析 ${areaMap.size} 个区域: ${areaMap.keys.joinToString(", ")}" }
        return areaMap
    }

    fun getSeats(
        area: AreaBean?,
        date: String,
        periodIndex: Int = 0,
        retry: Int = 0
    ): Map<String, SeatBean> {
        if (area == null) throw SpiderException("getSeats:无法查找到对应的区域，请检查提供的区域信息")

        // 如果没有periods信息，使用默认的时间段
        val period = try {
            area.periods?.get(periodIndex)
        } catch (_: Exception) {
            null
        }

        val url = if (period != null) {
            "$LIB_URL/api.php/spaces_old?" +
                    "area=${area.id}&segment=${period.id}&day=$date&startTime=${period.startTime}&endTime=${period.endTime}"
        } else {
            // 如果没有period信息，使用默认参数
            val defaultStartTime = "08:00"
            val defaultEndTime = "22:30"
            logger.warn { "区域 ${area.name} 没有时间段信息，使用默认时间段 $defaultStartTime-$defaultEndTime" }
            "$LIB_URL/api.php/spaces_old?" +
                    "area=${area.id}&day=$date&startTime=$defaultStartTime&endTime=$defaultEndTime"
        }

        logger.debug { "获取座位信息URL: $url" }

        val res = getProxyClient().newCallResponseText(retry) {
            url(url)
            header(
                "Referer",
                if (period != null) {
                    "$LIB_URL/web/seat3?area=${area.id}&segment=${period.id}" +
                            "&day=$date&startTime=${period.startTime}&endTime=${period.endTime}"
                } else {
                    "$LIB_URL/web/seat3?area=${area.id}&day=$date"
                }
            )
        }

        val seatMap = HashMap<String, SeatBean>()
        val json = GSON.parseString(res).asJsonObject
        val status = json.get("status").asInt
        if (status != 1) {
            throw SpiderException("获取座位信息失败: ${json.get("msg").asString}")
        }

        val seats = json.getAsJsonObject("data").getAsJsonArray("list")
            .map { it.asJsonObject }

        seats.forEach { seatItem ->
            val id = seatItem.get("id").asInt
            val name = if (seatItem.has("name")) {
                seatItem.get("name").asString
            } else if (seatItem.has("no")) {
                seatItem.get("no").asString
            } else {
                id.toString()
            }
            val seatStatus = seatItem.get("status").asInt
            val statusName = seatItem.get("status_name").asString

            logger.debug { "解析座位: id=$id, name=$name, status=$seatStatus, statusName=$statusName" }
            seatMap[name] = SeatBean(id, name, seatStatus, statusName, area)
        }

        logger.info { "成功获取区域 ${area.name} 的 ${seatMap.size} 个座位" }
        return seatMap
    }

    /**
     * 快速获取指定区域的座位信息（使用硬编码映射）
     */
    fun getSeatsForConfiguredAreas(
        configuredAreas: Set<String>,
        date: String,
        startTime: String = "08:00",
        endTime: String = "22:30",
        retry: Int = 0
    ): Map<String, Map<String, SeatBean>> {
        val seatsByArea = mutableMapOf<String, MutableMap<String, SeatBean>>()

        configuredAreas.forEach { areaName ->
            val areaId = AREA_ID_MAPPING[areaName]
            if (areaId != null) {
                logger.debug { "获取区域 $areaName (ID: $areaId) 的座位信息" }
                try {
                    val realSeats = getRealSeatsForArea(areaId, areaName, date, startTime, endTime, retry)
                    if (realSeats != null && realSeats.isNotEmpty()) {
                        seatsByArea[areaName] = realSeats.toMutableMap()
                        logger.debug { "成功获取区域 $areaName 的座位信息: ${realSeats.size} 个座位" }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "获取区域 $areaName (ID: $areaId) 的座位信息失败" }
                }
            } else {
                logger.warn { "未找到区域 $areaName 的ID映射，将使用完整API获取" }
            }
        }

        // 如果有区域没有在映射中找到，使用原来的完整API方法
        val unmappedAreas = configuredAreas.filter { !AREA_ID_MAPPING.containsKey(it) }
        if (unmappedAreas.isNotEmpty()) {
            logger.info { "使用完整API获取未映射的区域: ${unmappedAreas.joinToString(", ")}" }
            val fullApiResult = getSeatsDirectly(202, date, startTime, endTime, retry) // 使用默认区域ID
            unmappedAreas.forEach { areaName ->
                if (fullApiResult.containsKey(areaName)) {
                    seatsByArea[areaName] = fullApiResult[areaName]!!.toMutableMap()
                }
            }
        }

        logger.info { "成功获取 ${seatsByArea.size} 个区域的座位信息" }
        return seatsByArea
    }

    /**
     * 直接从API获取座位信息（适用于新的API结构）
     */
    @Suppress("UNUSED_PARAMETER")
    fun getSeatsDirectly(
        areaId: Int,
        date: String,
        startTime: String = "08:00",
        endTime: String = "22:30",
        retry: Int = 0
    ): Map<String, Map<String, SeatBean>> {
        // 使用正确的v3areas API格式
        val url = "$LIB_URL/api.php/v3areas?date=$date"

        logger.debug { "直接获取座位信息URL: $url" }
        logger.debug { "请求参数: areaId=$areaId, date=$date" }

        val res = getProxyClient().newCallResponseText(retry) {
            url(url)
            header("Referer", "http://seatwx.lib.sdu.edu.cn/")
            header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36 MicroMessenger/8.0.58")
            header("Accept", "application/json, text/plain, */*")
            header("Accept-Language", "zh-CN,zh;q=0.9")
        }

        logger.debug { "API响应内容: ${res.take(500)}..." }

        val json = GSON.parseString(res).asJsonObject
        val status = json.get("status").asInt
        val msg = json.get("msg")?.asString ?: "未知错误"

        logger.debug { "API响应状态: status=$status, msg=$msg" }

        if (status != 1) {
            logger.error { "座位API返回错误: status=$status, msg=$msg" }
            logger.debug { "完整API响应: $res" }
            throw SpiderException("获取座位信息失败: $msg")
        }

        // 按区域名称分组座位
        val seatsByArea = mutableMapOf<String, MutableMap<String, SeatBean>>()



        // 解析v3areas API响应结构
        val dataObj = json.getAsJsonObject("data")
        if (dataObj.has("list")) {
            val listElement = dataObj.get("list")
            if (listElement.isJsonObject) {
                val listObj = listElement.asJsonObject
                if (listObj.has("seatinfo")) {
                    val seatinfoArray = listObj.getAsJsonArray("seatinfo")

                    // 获取所有可用的区域（type=1表示具体的阅览室）
                    seatinfoArray.forEach { item ->
                        val areaItem = item.asJsonObject
                        val id = areaItem.get("id").asInt
                        val name = areaItem.get("name").asString
                        val parentId = if (areaItem.has("parentId")) {
                            areaItem.get("parentId").asInt
                        } else {
                            -1
                        }
                        val type = if (areaItem.has("type")) {
                            areaItem.get("type").asInt
                        } else {
                            0
                        }
                        val isValid = if (areaItem.has("isValid")) {
                            areaItem.get("isValid").asInt
                        } else {
                            1
                        }

                        // 只处理有效的具体阅览室（type=1且isValid=1）
                        if (type == 1 && isValid == 1) {
                            logger.debug { "处理区域: $name (ID: $id), parentId: $parentId, type: $type" }

                            // 尝试获取真实的座位信息
                            val realSeats = try {
                                getRealSeatsForArea(id, name, date, startTime, endTime, retry)
                            } catch (e: Exception) {
                                logger.debug(e) { "获取区域 $name (ID: $id) 的真实座位信息失败，跳过" }
                                null
                            }

                            if (realSeats != null && realSeats.isNotEmpty()) {
                                seatsByArea[name] = realSeats.toMutableMap()
                                logger.debug { "成功获取区域 $name 的真实座位信息: ${realSeats.size} 个座位" }
                            }
                        }
                    }
                }
            }
        }

        logger.info { "成功获取 ${seatsByArea.size} 个区域的座位信息" }

        return seatsByArea
    }

    /**
     * 获取区域的真实座位信息
     */
    private fun getRealSeatsForArea(
        areaId: Int,
        areaName: String,
        date: String,
        startTime: String,
        endTime: String,
        retry: Int
    ): Map<String, SeatBean>? {
        try {
            // 首先获取时间段信息
            val periods = getAreaPeriods(areaId, date, retry)
            if (periods.isNullOrEmpty()) {
                logger.warn { "区域 $areaName (ID: $areaId) 没有可用的时间段信息" }
                return null
            }

            val period = periods.first()
            val url = "$LIB_URL/api.php/spaces_old?" +
                    "area=$areaId&segment=${period.id}&day=$date&startTime=${period.startTime}&endTime=${period.endTime}"

            logger.debug { "获取真实座位信息URL: $url" }

            val res = getProxyClient().newCallResponseText(retry) {
                url(url)
                header("Referer", "http://seatwx.lib.sdu.edu.cn/")
            }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt
            if (status != 1) {
                logger.warn { "获取座位信息失败: ${json.get("msg")?.asString}" }
                return null
            }

            val seats = mutableMapOf<String, SeatBean>()
            val seatList = json.getAsJsonObject("data").getAsJsonArray("list")

            seatList.forEach { item ->
                val seatItem = item.asJsonObject
                val id = seatItem.get("id").asInt
                val name = if (seatItem.has("name")) {
                    seatItem.get("name").asString
                } else if (seatItem.has("no")) {
                    seatItem.get("no").asString
                } else {
                    String.format("%03d", id)
                }
                val seatStatus = seatItem.get("status").asInt
                val statusName = seatItem.get("status_name").asString

                val areaBean = AreaBean(areaId, areaName, 0, 0, periods)
                val seatBean = SeatBean(id, name, seatStatus, statusName, areaBean)
                seats[name] = seatBean
            }

            return seats

        } catch (e: Exception) {
            logger.warn(e) { "获取区域 $areaName (ID: $areaId) 的真实座位信息失败" }
            return null
        }
    }

    /**
     * 获取区域的可预约时间段
     */
    fun getAreaPeriods(areaId: Int, date: String, retry: Int = 0): List<PeriodBean>? {
        try {
            val url = "$LIB_URL/api.php/areadays/$areaId"

            val res = getProxyClient().newCallResponseText(retry) {
                url(url)
                header("Referer", "http://seatwx.lib.sdu.edu.cn/")
            }

            logger.debug { "时间段API响应: ${res.take(500)}..." }

            val json = GSON.parseString(res).asJsonObject
            val status = json.get("status").asInt

            if (status != 1) {
                logger.warn { "获取时间段失败: ${json.get("msg")?.asString}" }
                return null
            }

            val periods = mutableListOf<PeriodBean>()
            val dataObj = json.getAsJsonObject("data")
            val listArray = dataObj.getAsJsonArray("list")

            logger.debug { "时间段API返回 ${listArray.size()} 个时间段项目" }
            if (listArray.size() > 0) {
                logger.debug { "第一个时间段项目结构: ${listArray.get(0)}" }
                // 特别检查day字段的结构
                val firstItem = listArray.get(0).asJsonObject
                val dayElement = firstItem.get("day")
                logger.debug { "day字段类型: ${if (dayElement.isJsonPrimitive) "字符串" else if (dayElement.isJsonObject) "对象" else "其他"}" }
                logger.debug { "day字段内容: $dayElement" }

                // 检查所有项目的字段完整性
                var validItems = 0
                var invalidItems = 0
                listArray.forEach { item ->
                    val itemObj = item.asJsonObject
                    val hasId = itemObj.has("id") && !itemObj.get("id").isJsonNull
                    val hasStartTime = itemObj.has("startTime") && !itemObj.get("startTime").isJsonNull
                    val hasEndTime = itemObj.has("endTime") && !itemObj.get("endTime").isJsonNull
                    val hasDay = itemObj.has("day") && !itemObj.get("day").isJsonNull

                    if (hasId && hasStartTime && hasEndTime && hasDay) {
                        validItems++
                    } else {
                        invalidItems++
                        logger.debug { "无效时间段项目 (缺少字段): id=$hasId, startTime=$hasStartTime, endTime=$hasEndTime, day=$hasDay, 内容: $itemObj" }
                    }
                }
                logger.debug { "时间段项目统计: 有效=$validItems, 无效=$invalidItems" }
            }

            listArray.forEach { item ->
                val periodItem = item.asJsonObject

                // 处理day字段，可能是字符串或对象格式
                val day = try {
                    val dayElement = periodItem.get("day")
                    if (dayElement == null || dayElement.isJsonNull) {
                        logger.warn { "时间段项目缺少day字段: $periodItem" }
                        return@forEach
                    }

                    when {
                        dayElement.isJsonPrimitive -> {
                            // 字符串格式: "2025-06-29"
                            dayElement.asString
                        }
                        dayElement.isJsonObject -> {
                            // 对象格式: {"date":"2025-06-29 00:00:00","timezone_type":3,"timezone":"PRC"}
                            val dayObj = dayElement.asJsonObject
                            if (dayObj.has("date")) {
                                val dateStr = dayObj.get("date").asString
                                // 提取日期部分 "2025-06-29 00:00:00" -> "2025-06-29"
                                dateStr.substring(0, 10)
                            } else {
                                logger.warn { "day对象中没有date字段: $dayObj" }
                                return@forEach
                            }
                        }
                        else -> {
                            logger.warn { "未知的day字段类型: $dayElement" }
                            return@forEach
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "解析day字段失败: ${periodItem.get("day")}" }
                    return@forEach
                }

                // 只处理指定日期的时间段
                if (day == date) {
                    try {
                        // 首先检查所有必需字段是否存在
                        val idElement = periodItem.get("id")
                        val startTimeElement = periodItem.get("startTime")
                        val endTimeElement = periodItem.get("endTime")

                        if (idElement == null || idElement.isJsonNull) {
                            logger.debug { "跳过无效时间段项目 (缺少id字段): $periodItem" }
                            return@forEach
                        }

                        if (startTimeElement == null || startTimeElement.isJsonNull) {
                            logger.debug { "跳过无效时间段项目 (缺少startTime字段): $periodItem" }
                            return@forEach
                        }

                        if (endTimeElement == null || endTimeElement.isJsonNull) {
                            logger.debug { "跳过无效时间段项目 (缺少endTime字段): $periodItem" }
                            return@forEach
                        }

                        val id = idElement.asInt

                        // 获取时间对象（已经在前面验证过非空）
                        val startTimeObj = startTimeElement.asJsonObject
                        val endTimeObj = endTimeElement.asJsonObject

                        // 安全地获取日期字符串
                        val startDateElement = startTimeObj.get("date")
                        val endDateElement = endTimeObj.get("date")

                        if (startDateElement == null || endDateElement == null ||
                            startDateElement.isJsonNull || endDateElement.isJsonNull) {
                            logger.warn { "时间对象缺少date字段: startTime=$startTimeObj, endTime=$endTimeObj" }
                            return@forEach
                        }

                        val startTime = startDateElement.asString.substring(11, 16) // 提取时间部分 "08:00"
                        val endTime = endDateElement.asString.substring(11, 16) // 提取时间部分 "22:30"

                        val periodBean = PeriodBean(
                            id,
                            startTime,
                            endTime,
                            day
                        )
                        periods.add(periodBean)

                        logger.debug { "[解析成功] 成功解析时间段: id=$id, day=$day, time=$startTime-$endTime" }
                    } catch (e: Exception) {
                        logger.warn(e) { "解析时间段项目失败: $periodItem" }
                        return@forEach
                    }
                }
            }

            if (periods.isNotEmpty()) {
                return periods
            } else {
                return null
            }

        } catch (e: Exception) {
            logger.warn(e) { "获取时间段信息失败" }
            return null
        }
    }

    /**
     * 获取预约记录
     * @param status 1：等待审核 2：预约成功 3：使用中 4：已使用 5：审核未通过 6：用户取消
     *               7：已超时 8：已关闭 9：预约开始提醒 10：迟到提醒 11：预约结束提醒
     * @param keyword 请输入预约编号、申请标题
     * @param page 页码
     */
    @Suppress("unused")
    fun getBook(status: String = "", keyword: String = "", page: Int = 1): List<IBookBean> {
        val res = getProxyClient().newCallResponseText {
            url("$LIB_URL/user/index/book/p/$page")
            postForm(mapOf(Pair("status", status), Pair("keyword", keyword)))
        }
        val doc = Jsoup.parse(res)
        val tbody = doc.select("tbody").first()
        val trs = tbody?.select("tr")
        val bookBeans = mutableListOf<IBookBean>()
        trs?.forEach {
            val id = it.attr("id").removePrefix("list_")
            val tds = it.getElementsByTag("td")
            val title = tds[1].text().trim()
            val startTime = tds[2].text().trim()
            val endTime = tds[3].text().trim()
            val curStatus = tds[4].text().trim()
            bookBeans.add(BookBean(id, title, startTime, endTime, curStatus))
        }
        return bookBeans
    }

    @Suppress("unused")
    fun getCurrentUse(userId: String): IBookBean? {
        val res = getProxyClient().newCallResponseText {
            url("$LIB_URL/api.php/currentuse?user=$userId")
            header("Referer", LIB_URL)
        }
        val json = GSON.parseString(res).asJsonObject
        val status = json.get("status").asInt
        val msg = json.get("msg").asString
        var bookBean: IBookBean? = null
        if (status == 1) {
            val bookArr = json.getAsJsonArray("data")
            if (!bookArr.isEmpty) {
                val book = bookArr[0].asJsonObject
                bookBean = CurBookBean(
                    book.get("id").asString,
                    book.get("nameMerge").asString + ":" + book.get("spaceName").asString,
                    book.getAsJsonObject("beginTime").get("date").asString,
                    book.getAsJsonObject("endTime").get("date").asString,
                    statusMap[book.get("status").asInt] ?: "未知状态",
                    book.get("statusname").asString,
                    book.get("signintime").asString,
                    book.get("lastsignintime").asString,
                    if (book.has("needBackTime"))
                        book.getAsJsonObject("needBackTime").get("date").asString
                    else ""
                )
            }
        } else {
            logger.error { msg }
        }
        return bookBean
    }
}