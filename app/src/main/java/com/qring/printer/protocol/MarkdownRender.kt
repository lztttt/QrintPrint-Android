package com.qring.printer.protocol

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.qring.printer.ui.common.FontList

/**
 * Markdown 排版与渲染（Markdown 打印 / 批量打印共用）。
 *
 * 布局（layoutMarkdown）与绘制（renderMarkdownBitmap）分离：
 * - 布局阶段完成解析 + 折行 + 测量，每行自带 lineHeight，杜绝预估高度与绘制高度不一致；
 * - 打印时可把布局结果按高度切块逐段渲染，长文档不再一次性创建超大 Bitmap；
 * - 加粗跨行：按字符流折行并保持 bold 状态，加粗片段被折行断开不会丢粗体。
 */

data class MarkdownOptions(
    val fontSize: Float = 14f,
    val lineSpacing: Float = 4f,
    val margin: Float = 8f,
    val fontFamilyIndex: Int = 0,
    val fontFamilies: List<String> = listOf("sans-serif", "serif", "monospace"),
) {
    val family: String get() = fontFamilies.getOrElse(fontFamilyIndex) { "sans-serif" }
}

/** 一条已测量、待绘制的排版行 */
data class MdMeasuredLine(
    val segments: List<Pair<String, Boolean>>, // (文本, 是否加粗)
    val fontSize: Float,
    val indent: Float,
    val prefix: String,
    val isBold: Boolean,
    val lineHeight: Float,
)

private sealed class MdLine {
    data class Header(val text: String, val level: Int) : MdLine()
    data class ListItem(val text: String, val ordered: Boolean, val number: Int = 0) : MdLine()
    data class Paragraph(val segments: List<Pair<String, Boolean>>) : MdLine()
    object Blank : MdLine()
}

private fun parseBoldSegments(text: String): List<Pair<String, Boolean>> {
    val segments = mutableListOf<Pair<String, Boolean>>()
    var i = 0
    val current = StringBuilder()
    var bold = false

    while (i < text.length) {
        if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
            if (current.isNotEmpty()) {
                segments.add(current.toString() to bold)
                current.clear()
            }
            bold = !bold
            i += 2
        } else {
            current.append(text[i])
            i++
        }
    }
    if (current.isNotEmpty()) {
        segments.add(current.toString() to bold)
    }
    return segments
}

private fun parseMarkdown(text: String): List<MdLine> {
    val lines = text.lines()
    val result = mutableListOf<MdLine>()

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            result.add(MdLine.Blank)
            continue
        }
        when {
            trimmed.startsWith("### ") -> result.add(MdLine.Header(trimmed.removePrefix("### "), 3))
            trimmed.startsWith("## ") -> result.add(MdLine.Header(trimmed.removePrefix("## "), 2))
            trimmed.startsWith("# ") -> result.add(MdLine.Header(trimmed.removePrefix("# "), 1))
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> result.add(MdLine.ListItem(trimmed.substring(2), false))
            trimmed.matches(Regex("^\\d+\\.\\s.+")) -> {
                val dotIdx = trimmed.indexOf(". ")
                val num = if (dotIdx > 0) trimmed.substring(0, dotIdx).toIntOrNull() ?: 1 else 1
                val content = if (dotIdx > 0) trimmed.substring(dotIdx + 2) else trimmed
                result.add(MdLine.ListItem(content, true, num))
            }
            else -> result.add(MdLine.Paragraph(parseBoldSegments(trimmed)))
        }
    }
    return result
}

/**
 * 带加粗状态折行：把 segments 压平成 (字符, bold) 流，逐字符折行并合并相邻同 bold 片段。
 * 解决原实现「先整行折行、再对每行单独解析加粗」导致加粗跨行丢失的问题。
 */
private fun wrapBoldSegments(
    segments: List<Pair<String, Boolean>>,
    normalTypeface: Typeface,
    boldTypeface: Typeface,
    usable: Float
): List<List<Pair<String, Boolean>>> {
    data class Ch(val ch: Char, val bold: Boolean)
    val flat = mutableListOf<Ch>()
    for ((text, bold) in segments) {
        for (ch in text) flat.add(Ch(ch, bold))
    }
    if (flat.isEmpty()) return listOf(listOf("" to false))

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun segWidth(text: String, bold: Boolean): Float {
        paint.typeface = if (bold) boldTypeface else normalTypeface
        return paint.measureText(text)
    }
    fun widthOf(line: List<Pair<String, Boolean>>): Float {
        var w = 0f
        for ((t, b) in line) w += segWidth(t, b)
        return w
    }

    val lines = mutableListOf<MutableList<Pair<String, Boolean>>>()
    var current = mutableListOf<Pair<String, Boolean>>()
    var curW = 0f

    for (ch in flat) {
        val w = segWidth(ch.ch.toString(), ch.bold)
        if (curW + w > usable && current.isNotEmpty()) {
            lines.add(current)
            current = mutableListOf()
            curW = 0f
        }
        val last = current.lastOrNull()
        if (last != null && last.second == ch.bold) {
            current[current.size - 1] = (last.first + ch.ch) to ch.bold
        } else {
            current.add(ch.ch.toString() to ch.bold)
        }
        curW += w
    }
    if (current.isNotEmpty()) lines.add(current)
    return lines
}

/** 解析 + 折行 + 测量，得到可直接绘制的行列表 */
fun layoutMarkdown(text: String, opts: MarkdownOptions): List<MdMeasuredLine> {
    val usable = WIDTH_DOTS - 2 * opts.margin
    val normalTypeface = FontList.typefaceFor(opts.family, false, false)
    val boldTypeface = FontList.typefaceFor(opts.family, true, false)
    val parsed = parseMarkdown(text)
    val out = mutableListOf<MdMeasuredLine>()

    fun emit(segments: List<Pair<String, Boolean>>, fontSize: Float, indent: Float, prefix: String, isBold: Boolean) {
        val lineHeight = fontSize + opts.lineSpacing
        out.add(MdMeasuredLine(segments, fontSize, indent, prefix, isBold, lineHeight))
    }

    for (mdLine in parsed) {
        when (mdLine) {
            is MdLine.Blank -> emit(listOf("" to false), opts.fontSize, 0f, "", false)
            is MdLine.Header -> {
                val headerSize = when (mdLine.level) {
                    1 -> opts.fontSize + 6f
                    2 -> opts.fontSize + 4f
                    else -> opts.fontSize + 2f
                }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = headerSize
                    typeface = boldTypeface
                }
                for (line in wrapText(mdLine.text, paint, usable)) {
                    emit(listOf(line to true), headerSize, 0f, "", true)
                }
            }
            is MdLine.ListItem -> {
                val prefix = if (mdLine.ordered) "${mdLine.number}. " else "• "
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = opts.fontSize
                    typeface = normalTypeface
                }
                val indent = paint.measureText(prefix)
                val wrapped = wrapText(mdLine.text, paint, usable - indent)
                for ((idx, line) in wrapped.withIndex()) {
                    val p = if (idx == 0) prefix else ""
                    emit(listOf(line to false), opts.fontSize, indent, p, false)
                }
            }
            is MdLine.Paragraph -> {
                val wrapped = wrapBoldSegments(mdLine.segments, normalTypeface, boldTypeface, usable)
                for (segLine in wrapped) {
                    emit(segLine, opts.fontSize, 0f, "", false)
                }
            }
        }
    }
    return out
}

/**
 * 渲染排版行到 Bitmap（384 点宽）。
 * 行高直接用每行的 lineHeight，首尾各留 opts.margin。
 */
fun renderMarkdownBitmap(lines: List<MdMeasuredLine>, opts: MarkdownOptions): Bitmap {
    val width = WIDTH_DOTS
    val margin = opts.margin
    val normalTypeface = FontList.typefaceFor(opts.family, false, false)
    val boldTypeface = FontList.typefaceFor(opts.family, true, false)

    var hFloat = margin * 2f
    for (l in lines) hFloat += l.lineHeight
    val totalHeight = maxOf(1, Math.round(hFloat))

    val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    var y = margin
    for (ml in lines) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ml.fontSize
            color = Color.BLACK
            typeface = if (ml.isBold) boldTypeface else normalTypeface
        }
        // 以行高为基准垂直居中（替代原来的经验公式，保证与 lineHeight 自洽）
        val fm = paint.fontMetrics
        val baseline = y + (ml.lineHeight - fm.descent + fm.ascent) / 2f - fm.ascent

        var xPos = margin
        if (ml.prefix.isNotEmpty()) {
            val prefixPaint = Paint(paint).apply { typeface = normalTypeface }
            canvas.drawText(ml.prefix, xPos, baseline, prefixPaint)
            xPos += prefixPaint.measureText(ml.prefix)
        }

        var drawX = xPos + ml.indent
        for ((text, bold) in ml.segments) {
            val segPaint = Paint(paint).apply {
                typeface = if (bold) boldTypeface else normalTypeface
                isFakeBoldText = bold
            }
            canvas.drawText(text, drawX, baseline, segPaint)
            drawX += segPaint.measureText(text)
        }

        y += ml.lineHeight
    }
    return bitmap
}
