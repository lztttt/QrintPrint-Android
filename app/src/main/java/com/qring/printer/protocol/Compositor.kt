package com.qring.printer.protocol

/**
 * 画布合成工具 —— 纯数组运算，不依赖 ImageKit，可以在任何线程调用。
 *
 * ⚠ 两套极性，别搞混：
 *   GrayImage.data  0 = 黑，255 = 白（光度值）
 *   binary          1 = 黑（打这个点），0 = 白
 * 翻转发生在 ditherToBinary 里。所以二值画布的「空白」是填 0，不是 0xFF。
 */

/** 灰度空白值（白）。缩放时越界取样回落到它，不会凭空多出黑边 */
private const val GRAY_WHITE: Int = 255

/**
 * 最近邻缩放。
 *
 * 条码专用：一维码的信息全在黑白条的**边界位置**上，
 * 任何插值都会在交界处糊出灰边，二值化后条宽就变了，直接扫不出来。
 * 宁可锯齿也不能糊。
 */
fun scaleGrayNearest(src: GrayImage, targetW: Int, targetH: Int): GrayImage {
    val w = maxOf(1, Math.round(targetW.toFloat()))
    val h = maxOf(1, Math.round(targetH.toFloat()))
    if (w == src.width && h == src.height) return src

    val out = IntArray(w * h)
    for (y in 0 until h) {
        val srcY = minOf(src.height - 1, (y.toFloat() * src.height / h).toInt())
        val srcRow = srcY * src.width
        val dstRow = y * w
        for (x in 0 until w) {
            val srcX = minOf(src.width - 1, (x.toFloat() * src.width / w).toInt())
            out[dstRow + x] = src.data[srcRow + srcX]
        }
    }
    return GrayImage(out, w, h)
}

/**
 * 面积平均缩放（box filter）。
 *
 * 图片专用：缩小时把落在同一个目标像素里的源像素取平均，保住灰阶层次，
 * 之后再跑 Floyd 扩散才有东西可抖。用最近邻的话细节直接丢光，抖出来是一片噪点。
 *
 * 放大时退化成最近邻取样（没有新信息可造），这里不做双线性 ——
 * 热敏打印最终只有黑白两色，放大后的平滑过渡在二值化时会被吃掉，不值得那个开销。
 */
fun scaleGrayArea(src: GrayImage, targetW: Int, targetH: Int): GrayImage {
    val w = maxOf(1, Math.round(targetW.toFloat()))
    val h = maxOf(1, Math.round(targetH.toFloat()))
    if (w == src.width && h == src.height) return src

    // 放大方向没有可平均的源像素，直接走最近邻
    if (w >= src.width && h >= src.height) {
        return scaleGrayNearest(src, w, h)
    }

    val out = IntArray(w * h)
    val xRatio = src.width.toFloat() / w
    val yRatio = src.height.toFloat() / h

    for (y in 0 until h) {
        val y0 = (y.toFloat() * yRatio).toInt()
        // 至少覆盖一行，否则某些比例下 y0 === y1 会导致除零
        val y1 = maxOf(y0 + 1, minOf(src.height, Math.ceil(((y + 1).toFloat() * yRatio).toDouble()).toInt()))
        val dstRow = y * w

        for (x in 0 until w) {
            val x0 = (x.toFloat() * xRatio).toInt()
            val x1 = maxOf(x0 + 1, minOf(src.width, Math.ceil(((x + 1).toFloat() * xRatio).toDouble()).toInt()))

            var sum = 0f
            var count = 0
            for (sy in y0 until y1) {
                val srcRow = sy * src.width
                for (sx in x0 until x1) {
                    sum += src.data[srcRow + sx]
                    count++
                }
            }
            out[dstRow + x] = if (count > 0) Math.round(sum / count) else GRAY_WHITE
        }
    }
    return GrayImage(out, w, h)
}

/**
 * 纵向抽行压缩。一维码生成出来是 384 的方图，打之前压扁省纸。
 *
 * 同一根竖条内每行完全相同，按最近邻抽行是无损的；
 * 取平均反而会在黑白交界处糊出灰边。
 */
fun squeezeRows(src: GrayImage, targetHeight: Int): GrayImage {
    if (targetHeight >= src.height) return src
    val out = IntArray(src.width * targetHeight)
    for (y in 0 until targetHeight) {
        val srcY = minOf(src.height - 1, (y.toFloat() * src.height / targetHeight).toInt())
        val from = srcY * src.width
        System.arraycopy(src.data, from, out, y * src.width, src.width)
    }
    return GrayImage(out, src.width, targetHeight)
}

/** 新建一张全白的二值画布（二值里 0 就是白，所以零值即可） */
fun createBinaryCanvas(width: Int, height: Int): ByteArray =
    ByteArray(maxOf(1, width) * maxOf(1, height))

/**
 * 把 src 叠到 dst 的 (originX, originY) 处，超出部分自动裁掉。
 *
 * 用**或**合并而不是覆盖：元素重叠时黑点应该保留，
 * 覆盖的话后画的元素会用自己的白底把下面的内容擦掉，而热敏打印里「白」等于不打，
 * 擦出来的是一块空洞，不是想要的效果。
 */
fun blitBinary(
    dst: ByteArray, dstW: Int, dstH: Int,
    src: ByteArray, srcW: Int, srcH: Int,
    originX: Int, originY: Int
) {
    val ox = Math.round(originX.toFloat())
    val oy = Math.round(originY.toFloat())

    // 先把要拷的范围夹到画布内，循环里就不用每个点判越界
    val startX = maxOf(0, -ox)
    val startY = maxOf(0, -oy)
    val endX = minOf(srcW, dstW - ox)
    val endY = minOf(srcH, dstH - oy)

    for (y in startY until endY) {
        val srcRow = y * srcW
        val dstRow = (y + oy) * dstW
        for (x in startX until endX) {
            if (src[srcRow + x].toInt() == 1) {
                dst[dstRow + x + ox] = 1
            }
        }
    }
}
