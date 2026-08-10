package com.qring.print.protocol

/**
 * 图像抖动 (dithering)。
 *
 * 热敏头是 1-bit 输出，只能打黑或不打。单纯按阈值二值化会把所有中间灰度
 * 一刀切成纯黑纯白，照片就丢光了层次。抖动通过把量化误差扩散到邻近像素，
 * 用点阵的疏密在视觉上模拟灰阶。
 *
 * 纯计算模块，不依赖 ImageKit，方便单测。
 */

enum class DitherMode(val code: Int) {
    /** 直接阈值二值化，不扩散误差。线稿/文字/二维码用这个最锐利 */
    NONE(0),
    /** Floyd-Steinberg：经典误差扩散，层次最细腻，照片首选 */
    FLOYD_STEINBERG(1),
    /** Atkinson：只扩散 6/8 误差，对比度更高、亮部更干净，早期 Mac 的做法 */
    ATKINSON(2),
    /** Bayer 4x4 有序抖动：纹理规则，图表/线稿清晰，速度快 */
    BAYER_4X4(3),
    /** Stucki：7x3 核误差扩散，层次细腻，略重一点 */
    STUCKI(4),
    /** Jarvis-Judice-Ninke：5x3 核扩散，过渡柔和 */
    JARVIS(5),
    /** Sierra Lite：轻量 3x2 核扩散，速度快、噪点少 */
    SIERRA_LITE(6);
}

data class DitherOption(
    val mode: DitherMode,
    val label: String,
    val hint: String
)

val DITHER_OPTIONS: List<DitherOption> = listOf(
    DitherOption(DitherMode.NONE, "无", "纯阈值 · 线稿/文字最锐利"),
    DitherOption(DitherMode.FLOYD_STEINBERG, "Floyd", "Floyd-Steinberg · 层次细腻，照片首选"),
    DitherOption(DitherMode.BAYER_4X4, "Bayer", "有序抖动 · 纹理规则，图表清晰"),
    DitherOption(DitherMode.ATKINSON, "Atkinson", "Atkinson · 对比度更高，亮部更干净"),
    DitherOption(DitherMode.STUCKI, "Stucki", "Stucki · 扩散充分，层次丰富"),
    DitherOption(DitherMode.JARVIS, "Jarvis", "Jarvis · 过渡柔和"),
    DitherOption(DitherMode.SIERRA_LITE, "Sierra Lite", "轻量扩散 · 快速干净"),
)

/** Bayer 4x4 阈值矩阵 */
private val BAYER_4X4_MATRIX = arrayOf(
    intArrayOf(0, 8, 2, 10),
    intArrayOf(12, 4, 14, 6),
    intArrayOf(3, 11, 1, 9),
    intArrayOf(15, 7, 13, 5),
)

/** 灰度图。data 长度 = width * height，取值 0(黑)~255(白) */
data class GrayImage(
    val data: IntArray,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GrayImage) return false
        return width == other.width && height == other.height && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

/**
 * 灰度 → 二值。返回每像素 1 字节：1 = 黑(要打印)，0 = 白。
 *
 * threshold 是量化分界点（0~255，默认 128）：
 *   - NONE：直接按 threshold 截断
 *   - Bayer：有序抖动，threshold 决定整体明暗
 *   - 误差扩散（Floyd/Atkinson/Stucki/Jarvis/Sierra）：threshold 是量化中点，
 *     调低整体偏黑、调高整体偏白，可用来补偿不同纸张/浓度下的显色差异
 */
fun ditherToBinary(gray: GrayImage, mode: DitherMode, threshold: Int): ByteArray {
    val width = gray.width
    val height = gray.height
    val total = width * height
    val out = ByteArray(total)
    val th = threshold.coerceIn(0, 255)

    if (mode == DitherMode.NONE) {
        for (i in 0 until total) {
            out[i] = if (gray.data[i] < th) 1 else 0
        }
        return out
    }

    if (mode == DitherMode.BAYER_4X4) {
        // 每个位置的量化点围绕 threshold 上下摆动 0~240，形成规则网点
        for (y in 0 until height) {
            val row = y and 3
            for (x in 0 until width) {
                val t = (th + (BAYER_4X4_MATRIX[row][x and 3] - 7) * 16).coerceIn(0, 255)
                out[y * width + x] = if (gray.data[y * width + x] < t) 1 else 0
            }
        }
        return out
    }

    // 误差扩散会把值推到 0~255 之外，必须用带符号的浮点缓冲，不能原地改
    val buffer = FloatArray(total)
    for (i in 0 until total) {
        buffer[i] = gray.data[i].toFloat()
    }

    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x
            val oldValue = buffer[index]
            val newValue = if (oldValue < th) 0f else 255f
            out[index] = if (newValue == 0f) 1 else 0
            val error = oldValue - newValue

            when (mode) {
                DitherMode.FLOYD_STEINBERG -> {
                    //        X   7/16
                    //  3/16 5/16 1/16
                    if (x + 1 < width) buffer[index + 1] += error * 7f / 16f
                    if (y + 1 < height) {
                        if (x > 0) buffer[index + width - 1] += error * 3f / 16f
                        buffer[index + width] += error * 5f / 16f
                        if (x + 1 < width) buffer[index + width + 1] += error * 1f / 16f
                    }
                }
                DitherMode.ATKINSON -> {
                    //       X   1/8  1/8
                    //  1/8 1/8  1/8
                    //       1/8
                    // 只扩散 6/8，剩下 2/8 丢弃 —— 这正是 Atkinson 对比度更高的原因
                    val share = error / 8f
                    if (x + 1 < width) buffer[index + 1] += share
                    if (x + 2 < width) buffer[index + 2] += share
                    if (y + 1 < height) {
                        if (x > 0) buffer[index + width - 1] += share
                        buffer[index + width] += share
                        if (x + 1 < width) buffer[index + width + 1] += share
                    }
                    if (y + 2 < height) buffer[index + 2 * width] += share
                }
                DitherMode.STUCKI -> {
                    //        X  8/42 4/42
                    // 2/42 4/42 8/42 4/42 2/42
                    // 1/42 2/42 4/42 2/42 1/42
                    if (x + 1 < width) buffer[index + 1] += error * 8f / 42f
                    if (x + 2 < width) buffer[index + 2] += error * 4f / 42f
                    if (y + 1 < height) {
                        if (x > 1) buffer[index + width - 2] += error * 2f / 42f
                        if (x > 0) buffer[index + width - 1] += error * 4f / 42f
                        buffer[index + width] += error * 8f / 42f
                        if (x + 1 < width) buffer[index + width + 1] += error * 4f / 42f
                        if (x + 2 < width) buffer[index + width + 2] += error * 2f / 42f
                    }
                    if (y + 2 < height) {
                        if (x > 1) buffer[index + 2 * width - 2] += error * 1f / 42f
                        if (x > 0) buffer[index + 2 * width - 1] += error * 2f / 42f
                        buffer[index + 2 * width] += error * 4f / 42f
                        if (x + 1 < width) buffer[index + 2 * width + 1] += error * 2f / 42f
                        if (x + 2 < width) buffer[index + 2 * width + 2] += error * 1f / 42f
                    }
                }
                DitherMode.JARVIS -> {
                    //        X  7/48 5/48
                    // 3/48 5/48 7/48 5/48 3/48
                    // 1/48 3/48 5/48 3/48 1/48
                    if (x + 1 < width) buffer[index + 1] += error * 7f / 48f
                    if (x + 2 < width) buffer[index + 2] += error * 5f / 48f
                    if (y + 1 < height) {
                        if (x > 1) buffer[index + width - 2] += error * 3f / 48f
                        if (x > 0) buffer[index + width - 1] += error * 5f / 48f
                        buffer[index + width] += error * 7f / 48f
                        if (x + 1 < width) buffer[index + width + 1] += error * 5f / 48f
                        if (x + 2 < width) buffer[index + width + 2] += error * 3f / 48f
                    }
                    if (y + 2 < height) {
                        if (x > 1) buffer[index + 2 * width - 2] += error * 1f / 48f
                        if (x > 0) buffer[index + 2 * width - 1] += error * 3f / 48f
                        buffer[index + 2 * width] += error * 5f / 48f
                        if (x + 1 < width) buffer[index + 2 * width + 1] += error * 3f / 48f
                        if (x + 2 < width) buffer[index + 2 * width + 2] += error * 1f / 48f
                    }
                }
                DitherMode.SIERRA_LITE -> {
                    //        X  2/4
                    // 1/4 1/4
                    if (x + 1 < width) buffer[index + 1] += error * 2f / 4f
                    if (y + 1 < height) {
                        if (x > 0) buffer[index + width - 1] += error * 1f / 4f
                        buffer[index + width] += error * 1f / 4f
                    }
                }
                DitherMode.NONE, DitherMode.BAYER_4X4 -> { /* 已在上方处理 */ }
            }
        }
    }

    return out
}
