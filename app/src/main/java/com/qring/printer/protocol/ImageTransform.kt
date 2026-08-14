package com.qring.printer.protocol

/**
 * 图片打印共用选项与变换链（图片打印 / PDF 打印 / 批量打印共用）。
 *
 * 变换顺序：灰度 → 旋转（纯转置）→ 等比缩放到 384 点宽 → 抖动 → 翻转/反色。
 * 旋转与缩放都发生在灰度阶段、最后才抖动：
 * 1. 旋转不损失画质；
 * 2. 无论原图宽高比如何，输出光栅宽度恒为 384 点 —— 一定占满纸宽；
 * 3. 抖动在最终分辨率上进行，不会把网点放大成马赛克。
 */

data class ImagePrintOptions(
    val ditherMode: DitherMode = DitherMode.FLOYD_STEINBERG,
    val threshold: Int = 128,
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val invert: Boolean = false,
    val contrast: Int = 0,
    val brightness: Int = 0,
    val sharpness: Int = 0,
)

/**
 * 对灰度图应用对比度、亮度、锐度调整，返回新的 GrayImage。
 *
 * - contrast: -100~100，正值增加对比度，负值降低
 * - brightness: -100~100，正值变亮，负值变暗
 * - sharpness: 0~100，0 为不锐化，值越大锐化越强
 */
fun adjustGrayImage(src: GrayImage, contrast: Int, brightness: Int, sharpness: Int): GrayImage {
    val w = src.width
    val h = src.height
    val data = src.data.copyOf()

    // 1. 对比度 + 亮度
    if (contrast != 0 || brightness != 0) {
        // contrast factor: -100→0.0, 0→1.0, 100→∞ (clip at 3.0)
        val cFactor = if (contrast >= 0) {
            1f + contrast / 100f * 2f
        } else {
            1f + contrast / 100f * 0.9f
        }.coerceIn(0.1f, 3f)
        val bOffset = brightness * 2.55f
        for (i in data.indices) {
            val v = (data[i] - 128) * cFactor + 128 + bOffset
            data[i] = v.toInt().coerceIn(0, 255)
        }
    }

    // 2. 锐化（简单的 3x3 卷积）
    if (sharpness > 0) {
        val srcCopy = data.copyOf()
        val amount = sharpness / 100f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val center = srcCopy[idx]
                val up = srcCopy[idx - w]
                val down = srcCopy[idx + w]
                val left = srcCopy[idx - 1]
                val right = srcCopy[idx + 1]
                val blurred = (up + down + left + right) / 4f
                val sharpened = center + (center - blurred) * amount
                data[idx] = sharpened.toInt().coerceIn(0, 255)
            }
        }
    }

    return GrayImage(data, w, h)
}

/**
 * 统一的变换链：灰度 → 旋转/缩放 → 二值化 → 翻转/反色。
 * 返回 (binary, width, height)，width 恒为 384。
 */
fun transformToBinary(gray: GrayImage, opts: ImagePrintOptions): Triple<ByteArray, Int, Int> {
    var g = gray
    if (opts.rotation % 360 != 0) {
        g = rotateGray(g, opts.rotation)
    }
    if (g.width != WIDTH_DOTS) {
        val targetH = maxOf(1, Math.round(g.height.toFloat() * WIDTH_DOTS / g.width))
        g = scaleGrayArea(g, WIDTH_DOTS, targetH)
    }
    var w = g.width
    var h = g.height
    var binary = ditherToBinary(g, opts.ditherMode, opts.threshold)
    if (opts.flipH) binary = flipBinaryHorizontal(binary, w, h)
    if (opts.flipV) binary = flipBinaryVertical(binary, w, h)
    if (opts.invert) binary = invertBinary(binary, w, h)
    return Triple(binary, w, h)
}

/**
 * 文档增强变换链：灰度 → 旋转/缩放 → Sauvola 文档增强 → 翻转/反色。
 * 输出 width 恒为 384。适合文档类（PDF / 扫描件 / 文字照片）打印。
 */
fun enhanceToBinary(gray: GrayImage, opts: ImagePrintOptions): Triple<ByteArray, Int, Int> {
    var g = gray
    if (opts.rotation % 360 != 0) {
        g = rotateGray(g, opts.rotation)
    }
    if (g.width != WIDTH_DOTS) {
        val targetH = maxOf(1, Math.round(g.height.toFloat() * WIDTH_DOTS / g.width))
        g = scaleGrayArea(g, WIDTH_DOTS, targetH)
    }
    var w = g.width
    var h = g.height
    var binary = DocumentEnhancer.enhanceGray(g, windowSize = 25, k = 0.2f, denoise = true)
    if (opts.flipH) binary = flipBinaryHorizontal(binary, w, h)
    if (opts.flipV) binary = flipBinaryVertical(binary, w, h)
    if (opts.invert) binary = invertBinary(binary, w, h)
    return Triple(binary, w, h)
}
