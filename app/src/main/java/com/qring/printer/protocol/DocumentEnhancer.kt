package com.qring.printer.protocol

import android.graphics.Bitmap
import android.graphics.Color
import com.qring.printer.protocol.GrayImage
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
     * 背景归一化（光照补偿）：大核局部均值估计背景光照，orig/bg → 补偿阴影。
     * 三种自适应二值化（Sauvola / Wolf / Bradley）共用此预处理。
     */
    private fun normalizeBackground(data: IntArray, w: Int, h: Int, baseWindow: Int): IntArray {
        val bgWindow = maxOf(baseWindow * 4, 75) or 1
        val bg = computeLocalMean(data, w, h, bgWindow)
        val norm = IntArray(w * h)
        for (i in data.indices) {
            val bgVal = bg[i].coerceAtLeast(1)
            norm[i] = (data[i].toFloat() / bgVal.toFloat() * 255f).toInt().coerceIn(0, 255)
        }
        return norm
    }

    /**
     * Sauvola 自适应二值化（印刷文档标准，当前默认）。
     * T = m·(1 + k·(σ/128 − 1)) —— 用局部均值 m 与局部标准差 σ 自适应阈值，
     * 相比 Niblack 抑制了大面积空白区域的背景噪声，适合印刷体/打印体错题。
     */
    private fun sauvolaBinary(normGray: IntArray, w: Int, h: Int, windowSize: Int, k: Float): ByteArray {
        val sw = windowSize or 1
        val mean = computeLocalMean(normGray, w, h, sw)
        val std = computeLocalStd(normGray, w, h, sw, mean)
        val binary = ByteArray(w * h)
        for (i in normGray.indices) {
            val m = mean[i]
            val s = std[i]
            val t = m * (1.0f + k * (s / 128.0f - 1.0f))
            binary[i] = if (normGray[i] < t) 1 else 0
        }
        return binary
    }

    /**
     * Wolf-Jolion 自适应二值化（抑制白纸噪声，适合铅笔手写/低对比度）。
     * T = m − k·(m − M)·(1 − σ/R) —— 引入全局最小灰度 M 与动态范围 R，
     * 对大块均匀白纸区域的噪声抑制最强；对文字与背景对比弱的图（铅笔、淡印）更敏感。
     * k 论文推荐 0.3，越大越抑制背景。
     *
     * 关键：R 必须取固定值（128，灰度动态范围），不能取全局标准差 ——
     * 若 R < σ（笔画边缘局部标准差可达 60~100），(1 − σ/R) 变负导致 T > 均值，
     * 文字边缘周围一大片被判黑，即「黑色一坨 + 周围污迹」。R 固定 128 后 σ/R ≤ 1 恒成立。
     */
    private fun wolfBinary(normGray: IntArray, w: Int, h: Int, windowSize: Int, k: Float): ByteArray {
        val sw = windowSize or 1
        val mean = computeLocalMean(normGray, w, h, sw)
        val std = computeLocalStd(normGray, w, h, sw, mean)
        // 全局最小灰度 M；动态范围 R 固定 128（论文标准），保证不反号
        var minVal = 255
        for (v in normGray) if (v < minVal) minVal = v
        val R = 128.0f

        val binary = ByteArray(w * h)
        for (i in normGray.indices) {
            val m = mean[i]
            val s = std[i]
            // Wolf：T = m − k·(m − M)·(1 − σ/R)，σ/R ∈ [0,1] 恒成立
            val t = m - k * (m - minVal) * (1.0f - (s / R).coerceIn(0f, 1f))
            binary[i] = if (normGray[i] < t) 1 else 0
        }
        return binary
    }

    /**
     * Bradley 自适应二值化（仅均值、积分图 O(1)，速度最快）。
     * T = m·(1 − t/100) —— 只需局部均值，对光照渐变鲁棒，适合快速批量处理。
     * t 论文推荐 15（此处由 k 换算：t = k·100，k=0.2 → t=20）。
     */
    private fun bradleyBinary(normGray: IntArray, w: Int, h: Int, windowSize: Int, k: Float): ByteArray {
        val sw = windowSize or 1
        val mean = computeLocalMean(normGray, w, h, sw)
        val t = (k * 100f).coerceIn(1f, 50f)
        val binary = ByteArray(w * h)
        for (i in normGray.indices) {
            val m = mean[i]
            binary[i] = if (normGray[i] < m * (1.0f - t / 100f)) 1 else 0
        }
        return binary
    }

    private fun computeGlobalStd(data: IntArray): Float {
        var sum = 0L
        for (v in data) sum += v
        val mean = sum.toFloat() / maxOf(1, data.size)
        var sq = 0.0
        for (v in data) {
            val d = v - mean
            sq += d * d
        }
        return Math.sqrt(sq / maxOf(1, data.size)).toFloat()
    }

    /**
     * 对已缩放到打印宽度的灰度图直接增强，输出二值数组（1=黑前景，0=白背景）。
     *
     * mode：0 = Sauvola（默认，印刷文档标准），1 = Wolf-Jolion（白纸噪声抑制/铅笔手写），
     *       2 = Bradley（仅均值，最快）。
     * 与 [enhance] 共用同一套预处理（背景归一化 + 去噪），省去 Bitmap 往返，
     * 供 PDF / 批量等灰度管线直接复用。
     *
     * @return 尺寸与 gray 相同的 ByteArray，1=黑，0=白
     */
    fun enhanceGray(
        gray: GrayImage,
        windowSize: Int = 25,
        k: Float = 0.2f,
        denoise: Boolean = true,
        mode: Int = 0
    ): ByteArray {
        val w = gray.width
        val h = gray.height
        val normGray = normalizeBackground(gray.data, w, h, windowSize)

        val binary = when (mode) {
            1 -> wolfBinary(normGray, w, h, windowSize, k)
            2 -> bradleyBinary(normGray, w, h, windowSize, k)
            else -> sauvolaBinary(normGray, w, h, windowSize, k)
        }

        if (denoise) {
            denoiseBinary(binary, w, h)
        }
        return binary
    }

    /**
     * 增强算法 1（推荐）：高分辨率文档增强。
     *
     * 与原 [enhance]（先缩到 384 宽再二值化）不同，本函数**先在源分辨率上**做
     * 背景归一化 + Sauvola 自适应二值化，保留笔画细节，最后才由调用方
     * 抗锯齿缩放到打印宽度 —— 避免「先降采样丢失信息」导致小字糊掉/断笔。
     *
     * 为控制内存峰值（Sauvola 积分图约 8 倍像素内存），内部先把长边等比
     * 限制到 2048 再处理；2048 宽已足够还原打印级（384 宽）的全部细节。
     */
    fun enhanceHighRes(source: Bitmap, denoise: Boolean = true): Bitmap {
        // 1. 长边限制到 2048，控制内存
        var work = source
        var owned = false
        val maxEdge = 2048
        val longEdge = maxOf(source.width, source.height)
        if (longEdge > maxEdge) {
            val scale = maxEdge.toFloat() / longEdge
            val nw = maxOf(1, Math.round(source.width * scale))
            val nh = maxOf(1, Math.round(source.height * scale))
            work = Bitmap.createScaledBitmap(source, nw, nh, true)
            owned = true
        }
        try {
            val w = work.width
            val h = work.height

            // 2. 灰度化
            val pixels = IntArray(w * h)
            work.getPixels(pixels, 0, w, 0, 0, w, h)
            val gray = IntArray(w * h)
            for (i in pixels.indices) {
                val c = pixels[i]
                gray[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            }

            // 3. 背景归一化：大核局部均值估计光照（窗口约 min/8，覆盖文字间距）
            val bgWindow = maxOf(75, minOf(w, h) / 8) or 1
            val bg = computeLocalMean(gray, w, h, bgWindow)
            val normGray = IntArray(w * h)
            for (i in gray.indices) {
                val bgVal = bg[i].coerceAtLeast(1)
                normGray[i] = (gray[i].toFloat() / bgVal.toFloat() * 255f).toInt().coerceIn(0, 255)
            }

            // 4. Sauvola：窗口自适应（约 min/40，覆盖 2~3 个笔画）
            val sw = maxOf(15, minOf(w, h) / 40) or 1
            val mean = computeLocalMean(normGray, w, h, sw)
            val std = computeLocalStd(normGray, w, h, sw, mean)
            val binary = ByteArray(w * h)
            for (i in normGray.indices) {
                val m = mean[i]
                val s = std[i]
                val t = m * (1.0f + 0.2f * (s / 128.0f - 1.0f))
                binary[i] = if (normGray[i] < t) 1 else 0
            }
            if (denoise) denoiseBinary(binary, w, h)

            // 5. 输出二值 Bitmap（白底黑字，保持处理分辨率）
            val colors = IntArray(w * h)
            for (i in binary.indices) {
                colors[i] = if (binary[i].toInt() == 1) Color.BLACK else Color.WHITE
            }
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(colors, 0, w, 0, 0, w, h)
            return result
        } finally {
            if (owned) work.recycle()
        }
    }

    /**
     * 高分辨率灰度归一化（算法 1 的中间产物，不二值化）。
     *
     * 输出光照补偿后的灰度图（保持处理分辨率，长边 ≤ 2048）。
     * 调用方把它**灰度域**缩放到打印宽度后，再在 384 上做 Sauvola 二值化：
     * 缩小过程保留灰度渐变（不会把二值笔画糊成灰坨），
     * 最终二值化在打印分辨率上由 Sauvola 自适应完成 —— 小字清晰不糊、线条不锯齿。
     */
    fun enhanceHighResGray(source: Bitmap): Bitmap {
        // 1. 长边限制到 2048，控制内存
        var work = source
        var owned = false
        val maxEdge = 2048
        val longEdge = maxOf(source.width, source.height)
        if (longEdge > maxEdge) {
            val scale = maxEdge.toFloat() / longEdge
            val nw = maxOf(1, Math.round(source.width * scale))
            val nh = maxOf(1, Math.round(source.height * scale))
            work = Bitmap.createScaledBitmap(source, nw, nh, true)
            owned = true
        }
        try {
            val w = work.width
            val h = work.height

            // 2. 灰度化
            val pixels = IntArray(w * h)
            work.getPixels(pixels, 0, w, 0, 0, w, h)
            val gray = IntArray(w * h)
            for (i in pixels.indices) {
                val c = pixels[i]
                gray[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            }

            // 3. 背景归一化（光照补偿）
            val bgWindow = maxOf(75, minOf(w, h) / 8) or 1
            val bg = computeLocalMean(gray, w, h, bgWindow)
            val normGray = IntArray(w * h)
            for (i in gray.indices) {
                val bgVal = bg[i].coerceAtLeast(1)
                normGray[i] = (gray[i].toFloat() / bgVal.toFloat() * 255f).toInt().coerceIn(0, 255)
            }

            // 4. 输出灰度 Bitmap
            val colors = IntArray(w * h)
            for (i in normGray.indices) {
                val v = normGray[i]
                colors[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(colors, 0, w, 0, 0, w, h)
            return result
        } finally {
            if (owned) work.recycle()
        }
    }

    // ── 二值图后处理（P1：线条平滑）──────────────────────────

    /**
     * Bitmap → 二值数组（1=黑，0=白）。
     */
    fun bitmapToBinary(bmp: Bitmap, threshold: Int = 128): Triple<ByteArray, Int, Int> {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val binary = ByteArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val gray = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            binary[i] = if (gray < threshold) 1 else 0
        }
        return Triple(binary, w, h)
    }

    /**
     * 3×3 形态学闭运算（膨胀→腐蚀）：连接断裂笔画、填充小孔、平滑轮廓。
     * 原地修改 binary。
     */
    fun morphologyClose(binary: ByteArray, w: Int, h: Int) {
        // 膨胀：任一 3×3 邻域有黑 → 黑
        val dilated = binary.copyOf()
        for (y in 0 until h) {
            val rowBase = y * w
            for (x in 0 until w) {
                if (binary[rowBase + x].toInt() == 1) {
                    val y0 = (y - 1).coerceAtLeast(0)
                    val y1 = (y + 1).coerceAtMost(h - 1)
                    val x0 = (x - 1).coerceAtLeast(0)
                    val x1 = (x + 1).coerceAtMost(w - 1)
                    for (ny in y0..y1) {
                        val nBase = ny * w
                        for (nx in x0..x1) {
                            dilated[nBase + nx] = 1
                        }
                    }
                }
            }
        }
        // 腐蚀：3×3 邻域全黑才保留
        val closed = ByteArray(w * h)
        for (y in 1 until h - 1) {
            val rowBase = y * w
            for (x in 1 until w - 1) {
                var allBlack = true
                loop@ for (dy in -1..1) {
                    val nBase = (y + dy) * w
                    for (dx in -1..1) {
                        if (dilated[nBase + x + dx].toInt() != 1) {
                            allBlack = false
                            break@loop
                        }
                    }
                }
                if (allBlack) closed[rowBase + x] = 1
            }
        }
        System.arraycopy(closed, 0, binary, 0, binary.size)
    }

    /**
     * 移除面积小于 minArea 的连通域（小团噪点）。原地修改 binary。
     */
    fun removeSmallComponents(binary: ByteArray, w: Int, h: Int, minArea: Int) {
        val visited = ByteArray(w * h)
        val stack = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val startIdx = y * w + x
                if (binary[startIdx].toInt() != 1 || visited[startIdx].toInt() != 0) continue
                // BFS 标记连通域
                var top = 0
                stack[top++] = startIdx
                visited[startIdx] = 1
                val cells = mutableListOf<Int>()
                while (top > 0) {
                    val cur = stack[--top]
                    cells.add(cur)
                    val cx = cur % w
                    val cy = cur / w
                    val y0 = (cy - 1).coerceAtLeast(0)
                    val y1 = (cy + 1).coerceAtMost(h - 1)
                    val x0 = (cx - 1).coerceAtLeast(0)
                    val x1 = (cx + 1).coerceAtMost(w - 1)
                    for (ny in y0..y1) {
                        val nBase = ny * w
                        for (nx in x0..x1) {
                            val ni = nBase + nx
                            if (binary[ni].toInt() == 1 && visited[ni].toInt() == 0) {
                                visited[ni] = 1
                                stack[top++] = ni
                            }
                        }
                    }
                }
                if (cells.size < minArea) {
                    for (ci in cells) binary[ci] = 0
                }
            }
        }
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
