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

package sduseat

import sduseat.api.Auth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test


class Test {
    @Test
    fun testLogin() {
        // 创建Auth对象但不调用login()方法
        val auth = Auth("test_user", "test_password", "test_device_id")
        
        // 测试isExpire方法，这不需要实际的登录
        assertEquals(true, auth.isExpire())
        
        // 测试authUrl属性
        assertEquals("https://libseat.sdu.edu.cn/cas/index.php?callback=https://libseat.sdu.edu.cn/home/web/f_second", auth.authUrl)
    }
}