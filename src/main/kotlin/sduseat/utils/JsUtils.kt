/*
 * This file is part of Sdu-Seat
 * Sdu-Seat is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * Sdu-Seat is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with Sdu-Seat.  If not, see <https://www.gnu.org/licenses/>.
 * Copyright (C) 2025
 */

package sduseat.utils

import sduseat.bean.SeatBean
import sduseat.constant.Const.SCRIPT_ENGINE
import mu.KotlinLogging
import javax.script.SimpleBindings
import org.mozilla.javascript.NativeArray

private val logger = KotlinLogging.logger {}

object JsUtils {

    @Suppress("UNCHECKED_CAST")
    fun filterSeats(jsStr: String, seats: List<SeatBean>): List<SeatBean> {
        val sb = SimpleBindings()
        sb["seats"] = seats
        sb["utils"] = this
        return when (val result = SCRIPT_ENGINE.eval(jsStr, sb)) {
            is List<*> -> result.filterIsInstance<SeatBean>()
            is Array<*> -> result.filterIsInstance<SeatBean>()
            is NativeArray -> result.toArray().filterIsInstance<SeatBean>()
            else -> emptyList()
        }
    }

    fun log(msg: String) {
        logger.info { msg }
    }
}