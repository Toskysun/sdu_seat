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
 */

@file:Suppress("unused")

package sduseat

/**
 * 基础应用异常
 */
class AppException(msg: String) : Exception(msg)

/**
 * 身份验证异常
 */
class AuthException : Exception {
    constructor(msg: String) : super(msg)
    constructor(msg: String, cause: Throwable) : super(msg, cause)

    override fun fillInStackTrace(): Throwable {
        return this
    }
}

/**
 * 爬虫异常
 */
class SpiderException(msg: String) : Exception(msg) {
    override fun fillInStackTrace(): Throwable {
        return this
    }
}

/**
 * 图书馆异常
 */
class LibException(msg: String) : Exception(msg) {
    override fun fillInStackTrace(): Throwable {
        return this
    }
}