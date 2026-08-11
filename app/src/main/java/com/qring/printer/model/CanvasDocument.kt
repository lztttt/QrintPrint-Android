package com.qring.printer.model

import android.graphics.Bitmap
import com.qring.printer.protocol.DitherMode
import com.qring.printer.protocol.GrayImage
import com.qring.printer.protocol.TextRenderOptions

/**
 * 自定义画布的文档模型。
 *
 * 坐标单位一律是**打印点**（1 点 = 1/8 mm），画布固定 384 点宽 —— 和打印头一致，
 * 屏幕上再按 显示宽度/384 缩放显示。所有几何量都存点数，
 * 这样合成时不用做任何单位换算。
 */

enum class ElementKind {
    TEXT,
    IMAGE,
    CODE
}

/** 画布底部留白（点），避免内容贴着切纸口 */
const val CANVAS_BOTTOM_PAD: Int = 16

/** 画布最小长度的可调范围 */
const val MIN_LENGTH_FLOOR: Int = 80
const val MIN_LENGTH_CEIL: Int = 1200

/**
 * 画布高度硬上限。
 * 协议里高度是 16 位字段（65535）且**没有校验**，超了会静默回绕成一个小数字，
 * 打出来是一堆乱码。真正的瓶颈其实是 ACK 超时 120s，所以这里取一个远小于两者的保守值。
 */
const val MAX_CANVAS_HEIGHT: Int = 4000

/** 元素最小尺寸（点），再小就没法拖了 */
const val MIN_ELEMENT_SIZE: Int = 24

private var nextId: Int = 1

/**
 * 画布元素。
 *
 * 几何量用 dotX/dotY/dotW/dotH —— 一是标明单位是打印点，
 * 二是避开 width/height 这类名字（会和 View 的属性撞名）。
 */
data class CanvasElement(
    val id: String = "el_${nextId++}",
    val kind: ElementKind,
    var dotX: Int = 0,
    var dotY: Int = 0,
    var dotW: Int = 0,
    var dotH: Int = 0,
    var aspect: Float = 1f,
    var geometryLocked: Boolean = false,
    var preview: Bitmap? = null,
    var binary: ByteArray? = null,
    var rendering: Boolean = false,
    // 文字元素
    var text: String = "",
    var textOptions: TextRenderOptions = TextRenderOptions(),
    // 图片元素
    var imageUri: String = "",
    var ditherMode: DitherMode = DitherMode.FLOYD_STEINBERG,
    var ditherThreshold: Int = 128,
    var sourceGray: GrayImage? = null,
    // 条码元素
    var codeContent: String = "",
    var codeTypeIndex: Int = 0,
    // 元素旋转角度：0 / 90 / 180 / 270
    var rotation: Int = 0,
    // 水平翻转
    var flipH: Boolean = false,
    // 垂直翻转
    var flipV: Boolean = false,
    // 反色
    var invert: Boolean = false
) {
    fun release() {
        preview?.recycle()
        preview = null
        binary = null
        sourceGray = null
    }
}

/** 画布文档 */
data class CanvasDoc(
    var elements: MutableList<CanvasElement> = mutableListOf(),
    var minLength: Int = 200,
    var selectedId: String = "",
    /** true = 横排（内容旋转 90° 打印） */
    var landscape: Boolean = false
) {
    fun add(element: CanvasElement) {
        elements = (elements + element).toMutableList()
        selectedId = element.id
    }

    fun remove(id: String) {
        val kept = mutableListOf<CanvasElement>()
        for (el in elements) {
            if (el.id == id) {
                el.release()
            } else {
                kept.add(el)
            }
        }
        elements = kept
        if (selectedId == id) selectedId = ""
    }

    fun toTop(id: String) {
        val idx = elements.indexOfFirst { it.id == id }
        if (idx < 0) return
        val el = elements.removeAt(idx)
        elements.add(el)
    }

    fun toBottom(id: String) {
        val idx = elements.indexOfFirst { it.id == id }
        if (idx < 0) return
        val el = elements.removeAt(idx)
        elements.add(0, el)
    }

    fun find(id: String): CanvasElement? = elements.firstOrNull { it.id == id }

    fun selected(): CanvasElement? = if (selectedId.isNotEmpty()) find(selectedId) else null

    fun releaseAll() {
        elements.forEach { it.release() }
        elements = mutableListOf()
        selectedId = ""
    }

    /**
     * 画布当前长度（点）。
     * 取「最靠下元素的底边 + 留白」和「最小长度」中的大者。
     *
     * 横排时元素内容预旋转 270°，在竖排画布上的纵向占用是 dotW 而非 dotH。
     */
    fun height(): Int {
        var bottom = 0
        for (el in elements) {
            val elBottom = if (landscape) el.dotY + el.dotW else el.dotY + el.dotH
            if (elBottom > bottom) bottom = elBottom
        }
        val fitted = if (bottom > 0) bottom + CANVAS_BOTTOM_PAD else 0
        return minOf(MAX_CANVAS_HEIGHT, maxOf(minLength, fitted))
    }

    /**
     * 内容实际高度（点）：最靠下元素的底边 + 留白，**不含最小长度**。
     *
     * 横排时元素内容预旋转 270°，在竖排画布上的纵向占用是 dotW 而非 dotH。
     */
    fun contentHeight(): Int {
        var bottom = 0
        for (el in elements) {
            val elBottom = if (landscape) el.dotY + el.dotW else el.dotY + el.dotH
            if (elBottom > bottom) bottom = elBottom
        }
        return if (bottom > 0) minOf(MAX_CANVAS_HEIGHT, bottom + CANVAS_BOTTOM_PAD) else 0
    }

    /**
     * 横排时元素内容预旋转 270°，纵向占用是 dotW 而非 dotH。
     */
    fun overflowed(): Boolean {
        var bottom = 0
        for (el in elements) {
            val elBottom = if (landscape) el.dotY + el.dotW else el.dotY + el.dotH
            if (elBottom > bottom) bottom = elBottom
        }
        return bottom + CANVAS_BOTTOM_PAD > MAX_CANVAS_HEIGHT
    }
}

/** 新元素的默认落点：横向居中，纵向落在现有内容下方 */
fun nextInsertY(doc: CanvasDoc): Int {
    var bottom = 0
    for (el in doc.elements) {
        // 横排时元素预旋转 270°，纵向占用是 dotW 而非 dotH
        val elBottom = if (doc.landscape) el.dotY + el.dotW else el.dotY + el.dotH
        if (elBottom > bottom) bottom = elBottom
    }
    return if (bottom > 0) bottom + 8 else 8
}

/** 横向居中 */
fun centeredX(dotW: Int): Int = maxOf(0, Math.round((384 - dotW).toFloat() / 2f))
