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



/**
 * 认证基类
 * 简化版本，只保留微信OAuth认证所需的基本功能
 */
abstract class IAuth(
    open val userid: String,
    val password: String,
    val deviceId: String,
    val retry: Int = 0
) {
    abstract val authUrl: String

    var accessToken: String = ""
    var name: String = ""
    protected var expire: String? = null



    abstract fun login()
    abstract fun isExpire(): Boolean
}