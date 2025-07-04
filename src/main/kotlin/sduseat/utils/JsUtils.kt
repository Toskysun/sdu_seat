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

package sduseat.utils

import sduseat.bean.SeatBean
import sduseat.constant.Const.SCRIPT_ENGINE
import io.github.oshai.kotlinlogging.KotlinLogging as KLogger
import javax.script.SimpleBindings
import org.mozilla.javascript.NativeArray

private val logger = KLogger.logger {}

@Suppress("unused")
object JsUtils {

    @Suppress("UNCHECKED_CAST", "unused")
    fun filterSeats(jsStr: String, seats: List<SeatBean>): List<SeatBean> {
        val sb = SimpleBindings()
        sb["seats"] = seats
        sb["utils"] = this
        return when (val evalResult = SCRIPT_ENGINE.eval(jsStr, sb)) {
            is List<*> -> evalResult.filterIsInstance<SeatBean>()
            is NativeArray -> evalResult.toArray().filterIsInstance<SeatBean>()
            is Array<*> -> evalResult.filterIsInstance<SeatBean>()
            else -> emptyList()
        }
    }

    @Suppress("unused")
    fun log(msg: String) {
        logger.info { msg }
    }
}