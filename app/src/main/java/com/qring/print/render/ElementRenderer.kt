package com.qring.print.render

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.qring.print.model.CanvasDoc
import com.qring.print.model.CanvasElement
import com.qring.print.model.ElementKind
import com.qring.print.model.TemplateElementData
import com.qring.print.model.TemplateRecord
import com.qring.print.protocol.DitherMode
import com.qring.print.protocol.GrayImage
import com.qring.print.protocol.RasterData
import com.qring.print.protocol.WIDTH_DOTS
import com.qring.print.protocol.binaryToPreviewBitmap
import com.qring.print.protocol.blitBinary
import com.qring.print.protocol.bitmapToGrayRaw
import com.qring.print.protocol.createBinaryCanvas
import com.qring.print.protocol.decodeImageToPrintWidth
import com.qring.print.protocol.ditherToBinary
import com.qring.print.protocol.measureTextContentWidth
import com.qring.print.protocol.packBinaryToRaster
import com.qring.print.protocol.renderTextToPixelMapIn
import com.qring.print.protocol.scaleGrayArea
import com.qring.print.protocol.scaleGrayNearest
import com.qring.print.protocol.squeezeRows
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
 * 解码图片成 384 宽的灰度缓存。
 */
fun loadImageGray(uri: String): GrayImage {
    val bitmap = decodeImageToPrintWidth(uri)
    val gray = bitmapToGrayRaw(bitmap)
    bitmap.recycle()
    return gray
}

// ── 单个元素 → 二值位图 ────────────────────────────────────

/**
 * 按元素当前尺寸渲染出二值位图，并同步更新屏幕预览图。
 */
fun renderElement(el: CanvasElement): Pair<ByteArray, Bitmap> {
    val targetW = maxOf(1, el.dotW)
    val targetH = maxOf(1, el.dotH)
    var binary: ByteArray

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

            // 更新元素几何
            el.dotW = gray.width
            el.dotH = gray.height
            el.aspect = if (gray.height > 0) gray.width.toFloat() / gray.height else 1f

            // 文字白底透明
            val preview = binaryToPreviewBitmap(binary, gray.width, gray.height, true)
            return Pair(binary, preview)
        }

        ElementKind.IMAGE -> {
            val source = el.sourceGray ?: throw IllegalStateException("Image not decoded")
            val scaled = scaleGrayArea(source, targetW, targetH)
            binary = ditherToBinary(scaled, el.ditherMode, 128)
            val preview = binaryToPreviewBitmap(binary, scaled.width, scaled.height, false)
            return Pair(binary, preview)
        }

        ElementKind.CODE -> {
            val codeGray = renderCodeGray(el.codeContent, el.codeTypeLabel())
            val scaled = scaleGrayNearest(codeGray, targetW, targetH)
            binary = ditherToBinary(scaled, DitherMode.NONE, 128)
            val preview = binaryToPreviewBitmap(binary, scaled.width, scaled.height, false)
            return Pair(binary, preview)
        }
    }
}

private fun CanvasElement.codeTypeLabel(): String {
    return com.qring.print.model.CODE_TYPES.getOrNull(codeTypeIndex)?.label ?: "QR Code"
}

/**
 * 单元素完整渲染入口（含解码）。
 */
fun renderElementNow(el: CanvasElement, onStage: ((String) -> Unit)? = null): Pair<ByteArray, Bitmap>? {
    onStage?.invoke("开始渲染")

    // 图片首次渲染需要解码
    if (el.kind == ElementKind.IMAGE && el.sourceGray == null && el.imageUri.isNotEmpty()) {
        onStage?.invoke("解码图片")
        val gray = loadImageGray(el.imageUri)
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
    val height = doc.contentHeight()
    val canvas = createBinaryCanvas(WIDTH_DOTS, height)

    for (el in doc.elements) {
        val bits = el.binary ?: continue
        val w = maxOf(1, el.dotW)
        val h = maxOf(1, el.dotH)
        val rows = minOf(h, bits.size / w)
        if (rows <= 0) continue
        blitBinary(canvas, WIDTH_DOTS, height, bits, w, rows, el.dotX, el.dotY)
    }

    Timber.tag(TAG).d("composed ${doc.elements.size} elements, height $height")
    return CanvasComposite(canvas, WIDTH_DOTS, height)
}

/**
 * 合成图 → 预览 Bitmap。
 */
fun compositeToBitmap(composite: CanvasComposite): Bitmap {
    return binaryToPreviewBitmap(composite.binary, composite.width, composite.height, false)
}

/**
 * 合成图 → 光栅数据（打印用）。
 */
fun compositeToRaster(composite: CanvasComposite): RasterData {
    return packBinaryToRaster(composite.binary, composite.width, composite.height)
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
        codeContent = el.codeContent,
        codeTypeIndex = el.codeTypeIndex
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
    el.codeContent = data.codeContent
    el.codeTypeIndex = data.codeTypeIndex
    return el
}
