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
import mu.KotlinLogging
import sduseat.constant.Const.statusMap
import sduseat.http.getProxyClient
import sduseat.http.newCallResponseText
import sduseat.http.postForm
import sduseat.utils.GSON
import sduseat.utils.centerString
import sduseat.utils.parseString
import org.jsoup.Jsoup

private val logger = KotlinLogging.logger {}

object Spider {

    fun getLibs(retry: Int = 0): Map<String, AreaBean> {
        val res = getProxyClient().newCallResponseText(retry) {
            url(LIB_FIRST_URL)
        }
        val libMap = HashMap<String, AreaBean>()
        val doc = Jsoup.parse(res)
        val rooms = doc.select(".x_panel > div > .rooms")
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
                }
            }
        }
        return libMap
    }

    fun getAreas(area: AreaBean?, date: String, retry: Int = 0): Map<String, AreaBean> {
        if (area == null) throw SpiderException("getAreas:无法查找到对应的区域，请检查提供的区域信息")
        val url = "$LIB_URL/api.php/v3areas/${area.id}/date/$date"
        val res = getProxyClient().newCallResponseText(retry) {
            url(url)
            header("Referer", "$LIB_URL/home/web/seat/area/${area.id}/date/$date")
        }
        val areaMap = HashMap<String, AreaBean>()
        val json = GSON.parseString(res).asJsonObject
        val status = json.get("status").asInt
        if (status != 1) {
            throw SpiderException(json.get("msg").asString)
        }
        val areas = json.getAsJsonObject("data").getAsJsonObject("list")
            .getAsJsonArray("childArea").map { it.asJsonObject }
        areas.forEach {
            val id = it.get("id").asInt
            val name = it.get("name").asString
            val allSeats = it.get("TotalCount").asInt
            val unusedSeats = allSeats - it.get("UnavailableSpace").asInt
            var periods: List<PeriodBean>? = null

            if (it.has("area_times")) {
                periods = mutableListOf()
                kotlin.runCatching {
                    val periodArr = it.getAsJsonObject("area_times").getAsJsonObject("data")
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
                        periods.add(periodBean)
                    }
                }
            }
            areaMap[name] = AreaBean(id, name, unusedSeats, allSeats, periods)
        }
        return areaMap
    }

    fun getSeats(
        area: AreaBean?,
        date: String,
        periodIndex: Int = 0,
        retry: Int = 0
    ): Map<String, SeatBean> {
        if (area == null) throw SpiderException("getSeats:无法查找到对应的区域，请检查提供的区域信息")
        val period = try {
            area.periods?.get(periodIndex)
        } catch (_: Exception) {
            null
        } ?: throw SpiderException("getSeats:无法查找到对应的时间段Period_${periodIndex}，请检查提供的时间段")
        val url = "$LIB_URL/api.php/spaces_old?" +
                "area=${area.id}&segment=${period.id}&day=$date&startTime=${period.startTime}&endTime=${period.endTime}"
        val res = getProxyClient().newCallResponseText(retry) {
            url(url)
            header(
                "Referer",
                "$LIB_URL/web/seat3?area=${area.id}&segment=${period.id}" +
                        "&day=$date&startTime=${period.startTime}&endTime=${period.endTime}"
            )
        }
        val seatMap = HashMap<String, SeatBean>()
        val json = GSON.parseString(res).asJsonObject
        val status = json.get("status").asInt
        if (status != 1) {
            throw SpiderException(json.get("msg").asString)
        }
        val seats = json.getAsJsonObject("data").getAsJsonArray("list")
            .map { it.asJsonObject }
        seats.forEach {
            val id = it.get("id").asInt
            val name = it.get("name").asString
            val seatStatus = it.get("status").asInt
            val statusName = it.get("status_name").asString
            seatMap[name] = SeatBean(id, name, seatStatus, statusName, area)
        }
        return seatMap
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