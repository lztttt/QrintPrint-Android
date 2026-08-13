package com.qring.printer.ui.mdprint

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.data.HistoryRepository
import com.qring.printer.model.ConnState
import com.qring.printer.model.HIST_TYPE_MARKDOWN
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.bitmapToRaster
import com.qring.printer.ui.common.FontList
import com.qring.printer.ui.common.PrintWarningDialog
import com.qring.printer.ui.theme.Metrics
import com.qring.printer.ui.theme.ONLINE
import com.qring.printer.ui.theme.QringPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

// ── 数据模型 ──────────────────────────────────────────────

data class MarkdownPrintState(
    val text: String = "",
    val fontSize: Float = 14f,
    val lineSpacing: Float = 4f,
    val margin: Float = 8f,
    val fontFamilies: List<String> = listOf("sans-serif", "serif", "monospace"),
    val fontFamilyIndex: Int = 0,
    val previewBitmap: Bitmap? = null,
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false
)

// ── ViewModel ────────────────────────────────────────────

class MarkdownPrintViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val historyRepo = HistoryRepository(application)
    private val _state = MutableStateFlow(MarkdownPrintState())
    val state: StateFlow<MarkdownPrintState> = _state.asStateFlow()

    val printerStatus = PrinterStatusRepository.state

    init {
        loadFonts()
    }

    fun loadFonts() {
        val fonts = FontList.getSystemFonts(getApplication())
        _state.value = _state.value.copy(fontFamilies = fonts)
    }

    fun setFontFamilyIndex(index: Int) {
        val families = _state.value.fontFamilies
        if (index in families.indices) {
            _state.value = _state.value.copy(fontFamilyIndex = index)
            updatePreview()
        }
    }

    fun importFont(uri: android.net.Uri): String? {
        val name = FontList.importFont(getApplication(), uri)
        if (name != null) {
            loadFonts()
            val idx = _state.value.fontFamilies.indexOf(name)
            if (idx >= 0) {
                _state.value = _state.value.copy(fontFamilyIndex = idx)
                updatePreview()
            }
        }
        return name
    }

    fun deleteImportedFont(family: String) {
        FontList.deleteImportedFont(family)
        loadFonts()
        if (_state.value.fontFamilyIndex >= _state.value.fontFamilies.size) {
            _state.value = _state.value.copy(fontFamilyIndex = 0)
            updatePreview()
        }
    }

    fun isImportedFont(family: String): Boolean = FontList.isImported(family)

    private fun currentFamily(): String {
        val idx = _state.value.fontFamilyIndex
        val families = _state.value.fontFamilies
        return if (idx in families.indices) families[idx] else "sans-serif"
    }

    fun updateText(text: String) {
        _state.value = _state.value.copy(text = text)
        updatePreview()
    }

    fun setFontSize(size: Float) {
        _state.value = _state.value.copy(fontSize = size)
        updatePreview()
    }

    fun setLineSpacing(spacing: Float) {
        _state.value = _state.value.copy(lineSpacing = spacing)
        updatePreview()
    }

    fun setMargin(margin: Float) {
        _state.value = _state.value.copy(margin = margin)
        updatePreview()
    }

    fun updatePreview() {
        val st = _state.value
        if (st.text.isEmpty()) {
            val old = _state.value.previewBitmap
            if (old != null) {
                _state.value = _state.value.copy(previewBitmap = null)
                old.recycle()
            }
            return
        }
        if (st.printing) return
        viewModelScope.launch {
            try {
                val old = _state.value.previewBitmap
                val bitmap = withContext(Dispatchers.Default) {
                    renderMarkdownBitmap(st)
                }
                _state.value = _state.value.copy(previewBitmap = bitmap)
                old?.let { if (it != bitmap) { delay(150); it.recycle() } }
            } catch (e: Exception) { }
        }
    }

    fun print() {
        val st = _state.value
        if (st.printing) return
        if (printerStatus.value.connState != ConnState.CONNECTED) {
            _state.value = _state.value.copy(resultOk = false, resultMessage = "请先在首页连接打印机")
            return
        }
        if (st.text.isEmpty()) {
            _state.value = _state.value.copy(resultOk = false, resultMessage = "请输入要打印的内容")
            return
        }

        _state.value = _state.value.copy(printing = true, resultMessage = "")

        viewModelScope.launch {
            try {
                val fault = withContext(Dispatchers.IO) { printerConnection.preflightCheck() }
                if (fault != null) {
                    _state.value = _state.value.copy(printing = false, resultOk = false, resultMessage = fault)
                    return@launch
                }

                val result = withContext(Dispatchers.Default) {
                    val bitmap = renderMarkdownBitmap(st)
                    val raster = bitmapToRaster(bitmap, 211)

                    val thumbBitmap = Bitmap.createScaledBitmap(bitmap, 200, Math.round(200f * bitmap.height / bitmap.width), true)
                    val printResult = withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, 1)
                    }

                    if (printResult.ok) {
                        try {
                            val payload = JSONObject().apply {
                                put("text", st.text)
                                put("fontSize", st.fontSize.toDouble())
                                put("lineSpacing", st.lineSpacing.toDouble())
                                put("margin", st.margin.toDouble())
                                put("fontIndex", st.fontFamilyIndex)
                            }.toString()
                            historyRepo.saveHistory(HIST_TYPE_MARKDOWN, thumbBitmap, payload)
                        } catch (e: Exception) { }
                    }

                    bitmap.recycle()
                    thumbBitmap.recycle()
                    printResult
                }

                _state.value = _state.value.copy(
                    printing = false,
                    resultOk = result.ok,
                    resultMessage = result.message
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    printing = false,
                    resultOk = false,
                    resultMessage = "打印失败：${e.message}"
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _state.value.previewBitmap?.recycle()
    }
}

// ── Markdown 渲染到 Bitmap ──────────────────────────────────

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

private fun renderMarkdownBitmap(state: MarkdownPrintState): Bitmap {
    val width = WIDTH_DOTS
    val margin = state.margin
    val usable = width - 2 * margin
    val baseFontSize = state.fontSize
    val lineSpacing = state.lineSpacing
    val family = state.fontFamilies.getOrElse(state.fontFamilyIndex) { "sans-serif" }

    // 预创建 typeface
    val normalTypeface = FontList.typefaceFor(family, false, false)
    val boldTypeface = FontList.typefaceFor(family, true, false)

    val parsed = parseMarkdown(state.text)

    data class MeasuredLine(
        val segments: List<Pair<String, Boolean>>,
        val fontSize: Float,
        val indent: Float,
        val prefix: String,
        val isBold: Boolean
    )

    val measuredLines = mutableListOf<MeasuredLine>()

    for (mdLine in parsed) {
        when (mdLine) {
            is MdLine.Blank -> {
                measuredLines.add(MeasuredLine(listOf("" to false), baseFontSize, 0f, "", false))
            }
            is MdLine.Header -> {
                val headerSize = when (mdLine.level) {
                    1 -> baseFontSize + 6f
                    2 -> baseFontSize + 4f
                    else -> baseFontSize + 2f
                }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = headerSize
                    typeface = boldTypeface
                }
                val wrapped = wrapText(mdLine.text, paint, usable)
                for (line in wrapped) {
                    measuredLines.add(MeasuredLine(listOf(line to true), headerSize, 0f, "", true))
                }
            }
            is MdLine.ListItem -> {
                val prefix = if (mdLine.ordered) "${mdLine.number}. " else "• "
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = baseFontSize
                    typeface = normalTypeface
                }
                val indent = paint.measureText(prefix)
                val wrapped = wrapText(mdLine.text, paint, usable - indent)
                for ((idx, line) in wrapped.withIndex()) {
                    val p = if (idx == 0) prefix else ""
                    measuredLines.add(MeasuredLine(listOf(line to false), baseFontSize, indent, p, false))
                }
            }
            is MdLine.Paragraph -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = baseFontSize
                    typeface = normalTypeface
                }
                val fullText = mdLine.segments.joinToString("") { it.first }
                val wrapped = wrapText(fullText, paint, usable)
                for (line in wrapped) {
                    measuredLines.add(MeasuredLine(parseBoldSegments(line), baseFontSize, 0f, "", false))
                }
            }
        }
    }

    val lineHeight = baseFontSize + lineSpacing
    val headerLineHeight1 = (baseFontSize + 6f) + lineSpacing
    val headerLineHeight2 = (baseFontSize + 4f) + lineSpacing
    val headerLineHeight3 = (baseFontSize + 2f) + lineSpacing

    var totalHeight = 0
    for (ml in measuredLines) {
        val lh = when (ml.fontSize) {
            baseFontSize + 6f -> headerLineHeight1
            baseFontSize + 4f -> headerLineHeight2
            baseFontSize + 2f -> headerLineHeight3
            else -> lineHeight
        }
        totalHeight += lh.toInt()
    }
    totalHeight = (totalHeight + 2 * margin.toInt()).coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    var y = margin
    for (ml in measuredLines) {
        val baseTypeface = if (ml.isBold) boldTypeface else normalTypeface
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ml.fontSize
            color = Color.BLACK
            typeface = baseTypeface
        }

        val lh = when (ml.fontSize) {
            baseFontSize + 6f -> headerLineHeight1
            baseFontSize + 4f -> headerLineHeight2
            baseFontSize + 2f -> headerLineHeight3
            else -> lineHeight
        }

        val baseline = y + ml.fontSize - paint.fontMetrics.ascent * 0.3f

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

        y += lh.toInt()
    }

    return bitmap
}

private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
    if (text.isEmpty()) return listOf("")
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
            if (paint.measureText(candidate) <= maxWidth) {
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

// ── UI ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownPrintScreen(
    navController: NavHostController,
    viewModel: MarkdownPrintViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val state by viewModel.state.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current

    // md 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (content != null) {
                    viewModel.updateText(content)
                }
            } catch (e: Exception) { }
        }
    }

    // 字体文件选择器
    val fontPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importFont(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QringPalette.pageBg)
    ) {
        TopAppBar(
            title = { Text("文档打印") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = QringPalette.surface,
                titleContentColor = QringPalette.textPrimary
            )
        )

        // 预览
        MdPreviewCard(preview = state.previewBitmap, text = state.text)

        // 输入 + 设置
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.PAGE_PADDING.dp)
                .padding(top = 12.dp, bottom = 12.dp)
        ) {
            // 文本输入
            Card(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box {
                    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                    BasicTextField(
                        value = state.text,
                        onValueChange = { viewModel.updateText(it) },
                        enabled = !state.printing,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .padding(end = 80.dp),
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            color = QringPalette.textPrimary
                        ),
                        cursorBrush = SolidColor(QringPalette.brand),
                        decorationBox = { innerTextField ->
                            Box {
                                if (state.text.isEmpty()) {
                                    Text(
                                        text = "输入 Markdown 文本...\n支持 # 标题  - 列表  **粗体**",
                                        color = QringPalette.textSecondary,
                                        fontSize = 14.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    // 粘贴按钮
                    IconButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text ?: ""
                            if (clipText.isNotEmpty()) viewModel.updateText(clipText)
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "粘贴", tint = QringPalette.textSecondary, modifier = Modifier.size(20.dp))
                    }
                    // 上传 md 文件按钮
                    IconButton(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream", "*/*"))
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 40.dp, top = 4.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "导入MD文件", tint = QringPalette.brand, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 排版设置
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // 字体选择
                    FontSelectorRow(
                        families = state.fontFamilies,
                        selectedIndex = state.fontFamilyIndex,
                        onSelect = viewModel::setFontFamilyIndex,
                        onImportFont = { fontPickerLauncher.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream", "*/*")) },
                        onDeleteFont = viewModel::deleteImportedFont,
                        isImportedFont = viewModel::isImportedFont,
                        enabled = !state.printing
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "共 ${state.fontFamilies.size} 种 · 支持 TTF/OTF 导入",
                        fontSize = 10.sp,
                        color = QringPalette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("字号", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                        Text("${state.fontSize.toInt()}pt", fontSize = 13.sp, color = QringPalette.textSecondary)
                    }
                    Slider(
                        value = state.fontSize,
                        onValueChange = { viewModel.setFontSize(it) },
                        valueRange = 8f..24f,
                        colors = SliderDefaults.colors(thumbColor = QringPalette.brand, activeTrackColor = QringPalette.brand)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("行距", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                        Text("${state.lineSpacing.toInt()}pt", fontSize = 13.sp, color = QringPalette.textSecondary)
                    }
                    Slider(
                        value = state.lineSpacing,
                        onValueChange = { viewModel.setLineSpacing(it) },
                        valueRange = 0f..16f,
                        colors = SliderDefaults.colors(thumbColor = QringPalette.brand, activeTrackColor = QringPalette.brand)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("页边距", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                        Text("${state.margin.toInt()}pt", fontSize = 13.sp, color = QringPalette.textSecondary)
                    }
                    Slider(
                        value = state.margin,
                        onValueChange = { viewModel.setMargin(it) },
                        valueRange = 0f..40f,
                        colors = SliderDefaults.colors(thumbColor = QringPalette.brand, activeTrackColor = QringPalette.brand)
                    )
                }
            }
        }

        // 底部操作栏
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(QringPalette.surface)
                .padding(horizontal = Metrics.PAGE_PADDING.dp)
                .padding(top = 10.dp, bottom = 16.dp)
        ) {
            if (state.resultMessage.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.resultOk) ONLINE.copy(alpha = 0.1f) else androidx.compose.ui.graphics.Color(0xFFFF4D4F).copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = state.resultMessage,
                        modifier = Modifier.padding(12.dp),
                        color = if (state.resultOk) ONLINE else androidx.compose.ui.graphics.Color(0xFFFF4D4F),
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
            Button(
                onClick = { viewModel.print() },
                enabled = !state.printing && state.text.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (state.printing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("打印中…", fontSize = 15.sp)
                } else {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("打印", fontSize = 16.sp)
                }
            }
        }
    }

    PrintWarningDialog(onGoBack = { navController.popBackStack() })
}

@Composable
private fun FontSelectorRow(
    families: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onImportFont: () -> Unit = {},
    onDeleteFont: (String) -> Unit = {},
    isImportedFont: (String) -> Boolean = { false },
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "字体",
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            color = QringPalette.textPrimary
        )
        Text(
            text = FontList.fontLabel(families.getOrElse(selectedIndex) { "sans-serif" }),
            fontSize = 13.sp,
            color = QringPalette.brand,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(QringPalette.surface)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(
            onClick = onImportFont,
            enabled = enabled,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "导入字体",
                tint = QringPalette.brand,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            families.forEachIndexed { index, family ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                FontList.fontLabel(family),
                                fontSize = 13.sp,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            if (isImportedFont(family)) {
                                androidx.compose.material3.TextButton(
                                    onClick = { onDeleteFont(family) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "删除字体",
                                        tint = androidx.compose.ui.graphics.Color.Red,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MdPreviewCard(preview: Bitmap?, text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.PAGE_PADDING.dp)
            .padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = if (preview != null)
                    "宽 384 点 × 高 ${preview.height} 点"
                else if (text.isEmpty()) "输入内容后自动预览"
                else "正在渲染预览…",
                fontSize = 11.sp,
                color = QringPalette.textSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Color.White)
            ) {
                val canvasWidthDp = maxWidth.value
                val scale = canvasWidthDp / 384f
                when {
                    preview != null -> {
                        val contentH = (preview.height * scale)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = "打印预览",
                                modifier = Modifier
                                    .width(canvasWidthDp.dp)
                                    .height(contentH.dp),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                    }
                    text.isEmpty() -> {
                        Text("空", color = QringPalette.textSecondary, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
                    }
                    else -> {
                        Text("…", color = QringPalette.textSecondary, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}
