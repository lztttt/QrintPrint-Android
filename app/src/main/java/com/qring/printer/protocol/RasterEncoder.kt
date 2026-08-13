package com.qring.printer.protocol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import com.qring.printer.protocol.ditherToBinary
import timber.log.Timber
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream

const val DOMAIN = 0x0001
const val TAG = "RasterEncoder"

/** 图片二值化阈值，对应 Python 的 --threshold 默认值。仅 DitherMode.NONE 生效 */
const val THRESHOLD_IMAGE: Int = 128

/** 文字二值化阈值。APP 打文字用 212，比图片高很多，笔画才不会被吃掉 */
const val THRESHOLD_TEXT: Int = 212

data class RasterData(
    val data: ByteArray,
    val widthBytes: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return false
        if (other !is RasterData) return false
        return widthBytes == other.widthBytes && height == other.height && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + widthBytes
        result = 31 * result + height
        return result
    }
}

// ── 图片解码 ──────────────────────────────────────────────

/**
 * 从任意可打开的 InputStream 解码图片，并等比缩放到 384 点宽。
 *
 * 用 inSampleSize 在解码期缩放，比解码全尺寸再 scale 省内存。
 * 注意：先读一次边界再解码一次，content:// 的流不能复用，必须重新 open。
 */
private fun decodeStreamToPrintWidth(open: () -> InputStream): Bitmap {
    // 先只读尺寸
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, options) }

    val srcWidth = options.outWidth
    val srcHeight = options.outHeight
    require(srcWidth > 0 && srcHeight > 0) { "图片尺寸异常 ${srcWidth}x${srcHeight}" }

    // 计算目标高度，保持宽高比
    val targetHeight = maxOf(1, Math.round(srcHeight.toFloat() * WIDTH_DOTS / srcWidth))

    // 计算采样率
    var sampleSize = 1
    while (srcWidth / sampleSize > WIDTH_DOTS * 2) {
        sampleSize *= 2
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    val decoded = open().use { BitmapFactory.decodeStream(it, null, decodeOptions) }
        ?: throw IllegalStateException("BitmapFactory.decodeStream 返回 null")

    // 精确缩放到目标尺寸
    return if (decoded.width != WIDTH_DOTS || decoded.height != targetHeight) {
        val scaled = Bitmap.createScaledBitmap(decoded, WIDTH_DOTS, targetHeight, true)
        if (scaled != decoded) decoded.recycle()
        scaled
    } else {
        decoded
    }
}

/**
 * 从文件路径解码图片，并等比缩放到 384 点宽。
 */
fun decodeImageToPrintWidth(path: String): Bitmap {
    return decodeStreamToPrintWidth { FileInputStream(path) }
}

/**
 * 从 content:// 或 file:// URI 解码图片（相册选图走这条路）。
 */
fun decodeUriToPrintWidth(context: Context, uriString: String): Bitmap {
    val uri = Uri.parse(uriString)
    return decodeStreamToPrintWidth {
        context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开图片源")
    }
}

/**
 * 自动判断：content:// 走 ContentResolver，其余按文件路径。
 */
fun decodeSourceToPrintWidth(context: Context, source: String): Bitmap {
    return if (source.startsWith("content://") || source.startsWith("file://")) {
        decodeUriToPrintWidth(context, source)
    } else {
        decodeImageToPrintWidth(source)
    }
}

// ── 灰度转换 ──────────────────────────────────────────────

/**
 * Bitmap → 灰度图，按原尺寸读取，不动传入的位图。
 *
 * 透明像素按白色处理（alpha==0 视为不打印）。
 */
fun bitmapToGrayRaw(bitmap: Bitmap): GrayImage {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val gray = IntArray(width * height)
    for (i in 0 until width * height) {
        val pixel = pixels[i]
        val a = (pixel ushr 24) and 0xFF
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF

        // 先按白底合成 alpha，再转灰度
        val alpha = a / 255f
        val rr = r * alpha + 255 * (1 - alpha)
        val gg = g * alpha + 255 * (1 - alpha)
        val bb = b * alpha + 255 * (1 - alpha)
        gray[i] = Math.round(0.299f * rr + 0.587f * gg + 0.114f * bb)
    }
    return GrayImage(gray, width, height)
}

/**
 * Bitmap → 灰度图，并把宽度归一到 384。
 *
 * 遇到宽度 ≠ 384 时会缩放传入的 Bitmap 到目标宽。
 * 整幅打印走这条；画布里的单个元素走 bitmapToGrayRaw。
 */
fun bitmapToGray(bitmap: Bitmap): GrayImage {
    if (bitmap.width != WIDTH_DOTS && bitmap.width > 0) {
        val targetH = maxOf(1, Math.round(bitmap.height.toFloat() * WIDTH_DOTS / bitmap.width))
        val scaled = Bitmap.createScaledBitmap(bitmap, WIDTH_DOTS, targetH, true)
        val result = bitmapToGrayRaw(scaled)
        if (scaled != bitmap) scaled.recycle()
        return result
    }
    return bitmapToGrayRaw(bitmap)
}

// ── 光栅打包 ──────────────────────────────────────────────

/**
 * 二值数据 → 光栅字节（384 点宽专用）。
 *
 * 编码规则：每行 48 字节，MSB first（bit7 = 最左像素），置 1 = 黑。
 */
fun packBinaryToRaster(binary: ByteArray, width: Int, height: Int): RasterData {
    val out = ByteArray(WIDTH_BYTES * height)
    val limit = minOf(width, WIDTH_DOTS)

    for (y in 0 until height) {
        val rowBase = y * width
        val outBase = y * WIDTH_BYTES
        for (x in 0 until limit) {
            if (binary[rowBase + x].toInt() == 1) {
                out[outBase + (x ushr 3)] = (out[outBase + (x ushr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
            }
        }
    }
    return RasterData(out, WIDTH_BYTES, height)
}

/**
 * 二值数据 → 光栅字节（任意宽度，横排旋转后使用）。
 * 每行字节数 = ceil(width/8)，MSB first，置 1 = 黑。
 */
fun packBinaryToRasterArbitrary(binary: ByteArray, width: Int, height: Int): RasterData {
    val widthBytes = (width + 7) / 8
    val out = ByteArray(widthBytes * height)
    for (y in 0 until height) {
        val rowBase = y * width
        val outBase = y * widthBytes
        for (x in 0 until width) {
            if (binary[rowBase + x].toInt() == 1) {
                out[outBase + (x ushr 3)] = (out[outBase + (x ushr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
            }
        }
    }
    return RasterData(out, widthBytes, height)
}

/** 二值位图旋转：degrees 取 0/90/180/270，返回 (新位图, 新宽, 新高) */
fun rotateBinary(
    binary: ByteArray, width: Int, height: Int, degrees: Int
): Triple<ByteArray, Int, Int> {
    return when (degrees % 360) {
        180 -> {
            val out = ByteArray(width * height)
            for (i in 0 until width * height) {
                out[width * height - 1 - i] = binary[i]
            }
            Triple(out, width, height)
        }
        90 -> {
            // 顺时针 90°：新宽 = 原高，新高 = 原宽；(x,y) → (h-1-y, x)
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[x * height + (height - 1 - y)] = binary[y * width + x]
                }
            }
            Triple(out, height, width)
        }
        270 -> {
            // 逆时针 90°：新宽 = 原高，新高 = 原宽；(x,y) → (y, w-1-x)
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[(width - 1 - x) * height + y] = binary[y * width + x]
                }
            }
            Triple(out, height, width)
        }
        else -> Triple(binary, width, height)
    }
}

/** 二值位图水平翻转（左右镜像） */
fun flipBinaryHorizontal(
    binary: ByteArray, width: Int, height: Int
): ByteArray {
    val out = ByteArray(width * height)
    for (y in 0 until height) {
        val srcRow = y * width
        val dstRow = y * width
        for (x in 0 until width) {
            out[dstRow + (width - 1 - x)] = binary[srcRow + x]
        }
    }
    return out
}

/** 二值位图垂直翻转（上下镜像） */
fun flipBinaryVertical(
    binary: ByteArray, width: Int, height: Int
): ByteArray {
    val out = ByteArray(width * height)
    for (y in 0 until height) {
        val srcRow = y * width
        val dstRow = (height - 1 - y) * width
        System.arraycopy(binary, srcRow, out, dstRow, width)
    }
    return out
}

/** 二值位图反色：黑变白、白变黑（对应网页版的 invert 选项） */
fun invertBinary(
    binary: ByteArray, width: Int, height: Int
): ByteArray {
    val out = ByteArray(width * height)
    for (i in 0 until width * height) {
        out[i] = if (binary[i].toInt() == 1) 0 else 1
    }
    return out
}

/**
 * 二值位图等比缩放到指定宽度。
 * 最近邻采样，返回 (新位图, 新宽, 新高)。
 * 如果目标宽度与源宽度一致则原样返回。
 */
fun scaleBinaryToWidth(
    binary: ByteArray, width: Int, height: Int, targetWidth: Int
): Triple<ByteArray, Int, Int> {
    if (width == targetWidth || targetWidth <= 0) return Triple(binary, width, height)
    val targetH = maxOf(1, Math.round(height.toFloat() * targetWidth / width))
    val out = ByteArray(targetWidth * targetH)
    val xRatio = width.toFloat() / targetWidth
    val yRatio = height.toFloat() / targetH
    for (y in 0 until targetH) {
        val srcY = (y.toFloat() * yRatio).toInt().coerceIn(0, height - 1)
        for (x in 0 until targetWidth) {
            val srcX = (x.toFloat() * xRatio).toInt().coerceIn(0, width - 1)
            out[y * targetWidth + x] = binary[srcY * width + srcX]
        }
    }
    return Triple(out, targetWidth, targetH)
}

// ── 便捷封装 ──────────────────────────────────────────────

/** 便捷封装：Bitmap → 光栅。文字打印走这条，固定用纯阈值不抖动 */
fun bitmapToRaster(bitmap: Bitmap, threshold: Int): RasterData {
    val gray = bitmapToGray(bitmap)
    val binary = ditherToBinary(gray, DitherMode.NONE, threshold)
    return packBinaryToRaster(binary, gray.width, gray.height)
}

// ── 预览图生成 ──────────────────────────────────────────────

/**
 * 二值数据 → 可显示的 Bitmap，用于预览「实际会打印成什么样」。
 *
 * @param transparentWhite 白像素是否输出为透明。
 *   文字元素用它：白底变成透明，文字像是直接印在纸上。
 */
fun binaryToPreviewBitmap(
    binary: ByteArray, width: Int, height: Int,
    transparentWhite: Boolean = false
): Bitmap {
    val w = maxOf(1, width)
    val h = maxOf(1, height)
    val pixels = IntArray(w * h)
    for (i in 0 until w * h) {
        val black = binary.getOrElse(i) { 0 }.toInt() == 1
        val value = if (black) 0 else 255
        val alpha = if (transparentWhite && !black) 0 else 255
        pixels[i] = (alpha shl 24) or (value shl 16) or (value shl 8) or value
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

// ── 文本渲染选项 ──────────────────────────────────────────

/** 按字体族 + 粗斜体构造 Typeface；内置字体（楷体/宋体）优先，解析不了回落默认 */
private fun typefaceFor(options: TextRenderOptions): Typeface {
    return com.qring.printer.ui.common.FontList.typefaceFor(options.fontFamily, options.bold, options.italic)
}

data class TextRenderOptions(
    val fontFamily: String = "sans-serif",
    val fontSize: Float = 24f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 6f,
    val margin: Float = 8f,
    val alignment: TextAlignment = TextAlignment.LEFT
)

enum class TextAlignment {
    LEFT, CENTER, RIGHT, STRETCH
}

// ── 文本排版与渲染 ──────────────────────────────────────────

/** 拼 Canvas 的 font 简写：[italic] [bold] <size>px <family> */
private fun buildFontSpec(options: TextRenderOptions): String {
    val style = if (options.italic) "italic " else ""
    val weight = if (options.bold) "bold " else ""
    val family = if (options.fontFamily.contains(' ')) "\"${options.fontFamily}\"" else options.fontFamily
    return "$style${weight}${options.fontSize}px $family"
}

/** 按可用宽度逐字符折行。中文没有词边界，只能按字符量宽度 */
private fun wrapText(
    text: String, paint: Paint, usable: Float
): List<String> {
    val lines = mutableListOf<String>()
    val paragraphs = text.split("\n")

    for (paragraph in paragraphs) {
        if (paragraph.isEmpty()) {
            lines.add("")
            continue
        }
        var current = ""
        for (ch in paragraph) {
            val candidate = current + ch
            if (paint.measureText(candidate) <= usable) {
                current = candidate
            } else {
                lines.add(current)
                current = ch.toString()
            }
        }
        lines.add(current)
    }
    return lines
}

/**
 * 量出文本在 maxWidth 内排版后的**内容自然宽度**。
 *
 * 文字元素默认不该占满整幅 384 —— 只有一行时宽度就是这行文字 + 两边边距，
 * 多行（手动 \n 或超宽折行）时取最长一行的宽度。上限 maxWidth，不超纸宽。
 */
fun measureTextContentWidth(
    text: String, options: TextRenderOptions, maxWidth: Float
): Int {
    val width = maxOf(1f + 2 * options.margin, Math.round(maxWidth).toFloat())
    val paint = Paint().apply {
            textSize = options.fontSize * 3f // 考虑屏幕密度
            letterSpacing = options.letterSpacing
            typeface = typefaceFor(options)
        }
    val usable = width - 2 * options.margin
    val lines = wrapText(text, paint, usable)

    var widest = 0f
    for (line in lines) {
        val w = paint.measureText(line)
        if (w > widest) widest = w
    }
    val content = maxOf(options.fontSize, Math.ceil(widest.toDouble()).toFloat())
    return minOf(Math.round(width), Math.round(content + 2 * options.margin)).toInt()
}

/**
 * 文本 → 384 点宽位图，自动换行。
 *
 * 文本直接渲染到离屏 Canvas 的像素坐标，无需密度换算。
 */
fun renderTextToPixelMap(
    text: String, options: TextRenderOptions
): Bitmap = renderTextToPixelMapIn(text, options, WIDTH_DOTS.toFloat())

/**
 * 同上，但可指定排版宽度。
 *
 * 自定义画布里的文字元素不一定占满整幅宽 —— 放在 x=200 处的文字只能用剩下的 184 点，
 * 折行必须按这个宽度算，否则会排到纸外面去被裁掉。
 */
fun renderTextToPixelMapIn(
    text: String, options: TextRenderOptions, boxWidth: Float
): Bitmap {
    // 至少留一列可画
    val width = maxOf(1f + 2 * options.margin, Math.round(boxWidth).toFloat())
    val fontSizePx = options.fontSize
    val spacingPx = options.letterSpacing

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSizePx
        letterSpacing = spacingPx
        color = android.graphics.Color.BLACK
        isFakeBoldText = options.bold
        isUnderlineText = options.underline
        typeface = typefaceFor(options)
    }

    val usable = width - 2 * options.margin
    val lines = wrapText(text, paint, usable)

    val lineHeight = fontSizePx + options.lineSpacing
    val textHeight = fontSizePx + maxOf(0f, (lines.size - 1).toFloat()) * lineHeight
    val height = maxOf(1f, options.margin * 2 + textHeight).toInt()
    val widthInt = Math.round(width)

    val bitmap = Bitmap.createBitmap(widthInt, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // 铺白底
    canvas.drawColor(android.graphics.Color.WHITE)

    // 绘制文字
    paint.color = android.graphics.Color.BLACK
    paint.textAlign = Paint.Align.LEFT
    val fontMetrics = paint.fontMetrics

    for (i in lines.indices) {
        val y = options.margin + i * lineHeight - fontMetrics.ascent
        val line = lines[i]
        val lineWidth = paint.measureText(line)

        if (options.alignment == TextAlignment.STRETCH && line.isNotEmpty() && lineWidth > 0) {
            // 整行平铺：调整字距使文字均匀填满可用宽度，不拉伸字体
            // 总间隙 = usable - lineWidth，分配到 (charCount - 1) 个间距上
            // 当只有1个字符时，偏移到居中即可
            val charCount = line.length
            if (charCount > 1) {
                val extraSpace = usable - lineWidth
                // paint.letterSpacing 的单位是 em，需要除以 textSize 转换
                val extraLetterSpacing = (extraSpace / (charCount - 1)) / fontSizePx
                val stretchPaint = Paint(paint)
                stretchPaint.letterSpacing = spacingPx + extraLetterSpacing
                // 重新度量：加了字距后文字实际宽度应约为 usable
                canvas.drawText(line, options.margin, y, stretchPaint)
                // 下划线
                if (options.underline) {
                    val underlineTop = y + fontSizePx + 2
                    val underlineWeight = maxOf(1f, fontSizePx / 14f)
                    canvas.drawRect(options.margin, underlineTop, options.margin + usable, underlineTop + underlineWeight, stretchPaint)
                }
            } else {
                // 单字符居中
                val x = options.margin + (usable - lineWidth) / 2f
                canvas.drawText(line, x, y, paint)
                if (options.underline) {
                    val underlineTop = y + fontSizePx + 2
                    val underlineWeight = maxOf(1f, fontSizePx / 14f)
                    canvas.drawRect(x, underlineTop, x + lineWidth, underlineTop + underlineWeight, paint)
                }
            }
        } else {
            // 根据对齐方式计算 x 偏移
            val x: Float = when (options.alignment) {
                TextAlignment.LEFT -> options.margin
                TextAlignment.CENTER -> options.margin + (usable - lineWidth) / 2f
                TextAlignment.RIGHT -> options.margin + (usable - lineWidth)
                TextAlignment.STRETCH -> options.margin // 不会走到这里
            }
            canvas.drawText(line, x, y, paint)
            // 下划线
            if (options.underline && line.isNotEmpty()) {
                val underlineTop = y + fontSizePx + 2
                val underlineWeight = maxOf(1f, fontSizePx / 14f)
                canvas.drawRect(x, underlineTop, x + lineWidth, underlineTop + underlineWeight, paint)
            }
        }
    }

    Timber.tag(TAG).d("rendered ${lines.size} lines, height $height")
    return bitmap
}
