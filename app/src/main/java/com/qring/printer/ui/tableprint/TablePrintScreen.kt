package com.qring.printer.ui.tableprint

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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.qring.printer.model.HIST_TYPE_TABLE
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.bitmapToRaster
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
import org.json.JSONArray
import org.json.JSONObject

// ── 数据模型 ──────────────────────────────────────────────

data class TableConfig(
    val columnCount: Int = 3,
    val fontSize: Float = 14f,
    val showBorder: Boolean = true,
    val headerBold: Boolean = true,
    val padding: Float = 4f
)

data class TableState(
    val config: TableConfig = TableConfig(),
    val headers: List<String> = listOf("列1", "列2", "列3"),
    val rows: List<List<String>> = listOf(
        listOf("数据1", "数据2", "数据3"),
        listOf("数据4", "数据5", "数据6")
    ),
    val previewBitmap: Bitmap? = null,
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false
)

// ── ViewModel ────────────────────────────────────────────

class TablePrintViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val historyRepo = HistoryRepository(application)
    private val _state = MutableStateFlow(TableState())
    val state: StateFlow<TableState> = _state.asStateFlow()

    val printerStatus = PrinterStatusRepository.state

    init {
        updatePreview()
    }

    fun setColumnCount(count: Int) {
        val n = count.coerceIn(2, 6)
        val headers = _state.value.headers.toMutableList()
        val rows = _state.value.rows.map { it.toMutableList() }.toMutableList()
        while (headers.size < n) headers.add("列${headers.size + 1}")
        while (headers.size > n) headers.removeAt(headers.size - 1)
        for (row in rows) {
            while (row.size < n) row.add("")
            while (row.size > n) row.removeAt(row.size - 1)
        }
        _state.value = _state.value.copy(config = _state.value.config.copy(columnCount = n), headers = headers, rows = rows)
        updatePreview()
    }

    fun setFontSize(size: Float) {
        _state.value = _state.value.copy(config = _state.value.config.copy(fontSize = size))
        updatePreview()
    }

    fun toggleBorder() {
        _state.value = _state.value.copy(config = _state.value.config.copy(showBorder = !_state.value.config.showBorder))
        updatePreview()
    }

    fun toggleHeaderBold() {
        _state.value = _state.value.copy(config = _state.value.config.copy(headerBold = !_state.value.config.headerBold))
        updatePreview()
    }

    fun setPadding(p: Float) {
        _state.value = _state.value.copy(config = _state.value.config.copy(padding = p))
        updatePreview()
    }

    fun updateHeader(index: Int, value: String) {
        val headers = _state.value.headers.toMutableList()
        if (index in headers.indices) {
            headers[index] = value
            _state.value = _state.value.copy(headers = headers)
            updatePreview()
        }
    }

    fun updateCell(row: Int, col: Int, value: String) {
        val rows = _state.value.rows.map { it.toList() }.toMutableList()
        if (row in rows.indices && col in rows[row].indices) {
            rows[row] = rows[row].toMutableList().also { it[col] = value }
            _state.value = _state.value.copy(rows = rows)
            updatePreview()
        }
    }

    fun addRow() {
        val n = _state.value.config.columnCount
        val rows = _state.value.rows.toMutableList()
        rows.add(List(n) { "" })
        _state.value = _state.value.copy(rows = rows)
        updatePreview()
    }

    fun removeRow(index: Int) {
        val rows = _state.value.rows.toMutableList()
        if (index in rows.indices && rows.size > 1) {
            rows.removeAt(index)
            _state.value = _state.value.copy(rows = rows)
            updatePreview()
        }
    }

    fun updatePreview() {
        val st = _state.value
        if (st.printing) return
        viewModelScope.launch {
            try {
                val old = _state.value.previewBitmap
                val bitmap = withContext(Dispatchers.Default) {
                    renderTableBitmap(st)
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

        _state.value = _state.value.copy(printing = true, resultMessage = "")

        viewModelScope.launch {
            try {
                val fault = withContext(Dispatchers.IO) { printerConnection.preflightCheck() }
                if (fault != null) {
                    _state.value = _state.value.copy(printing = false, resultOk = false, resultMessage = fault)
                    return@launch
                }

                val result = withContext(Dispatchers.Default) {
                    val bitmap = renderTableBitmap(st)
                    val raster = bitmapToRaster(bitmap, 211)

                    val thumbBitmap = Bitmap.createScaledBitmap(bitmap, 200, Math.round(200f * bitmap.height / bitmap.width), true)
                    val printResult = withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, 1)
                    }

                    if (printResult.ok) {
                        try {
                            val payload = JSONObject().apply {
                                put("columnCount", st.config.columnCount)
                                put("fontSize", st.config.fontSize.toDouble())
                                put("showBorder", st.config.showBorder)
                                put("headerBold", st.config.headerBold)
                                put("padding", st.config.padding.toDouble())
                                put("headers", JSONArray(st.headers))
                                val rowsArray = JSONArray()
                                for (row in st.rows) {
                                    rowsArray.put(JSONArray(row))
                                }
                                put("rows", rowsArray)
                            }.toString()
                            historyRepo.saveHistory(HIST_TYPE_TABLE, thumbBitmap, payload)
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

// ── 表格渲染 ──────────────────────────────────────────────

private fun renderTableBitmap(state: TableState): Bitmap {
    val config = state.config
    val width = WIDTH_DOTS
    val padding = config.padding

    // 计算列宽：均分
    val colWidth = width / config.columnCount

    // 测量行高
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = config.fontSize
        color = Color.BLACK
        typeface = Typeface.DEFAULT
    }
    val lineHeight = config.fontSize + 4f

    // 文字折行：按列宽算每列每行的文字行数
    fun wrapCell(text: String, colW: Int): List<String> {
        val usable = colW - 2 * padding
        if (text.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
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

    // 计算所有行的高度
    val allRows = listOf(state.headers) + state.rows
    val rowHeights = allRows.map { row ->
        val maxLines = row.indices.maxOfOrNull { col ->
            wrapCell(row.getOrElse(col) { "" }, colWidth).size
        } ?: 1
        (maxLines * lineHeight + 2 * padding).toInt()
    }

    val totalHeight = (rowHeights.sum() + 4).coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    // 画边框
    if (config.showBorder) {
        val borderPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        // 外框
        canvas.drawRect(0.5f, 0.5f, width - 0.5f, totalHeight - 0.5f, borderPaint)
        // 竖线
        for (i in 1 until config.columnCount) {
            val x = i * colWidth.toFloat()
            canvas.drawLine(x, 0f, x, totalHeight.toFloat(), borderPaint)
        }
        // 横线（表头与数据之间，以及数据行之间）
        var lineY = 0
        for (i in 0 until allRows.size - 1) {
            lineY += rowHeights[i]
            canvas.drawLine(0f, lineY.toFloat(), width.toFloat(), lineY.toFloat(), borderPaint)
        }
    }

    // 画文字
    var y = 0
    for ((rowIdx, row) in allRows.withIndex()) {
        val rowH = rowHeights[rowIdx]
        val isHeader = rowIdx == 0
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = config.fontSize
            color = Color.BLACK
            typeface = if (isHeader && config.headerBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        for (col in 0 until config.columnCount) {
            val cellText = row.getOrElse(col) { "" }
            val wrappedLines = wrapCell(cellText, colWidth)
            val cellX = col * colWidth
            val textBlockHeight = wrappedLines.size * lineHeight
            val startY = y + (rowH - textBlockHeight) / 2f - textPaint.fontMetrics.ascent

            for ((lineIdx, line) in wrappedLines.withIndex()) {
                val ly = startY + lineIdx * lineHeight
                val textWidth = textPaint.measureText(line)
                val tx = cellX + padding + (colWidth - 2 * padding - textWidth) / 2f
                canvas.drawText(line, tx, ly, textPaint)
            }
        }
        y += rowH
    }

    return bitmap
}


// ── UI ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TablePrintScreen(
    navController: NavHostController,
    viewModel: TablePrintViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.headers, state.rows, state.config) {
        viewModel.updatePreview()
    }

    Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(QringPalette.pageBg)
    ) {
        TopAppBar(
            title = { Text("表格打印") },
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
        TablePreviewCard(preview = state.previewBitmap)

        // 设置 + 编辑
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.PAGE_PADDING.dp)
                .padding(top = 12.dp, bottom = 12.dp)
        ) {
            // 列数设置
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("列数", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                        Text("${state.config.columnCount}", fontSize = 13.sp, color = QringPalette.textSecondary)
                    }
                    Slider(
                        value = state.config.columnCount.toFloat(),
                        onValueChange = { viewModel.setColumnCount(it.toInt()) },
                        valueRange = 2f..6f,
                        steps = 3,
                        colors = SliderDefaults.colors(
                            thumbColor = QringPalette.brand,
                            activeTrackColor = QringPalette.brand
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("字号", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                        Text("${state.config.fontSize.toInt()}pt", fontSize = 13.sp, color = QringPalette.textSecondary)
                    }
                    Slider(
                        value = state.config.fontSize,
                        onValueChange = { viewModel.setFontSize(it) },
                        valueRange = 8f..28f,
                        colors = SliderDefaults.colors(
                            thumbColor = QringPalette.brand,
                            activeTrackColor = QringPalette.brand
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("边框", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                        Switch(checked = state.config.showBorder, onCheckedChange = { viewModel.toggleBorder() })
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("表头加粗", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                        Switch(checked = state.config.headerBold, onCheckedChange = { viewModel.toggleHeaderBold() })
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 可编辑表格
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("表格编辑", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.addRow() }, enabled = !state.printing && state.rows.size < 20) {
                    Icon(Icons.Default.Add, contentDescription = "添加行", tint = QringPalette.brand)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            EditableTableGrid(
                headers = state.headers,
                rows = state.rows,
                enabled = !state.printing,
                onUpdateHeader = viewModel::updateHeader,
                onUpdateCell = viewModel::updateCell,
                onRemoveRow = viewModel::removeRow
            )
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
                enabled = !state.printing,
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
private fun EditableTableGrid(
    headers: List<String>,
    rows: List<List<String>>,
    enabled: Boolean,
    onUpdateHeader: (Int, String) -> Unit,
    onUpdateCell: (Int, Int, String) -> Unit,
    onRemoveRow: (Int) -> Unit
) {
    val borderColor = QringPalette.offline
    val headerBg = QringPalette.surface
    val cellBg = QringPalette.surface

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            // 横向滚动容器
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                // 删除按钮列宽度
                val minColWidth = 80.dp
                val colCount = headers.size

                Column {
                    // 表头行
                    Row(
                        modifier = Modifier
                            .height(40.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        headers.forEachIndexed { colIdx, header ->
                            OutlinedTextField(
                                value = header,
                                onValueChange = { onUpdateHeader(colIdx, it) },
                                modifier = Modifier
                                    .width(minColWidth)
                                    .padding(2.dp),
                                singleLine = true,
                                enabled = enabled,
                                textStyle = TextStyle(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = QringPalette.textPrimary
                                ),
                                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = headerBg,
                                    unfocusedContainerColor = headerBg,
                                    focusedBorderColor = QringPalette.brand,
                                    unfocusedBorderColor = borderColor
                                )
                            )
                        }
                        // 表头行末尾占位
                        Spacer(modifier = Modifier.width(28.dp))
                    }

                    // 数据行
                    rows.forEachIndexed { rowIdx, row ->
                        Row(
                            modifier = Modifier.height(40.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            row.forEachIndexed { colIdx, cell ->
                                OutlinedTextField(
                                    value = cell,
                                    onValueChange = { onUpdateCell(rowIdx, colIdx, it) },
                                    modifier = Modifier
                                        .width(minColWidth)
                                        .padding(2.dp),
                                    singleLine = true,
                                    enabled = enabled,
                                    textStyle = TextStyle(
                                        fontSize = 12.sp,
                                        color = QringPalette.textPrimary
                                    ),
                                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = cellBg,
                                        unfocusedContainerColor = cellBg,
                                        focusedBorderColor = QringPalette.brand,
                                        unfocusedBorderColor = borderColor
                                    )
                                )
                            }
                            // 删除行按钮
                            IconButton(
                                onClick = { onRemoveRow(rowIdx) },
                                enabled = enabled && rows.size > 1,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除行",
                                    tint = androidx.compose.ui.graphics.Color.Red.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TablePreviewCard(preview: Bitmap?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.PAGE_PADDING.dp)
            .padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            Text(
                text = if (preview != null)
                    "宽 384 点 × 高 ${preview.height} 点"
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
                if (preview != null) {
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
                } else {
                    Text(
                        text = "…",
                        color = QringPalette.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}
