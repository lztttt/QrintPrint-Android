package com.qring.printer.protocol

import android.graphics.Bitmap
import android.graphics.Color
import timber.log.Timber

/**
 * 文档图像增强。
 *
 * 流程：
 * 1. 灰度化
 * 2. 背景归一化：用大核局部均值估计背景光照，然后 bg / orig → 补偿不均匀光照和阴影
 * 3. Sauvola 自适应二值化
 * 4. 去噪（孤立黑点去除）
 *
 * 输出：纯黑白二值图，白底黑字，适合热敏打印。
 */
object DocumentEnhancer {

    /**
     * 增强文档图像，返回二值 Bitmap（白底黑字）。
     *
     * @param source 原始彩色/灰度 Bitmap
     * @param windowSize Sauvola 窗口大小（奇数，推荐 25~51）
     * @param k Sauvola 参数（0.1~0.5，越大越敏感，推荐 0.2）
     * @param denoise 是否去除孤立噪点
     * @return 二值 Bitmap，白底黑字
     */
    fun enhance(
        source: Bitmap,
        windowSize: Int = 25,
        k: Float = 0.2f,
        denoise: Boolean = true
    ): Bitmap {
        val w = source.width
        val h = source.height

        // 1. 灰度化（批量读取像素，避免逐像素 getPixel 太慢）
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        // 2. 背景归一化
        // 用大核局部均值估计背景光照，然后 orig * 255 / bg → 补偿阴影
        val bgWindow = maxOf(windowSize * 4, 75) or 1
        val bg = computeLocalMean(gray, w, h, bgWindow)
        val normGray = IntArray(w * h)
        for (i in gray.indices) {
            val bgVal = bg[i].coerceAtLeast(1)
            // 归一化：把背景拉到 255 附近，文字保持暗
            val normalized = (gray[i].toFloat() / bgVal.toFloat() * 255f).toInt().coerceIn(0, 255)
            normGray[i] = normalized
        }

        // 3. Sauvola 自适应二值化
        // T = m * (1 + k * (s/128 - 1))
        val sw = windowSize or 1
        val mean = computeLocalMean(normGray, w, h, sw)
        val std = computeLocalStd(normGray, w, h, sw, mean)

        val binary = ByteArray(w * h)
        for (i in normGray.indices) {
            val m = mean[i]
            val s = std[i]
            val t = m * (1.0f + k * (s / 128.0f - 1.0f))
            // 1 = 黑(前景文字), 0 = 白
            binary[i] = if (normGray[i] < t) 1 else 0
        }

        // 4. 去噪
        if (denoise) {
            denoiseBinary(binary, w, h)
        }

        // 转回 Bitmap（白底黑字）
        val colors = IntArray(w * h)
        for (i in binary.indices) {
            colors[i] = if (binary[i].toInt() == 1) Color.BLACK else Color.WHITE
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(colors, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * 用积分图计算局部均值。
     */
    private fun computeLocalMean(data: IntArray, w: Int, h: Int, window: Int): IntArray {
        val half = window / 2
        val cols = w + 1
        val integral = IntArray(cols * (h + 1))
        for (y in 1..h) {
            var rowSum = 0
            for (x in 1..w) {
                rowSum += data[(y - 1) * w + (x - 1)]
                integral[y * cols + x] = integral[(y - 1) * cols + x] + rowSum
            }
        }

        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y1 = (y - half).coerceAtLeast(0)
            val y2 = (y + half).coerceAtMost(h - 1)
            for (x in 0 until w) {
                val x1 = (x - half).coerceAtLeast(0)
                val x2 = (x + half).coerceAtMost(w - 1)
                val area = (x2 - x1 + 1) * (y2 - y1 + 1)
                out[y * w + x] = (integral[(y2 + 1) * cols + (x2 + 1)] -
                        integral[y1 * cols + (x2 + 1)] -
                        integral[(y2 + 1) * cols + x1] +
                        integral[y1 * cols + x1]) / area
            }
        }
        return out
    }

    /**
     * 计算局部标准差。Var = E(X²) - E(X)²
     */
    private fun computeLocalStd(data: IntArray, w: Int, h: Int, window: Int, mean: IntArray): IntArray {
        val half = window / 2
        val cols = w + 1
        val sqData = LongArray(w * h)
        for (i in data.indices) {
            sqData[i] = data[i].toLong() * data[i]
        }

        val integralSq = LongArray(cols * (h + 1))
        for (y in 1..h) {
            var rowSum = 0L
            for (x in 1..w) {
                rowSum += sqData[(y - 1) * w + (x - 1)]
                integralSq[y * cols + x] = integralSq[(y - 1) * cols + x] + rowSum
            }
        }

        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y1 = (y - half).coerceAtLeast(0)
            val y2 = (y + half).coerceAtMost(h - 1)
            for (x in 0 until w) {
                val x1 = (x - half).coerceAtLeast(0)
                val x2 = (x + half).coerceAtMost(w - 1)
                val area = (x2 - x1 + 1) * (y2 - y1 + 1)
                val sumSq = integralSq[(y2 + 1) * cols + (x2 + 1)] -
                        integralSq[y1 * cols + (x2 + 1)] -
                        integralSq[(y2 + 1) * cols + x1] +
                        integralSq[y1 * cols + x1]
                val meanSq = sumSq / area
                val m = mean[y * w + x]
                val variance = (meanSq - m.toLong() * m).coerceAtLeast(0L)
                out[y * w + x] = Math.sqrt(variance.toDouble()).toInt()
            }
        }
        return out
    }

    /**
     * 去噪：孤立黑点（周围 8 邻域全白）变白。
     */
    private fun denoiseBinary(binary: ByteArray, w: Int, h: Int) {
        val copy = binary.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                if (copy[idx].toInt() == 1) {
                    var blackCount = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            if (copy[(y + dy) * w + (x + dx)].toInt() == 1) blackCount++
                        }
                    }
                    if (blackCount == 0) {
                        binary[idx] = 0
                    }
                }
            }
        }
    }
}
