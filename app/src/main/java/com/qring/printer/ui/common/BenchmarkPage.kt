package com.qring.printer.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.GrayImage
import com.qring.printer.protocol.DitherMode
import com.qring.printer.protocol.ditherToBinary
import com.qring.printer.protocol.packBinaryToRaster
import com.qring.printer.protocol.bitmapToRaster
import com.qring.printer.ui.theme.ONLINE
import com.qring.printer.ui.theme.QringPalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 基准测试页渲染。
 *
 * 内容：标题、版本信息、浓度梯度条、字号阶梯、分辨率测试（1pt 线条交替）、
 * 灰度渐变抖动测试、整体外框、时间戳。
 *
 * 返回 Bitmap（白底黑字，384 点宽）。
 */
fun renderBenchmarkBitmap(
    versionName: String,
    timestamp: String
): Bitmap {
    val width = WIDTH_DOTS
    val margin = 12
    val usable = width - 2 * margin

    val sections = mutableListOf<BenchmarkSection>()

    // 标题
    sections.add(BenchmarkSection.title("QringPrint 基准测试页", 20f))
    sections.add(BenchmarkSection.text("v$versionName · 384 dots · 58mm", 11f))
    sections.add(BenchmarkSection.blank(8f))

    // 浓度梯度条
    sections.add(BenchmarkSection.text("浓度梯度", 13f, bold = true))
    sections.add(BenchmarkSection.gradient)
    sections.add(BenchmarkSection.blank(8f))

    // 字号阶梯
    sections.add(BenchmarkSection.text("字号阶梯", 13f, bold = true))
    sections.add(BenchmarkSection.text("12pt — QringPrint 基准测试", 12f))
    sections.add(BenchmarkSection.text("16pt — QringPrint 基准测试", 16f))
    sections.add(BenchmarkSection.text("20pt — QringPrint 基准", 20f))
    sections.add(BenchmarkSection.text("24pt — 基准测试", 24f))
    sections.add(BenchmarkSection.text("28pt — 测试", 28f))
    sections.add(BenchmarkSection.blank(8f))

    // 分辨率测试：1pt 竖线 + 1pt 间隙，交替排列
    sections.add(BenchmarkSection.text("分辨率测试 — 竖线 (1pt 线 / 1pt 间隔)", 13f, bold = true))
    sections.add(BenchmarkSection.vResolution)
    sections.add(BenchmarkSection.blank(4f))
    sections.add(BenchmarkSection.text("分辨率测试 — 横线 (1pt 线 / 1pt 间隔)", 13f, bold = true))
    sections.add(BenchmarkSection.hResolution)
    sections.add(BenchmarkSection.blank(8f))

    // 灰度渐变抖动测试
    sections.add(BenchmarkSection.text("灰度渐变 (Floyd-Steinberg 抖动)", 13f, bold = true))
    sections.add(BenchmarkSection.gradientDither)
    sections.add(BenchmarkSection.blank(8f))

    // 时间戳
    sections.add(BenchmarkSection.text("打印时间: $timestamp", 10f))

    // 计算总高度
    var totalHeight = 0
    for (s in sections) {
        totalHeight += s.measureHeight(usable)
    }
    totalHeight = (totalHeight + 2 * margin).coerceAtLeast(1)

    // 绘制
    val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    // 先画整体外框
    val framePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    canvas.drawRect(0.5f, 0.5f, (width - 0.5f), (totalHeight - 0.5f), framePaint)

    // 画各 section
    var y = margin
    for (section in sections) {
        y = section.draw(canvas, y, width, margin, usable)
    }

    return bitmap
}

private sealed class BenchmarkSection {
    data class title(val text: String, val size: Float) : BenchmarkSection()
    data class text(val text: String, val size: Float, val bold: Boolean = false) : BenchmarkSection()
    data class blank(val height: Float) : BenchmarkSection()
    object gradient : BenchmarkSection()
    object vResolution : BenchmarkSection()
    object hResolution : BenchmarkSection()
    object gradientDither : BenchmarkSection()

    fun measureHeight(usable: Int): Int {
        return when (this) {
            is title -> {
                val paint = Paint().apply { textSize = size; typeface = Typeface.DEFAULT_BOLD }
                val lines = wrapBenchmark(text, paint, usable.toFloat())
                (lines.size * (size + 4f) + 8f).toInt()
            }
            is text -> {
                val paint = Paint().apply { textSize = size; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
                val lines = wrapBenchmark(this.text, paint, usable.toFloat())
                (lines.size * (size + 4f) + 4f).toInt()
            }
            is blank -> height.toInt()
            is gradient -> 36
            is vResolution -> 60
            is hResolution -> 40
            is gradientDither -> 80
        }
    }

    fun draw(canvas: Canvas, startY: Int, width: Int, margin: Int, usable: Int): Int {
        var y = startY
        when (this) {
            is title -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size
                    color = Color.BLACK
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }
                val lines = wrapBenchmark(text, paint, usable.toFloat())
                val lineHeight = size + 4f
                for (line in lines) {
                    val baseline = y + size - paint.fontMetrics.ascent * 0.2f
                    canvas.drawText(line, width / 2f, baseline, paint)
                    y += lineHeight.toInt()
                }
                y += 8
            }
            is text -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size
                    color = Color.BLACK
                    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    textAlign = Paint.Align.LEFT
                }
                val lines = wrapBenchmark(this.text, paint, usable.toFloat())
                val lineHeight = size + 4f
                for (line in lines) {
                    val baseline = y + size - paint.fontMetrics.ascent * 0.2f
                    canvas.drawText(line, margin.toFloat(), baseline, paint)
                    y += lineHeight.toInt()
                }
                y += 4
            }
            is blank -> y += height.toInt()
            is gradient -> {
                // 5 级灰度条
                val barWidth = usable / 5
                val barHeight = 24
                val labels = listOf("100%", "75%", "50%", "25%", "0%")
                val grayValues = intArrayOf(0, 64, 128, 192, 255)
                for (i in 0 until 5) {
                    val left = margin + i * barWidth
                    val grayPaint = Paint().apply {
                        color = Color.rgb(grayValues[i], grayValues[i], grayValues[i])
                    }
                    canvas.drawRect(
                        left.toFloat(), y.toFloat(),
                        (left + barWidth - 2).toFloat(), (y + barHeight).toFloat(),
                        grayPaint
                    )
                    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 9f
                        color = Color.BLACK
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText(labels[i], (left + barWidth / 2).toFloat(), (y + barHeight + 12).toFloat(), labelPaint)
                }
                y += barHeight + 12
            }
            is vResolution -> {
                // 竖线：1pt 黑 / 1pt 白交替，在整个可用宽度内画
                val blackPaint = Paint().apply { color = Color.BLACK }
                var x = margin
                while (x < margin + usable) {
                    canvas.drawRect(x.toFloat(), y.toFloat(), (x + 1).toFloat(), (y + 50).toFloat(), blackPaint)
                    x += 2
                }
                // 标签
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 9f
                    color = Color.BLACK
                }
                canvas.drawText("↑ 1pt线/1pt间隔竖线", margin.toFloat(), (y + 58).toFloat(), labelPaint)
                y += 60
            }
            is hResolution -> {
                // 横线：1pt 黑 / 1pt 白交替，在可用高度内画
                val blackPaint = Paint().apply { color = Color.BLACK }
                var hy = y
                val endY = y + 34
                while (hy < endY) {
                    canvas.drawRect(margin.toFloat(), hy.toFloat(), (width - margin).toFloat(), (hy + 1).toFloat(), blackPaint)
                    hy += 2
                }
                y += 40
            }
            is gradientDither -> {
                // 灰度渐变：从上到下，顶部全黑(灰度0)到底部全白(灰度255)
                // 生成灰度数据，用 Floyd-Steinberg 抖动后绘制
                val gradWidth = usable
                val gradHeight = 72
                val grayData = IntArray(gradWidth * gradHeight)
                for (row in 0 until gradHeight) {
                    // 顶部灰度=0(黑), 底部灰度=255(白)
                    val gray = (row.toFloat() / gradHeight * 255f).toInt().coerceIn(0, 255)
                    for (col in 0 until gradWidth) {
                        grayData[row * gradWidth + col] = gray
                    }
                }
                val grayImage = GrayImage(grayData, gradWidth, gradHeight)
                val binary = ditherToBinary(grayImage, DitherMode.FLOYD_STEINBERG, 128)

                // 将二值数据绘制到 canvas
                val blackPaint = Paint().apply { color = Color.BLACK }
                for (row in 0 until gradHeight) {
                    for (col in 0 until gradWidth) {
                        if (binary[row * gradWidth + col].toInt() == 1) {
                            canvas.drawRect(
                                (margin + col).toFloat(), (y + row).toFloat(),
                                (margin + col + 1).toFloat(), (y + row + 1).toFloat(),
                                blackPaint
                            )
                        }
                    }
                }
                y += gradHeight + 8
            }
        }
        return y
    }
}

private fun wrapBenchmark(text: String, paint: Paint, maxWidth: Float): List<String> {
    if (text.isEmpty()) return listOf("")
    val lines = mutableListOf<String>()
    var current = ""
    for (ch in text) {
        val candidate = current + ch
        if (paint.measureText(candidate) <= maxWidth) {
            current = candidate
        } else {
            lines.add(current)
            current = ch.toString()
        }
    }
    lines.add(current)
    return lines
}

// ── UI 卡片 ──────────────────────────────────────────────

@Composable
fun BenchmarkCard() {
    val scope = rememberCoroutineScope()
    val printerStatus by com.qring.printer.model.PrinterStatusRepository.state.collectAsState()
    var printing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var messageOk by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = QringPalette.brand,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "基准测试页",
                    color = QringPalette.textPrimary,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "打印一张标准测试页，用于校验打印质量、浓度、分辨率等",
                fontSize = 12.sp,
                color = QringPalette.textSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (message.isNotEmpty()) {
                Text(
                    text = message,
                    fontSize = 12.sp,
                    color = if (messageOk) ONLINE else androidx.compose.ui.graphics.Color(0xFFFF4D4F)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    if (printerStatus.connState != com.qring.printer.model.ConnState.CONNECTED) {
                        message = "请先连接打印机"
                        messageOk = false
                        return@Button
                    }
                    printing = true
                    message = ""
                    scope.launch {
                        try {
                            val result = withContext(kotlinx.coroutines.Dispatchers.Default) {
                                val bitmap = renderBenchmarkBitmap(
                                    com.qring.printer.BuildConfig.VERSION_NAME,
                                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                                )
                                val raster = bitmapToRaster(bitmap, 211)
                                bitmap.recycle()
                                val printResult = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.qring.printer.bt.PrinterConnection.getInstance().printRaster(raster, 2)
                                }
                                printResult
                            }
                            printing = false
                            message = result.message
                            messageOk = result.ok
                        } catch (e: Exception) {
                            printing = false
                            message = "打印失败: ${e.message}"
                            messageOk = false
                        }
                    }
                },
                enabled = !printing,
                colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (printing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = androidx.compose.ui.graphics.Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("打印中...", fontSize = 13.sp)
                } else {
                    Text("打印测试页", fontSize = 13.sp)
                }
            }
        }
    }
}
