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

 /**
 * 取两个文本之间的文本值
 * @param left 文本前面
 * @param right 后面文本
 * @return 返回 String
 */
fun String.centerString(left: String?, right: String?): String {
    val startIndex = if (left.isNullOrEmpty()) {
        0
    } else {
        val leftIndex = indexOf(left)
        if (leftIndex > -1) leftIndex + left.length else 0
    }

    val endIndex = if (right.isNullOrEmpty()) {
        length
    } else {
        val rightIndex = indexOf(right, startIndex)
        if (rightIndex < 0) length else rightIndex
    }

    return substring(startIndex, endIndex)
}