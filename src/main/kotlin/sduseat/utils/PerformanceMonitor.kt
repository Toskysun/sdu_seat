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
package sduseat.utils

import sduseat.constant.Const.logger
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

object PerformanceMonitor {
    private val metrics = ConcurrentHashMap<String, MutableList<Long>>()

    fun recordTime(operation: String, block: () -> Unit) {
        val time = measureTimeMillis(block)
        metrics.getOrPut(operation) { mutableListOf() }.add(time)
        logger.debug { "$operation took $time ms" }
    }

    fun getMetrics(): Map<String, PerformanceMetrics> {
        return metrics.mapValues { (_, times) ->
            PerformanceMetrics(
                average = times.average(),
                min = times.minOrNull() ?: 0,
                max = times.maxOrNull() ?: 0,
                count = times.size
            )
        }
    }

    fun logMetrics() {
        getMetrics().forEach { (operation, metrics) ->
            logger.info {
                """
                Performance metrics for $operation:
                - Average time: ${metrics.average.toInt()} ms
                - Min time: ${metrics.min} ms
                - Max time: ${metrics.max} ms
                - Count: ${metrics.count}
                """.trimIndent()
            }
        }
    }

    fun reset() {
        metrics.clear()
    }

    data class PerformanceMetrics(
        val average: Double,
        val min: Long,
        val max: Long,
        val count: Int
    )
} 