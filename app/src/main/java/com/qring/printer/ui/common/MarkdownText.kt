package com.qring.printer.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qring.printer.ui.theme.QringPalette

/**
 * 轻量级 Markdown 渲染组件。
 *
 * 支持：
 * - # / ## / ### 标题
 * - - / * 无序列表
 * - 1. 有序列表
 * - **粗体**
 * - 普通段落
 * - 空行作为段落间距
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseFontSize: Int = 12,
    baseColor: androidx.compose.ui.graphics.Color = QringPalette.textSecondary
) {
    val lines = markdown.lines()
    Column(modifier = modifier) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            if (line.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                i++
                continue
            }

            when {
                line.startsWith("### ") -> {
                    Text(
                        text = parseInlineBold(line.removePrefix("### ")),
                        fontSize = (baseFontSize + 1).sp,
                        fontWeight = FontWeight.Bold,
                        color = baseColor
                    )
                }
                line.startsWith("## ") -> {
                    Text(
                        text = parseInlineBold(line.removePrefix("## ")),
                        fontSize = (baseFontSize + 2).sp,
                        fontWeight = FontWeight.Bold,
                        color = baseColor
                    )
                }
                line.startsWith("# ") -> {
                    Text(
                        text = parseInlineBold(line.removePrefix("# ")),
                        fontSize = (baseFontSize + 3).sp,
                        fontWeight = FontWeight.Bold,
                        color = baseColor
                    )
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    val content = line.substring(2)
                    Text(
                        text = parseInlineBold("• $content"),
                        fontSize = baseFontSize.sp,
                        color = baseColor
                    )
                }
                line.matches(Regex("^\\d+\\.\\s.+")) -> {
                    val dotIndex = line.indexOf(". ")
                    val content = if (dotIndex > 0) line.substring(dotIndex + 2) else line
                    val num = if (dotIndex > 0) line.substring(0, dotIndex) else ""
                    Text(
                        text = parseInlineBold("$num. $content"),
                        fontSize = baseFontSize.sp,
                        color = baseColor
                    )
                }
                else -> {
                    Text(
                        text = parseInlineBold(line),
                        fontSize = baseFontSize.sp,
                        color = baseColor
                    )
                }
            }
            i++
        }
    }
}

/**
 * 解析 **粗体** 标记，返回带样式的 AnnotatedString。
 */
private fun parseInlineBold(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end >= 0) {
                    val boldText = text.substring(i + 2, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(boldText)
                    }
                    i = end + 2
                } else {
                    append(text[i])
                    i++
                }
            } else {
                append(text[i])
                i++
            }
        }
    }
}
