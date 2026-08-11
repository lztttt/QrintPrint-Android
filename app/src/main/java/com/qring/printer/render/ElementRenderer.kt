package com.qring.printer.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.qring.printer.model.CanvasDoc
import com.qring.printer.model.CanvasElement
import com.qring.printer.model.ElementKind
import com.qring.printer.model.TemplateElementData
import com.qring.printer.model.TemplateRecord
import com.qring.printer.protocol.DitherMode
import com.qring.printer.protocol.GrayImage
import com.qring.printer.protocol.RasterData
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.binaryToPreviewBitmap
import com.qring.printer.protocol.blitBinary
import com.qring.printer.protocol.bitmapToGrayRaw
import com.qring.printer.protocol.createBinaryCanvas
import com.qring.printer.protocol.decodeSourceToPrintWidth
import com.qring.printer.protocol.ditherToBinary
import com.qring.printer.protocol.measureTextContentWidth
import com.qring.printer.protocol.packBinaryToRaster
import com.qring.printer.protocol.packBinaryToRasterArbitrary
import com.qring.printer.protocol.renderTextToPixelMapIn
import com.qring.printer.protocol.flipBinaryHorizontal
import com.qring.printer.protocol.flipBinaryVertical
import com.qring.printer.protocol.invertBinary
import com.qring.printer.protocol.rotateBinary
import com.qring.printer.protocol.scaleGrayArea
import com.qring.printer.protocol.scaleGrayNearest
import com.qring.printer.protocol.squeezeRows
import timber.log.Timber
import java.io.File

const val TAG = "ElementRender"

/** 条码生成尺寸，宽高都必须是 384 */
private const val CODE_GEN_SIZE = 384
/** 一维码生成出来是方的，先压扁到这个高度当作它的「原始」形态 */
private const val ONE_D_NATURAL_HEIGHT = 140

/** 元素默认插入尺寸（点） */
const val DEFAULT_IMAGE_WIDTH = 240
const val DEFAULT_CODE_2D_SIZE = 160
const val DEFAULT_CODE_1D_WIDTH = 280

/** 一维码「自然」宽高比（w/h） */
fun codeOneDAspect(): Float = CODE_GEN_SIZE.toFloat() / ONE_D_NATURAL_HEIGHT

// ── 条码 ──────────────────────────────────────────────────

/**
 * 生成条码的原始灰度图。
 */
fun renderCodeGray(content: String, codeLabel: String): GrayImage {
    val format = when (codeLabel) {
        "QR Code" -> BarcodeFormat.QR_CODE
        "Data Matrix" -> BarcodeFormat.DATA_MATRIX
        "Aztec" -> BarcodeFormat.AZTEC
        "PDF417" -> BarcodeFormat.PDF_417
        "Code 128" -> BarcodeFormat.CODE_128
        "Code 39" -> BarcodeFormat.CODE_39
        "Code 93" -> BarcodeFormat.CODE_93
        "EAN-13" -> BarcodeFormat.EAN_13
        "EAN-8" -> BarcodeFormat.EAN_8
        "UPC-A" -> BarcodeFormat.UPC_A
        "ITF" -> BarcodeFormat.ITF
        else -> BarcodeFormat.QR_CODE
    }

    val is1D = format in listOf(
        BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93,
        BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A,
        BarcodeFormat.ITF, BarcodeFormat.CODABAR
    )

    val hints = mapOf(
        EncodeHintType.MARGIN to 2,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )

    val bitMatrix = MultiFormatWriter().encode(content, format, CODE_GEN_SIZE, CODE_GEN_SIZE, hints)

    val grayData = IntArray(CODE_GEN_SIZE * CODE_GEN_SIZE)
    for (y in 0 until CODE_GEN_SIZE) {
        for (x in 0 until CODE_GEN_SIZE) {
            grayData[y * CODE_GEN_SIZE + x] = if (bitMatrix.get(x, y)) 0 else 255
        }
    }

    val gray = GrayImage(grayData, CODE_GEN_SIZE, CODE_GEN_SIZE)
    return if (is1D) squeezeRows(gray, ONE_D_NATURAL_HEIGHT) else gray
}

// ── 图片 ──────────────────────────────────────────────────

/**
 * 解码图片成 384 宽的灰度缓存（content:// 与文件路径都能处理）。
 */
fun loadImageGray(context: Context, uri: String): GrayImage {
    val bitmap = decodeSourceToPrintWidth(context, uri)
    val gray = bitmapToGrayRaw(bitmap)
    bitmap.recycle()
    return gray
}

// ── 单个元素 → 二值位图 ────────────────────────────────────

/**
 * 按元素当前尺寸渲染出二值位图，并同步更新屏幕预览图。
 * 渲染完成后按元素的 rotation 旋转。
 */
fun renderElement(el: CanvasElement): Pair<ByteArray, Bitmap> {
    val targetW = maxOf(1, el.dotW)
    val targetH = maxOf(1, el.dotH)
    var binary: ByteArray
    var bw: Int
    var bh: Int
    var transparentWhite = false

    when (el.kind) {
        ElementKind.TEXT -> {
            // 文字不做位图缩放 —— 直接按目标宽度重新排版
            val contentW = if (el.dotW <= 0) {
                measureTextContentWidth(el.text, el.textOptions, WIDTH_DOTS.toFloat())
            } else {
                maxOf(1, el.dotW)
            }
            val bitmap = renderTextToPixelMapIn(el.text, el.textOptions, contentW.toFloat())
            val gray = bitmapToGrayRaw(bitmap)
            bitmap.recycle()
            binary = ditherToBinary(gray, DitherMode.NONE, 211) // THRESHOLD_TEXT
            bw = gray.width
            bh = gray.height
            transparentWhite = true // 文字白底透明

            // 更新元素几何（旋转前）
            el.dotW = gray.width
            el.dotH = gray.height
            el.aspect = if (gray.height > 0) gray.width.toFloat() / gray.height else 1f
        }

        ElementKind.IMAGE -> {
            val source = el.sourceGray ?: throw IllegalStateException("Image not decoded")
            val scaled = scaleGrayArea(source, targetW, targetH)
            binary = ditherToBinary(scaled, el.ditherMode, el.ditherThreshold)
            bw = scaled.width
            bh = scaled.height
        }

        ElementKind.CODE -> {
            val codeGray = renderCodeGray(el.codeContent, el.codeTypeLabel())
            val scaled = scaleGrayNearest(codeGray, targetW, targetH)
            binary = ditherToBinary(scaled, DitherMode.NONE, 128)
            bw = scaled.width
            bh = scaled.height
        }
    }

    var preview = binaryToPreviewBitmap(binary, bw, bh, transparentWhite)

    // 应用元素旋转
    if (el.rotation % 360 != 0) {
        val (rotBinary, nw, nh) = rotateBinary(binary, bw, bh, el.rotation)
        binary = rotBinary
        el.dotW = nw
        el.dotH = nh
        // 注意：不更新 el.aspect，保留旋转前的原始宽高比
        // 这样下次渲染时仍用原始比例缩放，避免反复旋转导致比例错乱
        // 旋转预览位图
        try {
            val m = android.graphics.Matrix().apply { postRotate(el.rotation.toFloat()) }
            val rotated = android.graphics.Bitmap.createBitmap(
                preview, 0, 0, preview.width, preview.height, m, true
            )
            if (rotated != preview) preview.recycle()
            preview = rotated
        } catch (e: Exception) { }
    }

    // 应用水平翻转
    if (el.flipH) {
        binary = flipBinaryHorizontal(binary, el.dotW, el.dotH)
        try {
            val m = android.graphics.Matrix().apply { preScale(-1f, 1f) }
            val flipped = android.graphics.Bitmap.createBitmap(
                preview, 0, 0, preview.width, preview.height, m, true
            )
            if (flipped != preview) preview.recycle()
            preview = flipped
        } catch (e: Exception) { }
    }

    // 应用垂直翻转
    if (el.flipV) {
        binary = flipBinaryVertical(binary, el.dotW, el.dotH)
        try {
            val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
            val flipped = android.graphics.Bitmap.createBitmap(
                preview, 0, 0, preview.width, preview.height, m, true
            )
            if (flipped != preview) preview.recycle()
            preview = flipped
        } catch (e: Exception) { }
    }

    // 应用反色
    if (el.invert) {
        binary = invertBinary(binary, el.dotW, el.dotH)
        try {
            // 预览反色：交换黑白像素
            val m = android.graphics.Matrix()
            val inverted = android.graphics.Bitmap.createBitmap(
                preview.width, preview.height, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(inverted)
            canvas.drawColor(android.graphics.Color.BLACK)
            val paint = Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix(floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                )
            }
            canvas.drawBitmap(preview, m, paint)
            preview.recycle()
            preview = inverted
        } catch (e: Exception) { }
    }

    return Pair(binary, preview)
}



private fun CanvasElement.codeTypeLabel(): String {
    return com.qring.printer.model.CODE_TYPES.getOrNull(codeTypeIndex)?.label ?: "QR Code"
}

/**
 * 单元素完整渲染入口（含解码）。
 */
fun renderElementNow(
    context: Context,
    el: CanvasElement,
    onStage: ((String) -> Unit)? = null
): Pair<ByteArray, Bitmap>? {
    onStage?.invoke("开始渲染")

    // 图片首次渲染需要解码
    if (el.kind == ElementKind.IMAGE && el.sourceGray == null && el.imageUri.isNotEmpty()) {
        onStage?.invoke("解码图片")
        val gray = loadImageGray(context, el.imageUri)
        el.sourceGray = gray

        // 新插入的图片按真实比例设尺寸
        if (!el.geometryLocked) {
            el.aspect = if (gray.height > 0) gray.width.toFloat() / gray.height else 1f
            el.dotW = DEFAULT_IMAGE_WIDTH
            el.dotH = maxOf(1, Math.round(DEFAULT_IMAGE_WIDTH.toFloat() / el.aspect))
            el.dotX = maxOf(0, Math.round((WIDTH_DOTS - el.dotW).toFloat() / 2f))
            el.geometryLocked = true
        }
    }

    return renderElement(el)
}

// ── 整幅合成 ──────────────────────────────────────────────

data class CanvasComposite(
    val binary: ByteArray,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanvasComposite) return false
        return width == other.width && height == other.height && binary.contentEquals(other.binary)
    }

    override fun hashCode(): Int {
        var result = binary.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

/**
 * 把所有元素合成成一张 384 × H 的二值图。
 */
fun composeCanvas(doc: CanvasDoc): CanvasComposite {
    val contentH = doc.contentHeight()
    // 空画布：给一张最小空白，不旋转，避免横排产生 1 像素宽的病态位图
    if (contentH <= 0) {
        return CanvasComposite(createBinaryCanvas(WIDTH_DOTS, 1), WIDTH_DOTS, 1)
    }
    val height = contentH
    val canvas = createBinaryCanvas(WIDTH_DOTS, height)

    for (el in doc.elements) {
        val bits = el.binary ?: continue
        val w = maxOf(1, el.dotW)
        val h = maxOf(1, el.dotH)
        val rows = minOf(h, bits.size / w)
        if (rows <= 0) continue
        if (doc.landscape) {
            // 横排：先把元素二值图逆时针旋转 270°（=90° CCW），
            // 这样画布整体顺时针旋转 90° 后，元素内容恢复正立（270+90=360°）
            // 预旋转后宽高互换：新宽=h，新高=w
            val (rotBits, rw, rh) = rotateBinary(bits, w, rows, 270)
            val actualRows = minOf(rh, rotBits.size / rw)
            blitBinary(canvas, WIDTH_DOTS, height, rotBits, rw, actualRows, el.dotX, el.dotY)
        } else {
            blitBinary(canvas, WIDTH_DOTS, height, bits, w, rows, el.dotX, el.dotY)
        }
    }

    Timber.tag(TAG).d("composed ${doc.elements.size} elements, height $height, landscape=${doc.landscape}")
    // 横排：整幅旋转 90°，宽高互换（H×384），用于屏幕预览
    // 打印时由调用方再旋转 90° CW 得到 384×H 送打印机
    return if (doc.landscape) {
        val (rot, nw, nh) = rotateBinary(canvas, WIDTH_DOTS, height, 90)
        CanvasComposite(rot, nw, nh)
    } else {
        CanvasComposite(canvas, WIDTH_DOTS, height)
    }
}

/**
 * 合成图 → 预览 Bitmap。
 */
fun compositeToBitmap(composite: CanvasComposite): Bitmap {
    return binaryToPreviewBitmap(composite.binary, composite.width, composite.height, false)
}

/**
 * 合成图 → 光栅数据（打印用）。支持任意宽度（横排旋转后宽度可能 ≠ 384）。
 */
fun compositeToRaster(composite: CanvasComposite): RasterData {
    return packBinaryToRasterArbitrary(composite.binary, composite.width, composite.height)
}

/**
 * 合成图 → 打印用光栅数据。
 * 横排时把 H×384 的合成图再旋转 90° CW → 384×H，
 * 确保打印机收到标准的 48 字节/行（384 点宽）数据。
 * 竖排时直接打包。
 */
fun compositeToPrintRaster(doc: CanvasDoc, composite: CanvasComposite): RasterData {
    return if (doc.landscape) {
        // 横排：合成图是 H×384（宽×高），旋转 90° CW → 384×H（宽×高）
        // 打印头固定 384 点宽，旋转后宽度正好匹配
        val (rot, nw, nh) = rotateBinary(composite.binary, composite.width, composite.height, 90)
        packBinaryToRaster(rot, nw, nh)
    } else {
        packBinaryToRaster(composite.binary, composite.width, composite.height)
    }
}

// ── 模板序列化 ──────────────────────────────────────────────

/**
 * 把 CanvasElement 序列化为 TemplateElementData。
 */
fun elementToTemplateData(el: CanvasElement): TemplateElementData {
    return TemplateElementData(
        kind = el.kind.name,
        dotX = el.dotX,
        dotY = el.dotY,
        dotW = el.dotW,
        dotH = el.dotH,
        aspect = el.aspect,
        geometryLocked = el.geometryLocked,
        text = el.text,
        textOptions = el.textOptions,
        imageUri = el.imageUri,
        ditherMode = el.ditherMode.code,
        ditherThreshold = el.ditherThreshold,
        codeContent = el.codeContent,
        codeTypeIndex = el.codeTypeIndex,
        rotation = el.rotation,
flipH = el.flipH,
flipV = el.flipV,
invert = el.invert
)
}

/**
 * 把 TemplateElementData 反序列化为 CanvasElement（不含渲染结果）。
 */
fun templateDataToElement(data: TemplateElementData): CanvasElement {
    val kind = when (data.kind) {
        "TEXT" -> ElementKind.TEXT
        "IMAGE" -> ElementKind.IMAGE
        "CODE" -> ElementKind.CODE
        else -> ElementKind.TEXT
    }
    val el = CanvasElement(kind = kind)
    el.dotX = data.dotX
    el.dotY = data.dotY
    el.dotW = data.dotW
    el.dotH = data.dotH
    el.aspect = data.aspect
    el.geometryLocked = data.geometryLocked
    el.text = data.text
    el.textOptions = data.textOptions
    el.imageUri = data.imageUri
    el.ditherMode = DitherMode.entries.getOrElse(data.ditherMode) { DitherMode.FLOYD_STEINBERG }
    el.ditherThreshold = data.ditherThreshold
    el.codeContent = data.codeContent
    el.codeTypeIndex = data.codeTypeIndex
    el.rotation = data.rotation
el.flipH = data.flipH
el.flipV = data.flipV
el.invert = data.invert
return el
}
