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

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.client.j2se.MatrixToImageWriter
import io.github.oshai.kotlinlogging.KotlinLogging as KLogger
import java.io.File
import java.nio.file.Paths

private val logger = KLogger.logger {}

object QRCodeUtils {
    
    /**
     * 生成ASCII二维码并打印到控制台
     * @param text 要编码的文本
     * @param size 二维码大小（默认17，平衡大小和正方形比例）
     */
    fun generateASCIIQRCode(text: String, size: Int = 17): String {
        return try {
            val qrCodeWriter = QRCodeWriter()
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1
            )
            
            val bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, size, size, hints)

            // 添加调试信息
            logger.debug { "请求尺寸: ${size}x${size}, 实际生成: ${bitMatrix.width}x${bitMatrix.height}" }

            convertBitMatrixToASCII(bitMatrix)
        } catch (e: WriterException) {
            logger.error(e) { "生成二维码失败" }
            "二维码生成失败：${e.message}"
        }
    }
    
    /**
     * 将BitMatrix转换为ASCII字符
     * 使用双字符确保正方形比例，但控制总体大小
     */
    private fun convertBitMatrixToASCII(bitMatrix: BitMatrix): String {
        val result = StringBuilder()
        val width = bitMatrix.width
        val height = bitMatrix.height

        // 添加顶部边框（双字符宽度）
        result.append("+").append("-".repeat(width * 2)).append("+\n")

        for (y in 0 until height) {
            result.append("|")
            for (x in 0 until width) {
                // 使用双字符确保正方形比例
                if (bitMatrix[x, y]) {
                    result.append("##")  // 黑色方块（双字符）
                } else {
                    result.append("  ")  // 白色方块（双字符）
                }
            }
            result.append("|\n")
        }

        // 添加底部边框（双字符宽度）
        result.append("+").append("-".repeat(width * 2)).append("+\n")

        return result.toString()
    }
    
    /**
     * 生成简化版ASCII二维码（更小尺寸，适合小屏幕）
     */
    fun generateCompactASCIIQRCode(text: String): String {
        return try {
            val qrCodeWriter = QRCodeWriter()
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 0
            )
            
            val bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, 21, 21, hints)
            convertBitMatrixToCompactASCII(bitMatrix)
        } catch (e: WriterException) {
            logger.error(e) { "生成紧凑二维码失败" }
            "二维码生成失败：${e.message}"
        }
    }
    
    /**
     * 将BitMatrix转换为紧凑ASCII字符
     */
    private fun convertBitMatrixToCompactASCII(bitMatrix: BitMatrix): String {
        val result = StringBuilder()
        val width = bitMatrix.width
        val height = bitMatrix.height
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (bitMatrix[x, y]) {
                    result.append("#")  // 黑色方块
                } else {
                    result.append(" ")  // 白色方块
                }
            }
            result.append("\n")
        }
        
        return result.toString()
    }
    
    /**
     * 生成二维码图片文件
     * @param text 要编码的文本
     * @param filePath 保存的文件路径
     * @param size 二维码大小（像素）
     * @return 是否生成成功
     */
    fun generateQRCodeImage(text: String, filePath: String, size: Int = 300): Boolean {
        return try {
            val qrCodeWriter = QRCodeWriter()
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1
            )

            val bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val path = Paths.get(filePath)

            // 确保目录存在
            path.parent?.let { parentPath ->
                File(parentPath.toString()).mkdirs()
            }

            MatrixToImageWriter.writeToPath(bitMatrix, "PNG", path)
            logger.debug { "二维码图片已生成：$filePath" }
            true
        } catch (e: Exception) {
            logger.error(e) { "生成二维码图片失败：$filePath" }
            false
        }
    }

    /**
     * 生成二维码图片并显示说明
     */
    fun generateQRCodeWithInstructions(url: String) {
        try {
            val fileName = "wechat_login_qr.png"
            val currentDir = System.getProperty("user.dir")
            val filePath = "$currentDir${File.separator}$fileName"

            if (generateQRCodeImage(url, filePath)) {
                println("二维码已生成：$fileName")
                println("请用微信扫描二维码完成登录")
            } else {
                println("二维码生成失败，请手动登录：$url")
            }

        } catch (e: Exception) {
            logger.error(e) { "生成二维码失败" }
            // 降级到文本模式
            println("请在微信中打开：$url")
        }
    }

    /**
     * 打印带说明的二维码（保持向后兼容）
     * @deprecated 使用 generateQRCodeWithInstructions 替代
     */
    @Deprecated("使用 generateQRCodeWithInstructions 替代", ReplaceWith("generateQRCodeWithInstructions(url)"))
    fun printQRCodeWithInstructions(url: String) {
        generateQRCodeWithInstructions(url)
    }
}
