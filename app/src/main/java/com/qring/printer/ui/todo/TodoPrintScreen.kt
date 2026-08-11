package com.qring.printer.ui.todo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.data.HistoryPayloadHolder
import com.qring.printer.data.HistoryRepository
import com.qring.printer.bt.PrintResult
import com.qring.printer.model.ConnState
import com.qring.printer.model.PrinterStatus
import com.qring.printer.model.HIST_TYPE_TODO
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.RasterData
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.WIDTH_BYTES
import com.qring.printer.protocol.createBinaryCanvas
import com.qring.printer.protocol.blitBinary
import com.qring.printer.protocol.packBinaryToRaster
import com.qring.printer.protocol.bitmapToGray
import com.qring.printer.protocol.ditherToBinary
import com.qring.printer.protocol.DitherMode
import com.qring.printer.protocol.renderTextToPixelMap
import com.qring.printer.protocol.TextRenderOptions
import com.qring.printer.ui.common.FontList
import com.qring.printer.ui.theme.QringPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ── 数据模型 ──────────────────────────────────────────────

data class TodoItem(
    val id: String,
    val text: String,
    val done: Boolean = false,
    val priority: Int = 0  // 0=普通, 1=重要, 2=紧急
)

data class TodoState(
    val items: List<TodoItem> = emptyList(),
    val title: String = "待办事项",
    val fontSize: Float = 22f,
    val fontFamilyIndex: Int = 0,
    val fontFamilies: List<String> = listOf("sans-serif", "serif", "monospace"),
    val previewBitmap: Bitmap? = null,
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false
)

// ── ViewModel ────────────────────────────────────────────

class TodoViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val historyRepo = HistoryRepository(application)
    private val _state = MutableStateFlow(TodoState())
    val state: StateFlow<TodoState> = _state.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    init {
        _state.value = TodoState(items = listOf(
            TodoItem(java.util.UUID.randomUUID().toString(), "示例待办事项")
        ))
        restoreFromHistoryPayload()
    }

    private fun restoreFromHistoryPayload() {
        val (type, payload) = HistoryPayloadHolder.consumePayload() ?: return
        if (type != HIST_TYPE_TODO) {
            HistoryPayloadHolder.setPayload(type, payload)
            return
        }
        try {
            val obj = JSONObject(payload)
            val items = mutableListOf<TodoItem>()
            val itemsArray = obj.optJSONArray("items")
            if (itemsArray != null) {
                for (i in 0 until itemsArray.length()) {
                    val itemObj = itemsArray.getJSONObject(i)
                    items.add(TodoItem(
                        id = itemObj.getString("id"),
                        text = itemObj.getString("text"),
                        done = itemObj.optBoolean("done", false),
                        priority = itemObj.optInt("priority", 0)
                    ))
                }
            }
            _state.value = TodoState(
                title = obj.optString("title", "待办事项"),
                fontSize = obj.optDouble("fontSize", 22.0).toFloat(),
                fontFamilyIndex = obj.optInt("fontFamilyIndex", 0),
                items = items
            )
            updatePreview()
        } catch (e: Exception) { }
    }

    private fun serializeTodoState(state: TodoState): String {
        return JSONObject().apply {
            put("title", state.title)
            put("fontSize", state.fontSize.toDouble())
            put("fontFamilyIndex", state.fontFamilyIndex)
            val itemsArray = JSONArray()
            state.items.forEach { item ->
                itemsArray.put(JSONObject().apply {
                    put("id", item.id)
                    put("text", item.text)
                    put("done", item.done)
                    put("priority", item.priority)
                })
            }
            put("items", itemsArray)
        }.toString()
    }

    fun addItem(text: String) {
        if (text.isBlank()) return
        _state.update { current ->
            current.copy(items = current.items + TodoItem(java.util.UUID.randomUUID().toString(), text))
        }
        updatePreview()
    }

    fun updateItem(id: String, text: String) {
        _state.update { current ->
            current.copy(items = current.items.map { if (it.id == id) it.copy(text = text) else it })
        }
        updatePreview()
    }

    fun toggleDone(id: String) {
        _state.update { current ->
            current.copy(items = current.items.map { if (it.id == id) it.copy(done = !it.done) else it })
        }
        updatePreview()
    }

    fun deleteItem(id: String) {
        _state.update { current ->
            current.copy(items = current.items.filter { it.id != id })
        }
        updatePreview()
    }

    fun setPriority(id: String, priority: Int) {
        _state.update { current ->
            current.copy(items = current.items.map { if (it.id == id) it.copy(priority = priority) else it })
        }
        updatePreview()
    }

    fun updateTitle(title: String) {
        _state.update { it.copy(title = title) }
        updatePreview()
    }

    fun setFontSize(size: Float) {
        _state.update { it.copy(fontSize = size) }
        updatePreview()
    }

    fun setFontFamilyIndex(index: Int) {
        _state.update { it.copy(fontFamilyIndex = index) }
        updatePreview()
    }

    fun loadFonts() {
        val fonts = FontList.getSystemFonts(getApplication())
        _state.update { it.copy(fontFamilies = fonts) }
    }

    fun moveItemUp(id: String) {
        _state.update { current ->
            val idx = current.items.indexOfFirst { it.id == id }
            if (idx > 0) {
                val items = current.items.toMutableList()
                val temp = items[idx]
                items[idx] = items[idx - 1]
                items[idx - 1] = temp
                current.copy(items = items)
            } else current
        }
        updatePreview()
    }

    fun moveItemDown(id: String) {
        _state.update { current ->
            val idx = current.items.indexOfFirst { it.id == id }
            if (idx >= 0 && idx < current.items.size - 1) {
                val items = current.items.toMutableList()
                val temp = items[idx]
                items[idx] = items[idx + 1]
                items[idx + 1] = temp
                current.copy(items = items)
            } else current
        }
        updatePreview()
    }

    fun updatePreview() {
        val state = _state.value
        if (state.items.isEmpty() && state.title.isBlank()) {
            val old = _state.value.previewBitmap
            if (old != null) {
                _state.value = _state.value.copy(previewBitmap = null)
                old.recycle()
            }
            return
        }
        if (state.printing) return
        viewModelScope.launch {
            try {
                val old = _state.value.previewBitmap
                val bitmap = withContext(Dispatchers.Default) {
                    renderTodoPreview(state)
                }
                _state.value = _state.value.copy(previewBitmap = bitmap)
                old?.let { if (it != bitmap) { delay(150); it.recycle() } }
            } catch (e: Exception) { }
        }
    }

    /** 渲染待办事项预览 */
    private fun renderTodoPreview(state: TodoState): Bitmap {
        val margin = 12f
        val itemSize = state.fontSize
        val titleSize = itemSize + 6f
        val lineSpacing = 6f
        val checkboxSize = 16f
        val checkboxGap = 8f
        val sectionGap = 16f
        val family = state.fontFamilies.getOrElse(state.fontFamilyIndex) { "sans-serif" }

        // 先计算所有行
        data class RenderLine(val text: String, val isTitle: Boolean, val done: Boolean, val priority: Int, val yOffset: Float)

        val lines = mutableListOf<RenderLine>()
        var currentY = margin

        // 标题
        if (state.title.isNotBlank()) {
            lines.add(RenderLine(state.title, true, false, 0, currentY))
            currentY += titleSize + lineSpacing + sectionGap
        }

        // 统计
        val doneCount = state.items.count { it.done }
        val totalCount = state.items.size
        val statsText = "共 $totalCount 项 · 已完成 $doneCount 项"
        lines.add(RenderLine(statsText, false, false, -1, currentY))
        currentY += itemSize + lineSpacing + sectionGap / 2

        // 待办项
        for (item in state.items) {
            val priorityMark = when (item.priority) { 2 -> "❗ "; 1 -> "★ "; else -> "" }
            val checkMark = if (item.done) "☑ " else "☐ "
            lines.add(RenderLine("$checkMark$priorityMark${item.text}", false, item.done, item.priority, currentY))
            currentY += itemSize + lineSpacing
        }

        val totalHeight = (currentY + margin).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(WIDTH_DOTS, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = titleSize
            color = Color.BLACK
            typeface = FontList.typefaceFor(family, bold = true)
            textAlign = Paint.Align.LEFT
        }

        val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = itemSize
            color = Color.BLACK
            typeface = FontList.typefaceFor(family)
            textAlign = Paint.Align.LEFT
        }

        val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = itemSize
            color = Color.GRAY
            typeface = FontList.typefaceFor(family, italic = true)
            textAlign = Paint.Align.LEFT
            isStrikeThruText = true
        }

        val statsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (itemSize - 4f).coerceAtLeast(14f)
            color = Color.GRAY
            typeface = FontList.typefaceFor(family)
            textAlign = Paint.Align.LEFT
        }

        // 分隔线画笔
        val dividerPaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }

        for (line in lines) {
            val paint = when {
                line.priority == -1 -> statsPaint
                line.isTitle -> titlePaint
                line.done -> donePaint
                else -> itemPaint
            }
            val fm = paint.fontMetrics
            val y = line.yOffset - fm.ascent
            canvas.drawText(line.text, margin, y, paint)

            // 在标题和统计之间画分隔线
            if (line.isTitle && lines.size > 1) {
                val dividerY = line.yOffset + titleSize + lineSpacing + sectionGap / 2
                canvas.drawLine(margin, dividerY, WIDTH_DOTS - margin, dividerY, dividerPaint)
            }
        }

        return bitmap
    }

    fun print() {
        val state = _state.value
        if (state.printing) return
        if (printerStatus.value.connState != ConnState.CONNECTED) {
            _state.value = _state.value.copy(
                resultOk = false,
                resultMessage = "请先在首页连接打印机"
            )
            return
        }
        if (state.items.isEmpty()) {
            _state.value = _state.value.copy(
                resultOk = false,
                resultMessage = "请添加待办事项"
            )
            return
        }

        _state.value = _state.value.copy(printing = true, resultMessage = "")

        viewModelScope.launch {
            try {
                val fault = withContext(Dispatchers.IO) {
                    printerConnection.preflightCheck()
                }
                if (fault != null) {
                    _state.value = _state.value.copy(
                        printing = false,
                        resultOk = false,
                        resultMessage = fault
                    )
                    return@launch
                }

                val result = withContext(Dispatchers.Default) {
                    val previewBmp = renderTodoPreview(_state.value)
                    val thumbBitmap = Bitmap.createScaledBitmap(previewBmp, 200, Math.round(200f * previewBmp.height / previewBmp.width), true)
                    val gray = bitmapToGray(previewBmp)
                    val binary = ditherToBinary(gray, DitherMode.NONE, 211)
                    previewBmp.recycle()
                    val raster = packBinaryToRaster(binary, gray.width, gray.height)

                    val printResult = withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, null)
                    }

                    if (printResult.ok) {
                        try {
                            val payload = serializeTodoState(_state.value)
                            historyRepo.saveHistory(HIST_TYPE_TODO, thumbBitmap, payload)
                        } catch (e: Exception) { }
                    }

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

    fun clearResult() {
        _state.value = _state.value.copy(resultMessage = "", resultOk = false)
    }

    override fun onCleared() {
        super.onCleared()
        _state.value.previewBitmap?.recycle()
    }
}

// ── 屏幕 ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoPrintScreen(navController: NavHostController) {
    val viewModel: TodoViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    // 加载字体列表
    LaunchedEffect(Unit) {
        viewModel.loadFonts()
    }

    // 自动预览
    LaunchedEffect(
        state.items.map { it.text + it.done.toString() + it.priority.toString() }.hashCode(),
        state.title,
        state.fontSize,
        state.fontFamilyIndex
    ) {
        delay(400)
        viewModel.updatePreview()
    }

    Scaffold(
        containerColor = QringPalette.pageBg,
        topBar = {
            TopAppBar(
                title = { Text("Todo打印", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = QringPalette.surface,
                    titleContentColor = QringPalette.textPrimary,
                    navigationIconContentColor = QringPalette.textPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = QringPalette.brand
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                // 预览卡片
                TodoPreviewCard(preview = state.previewBitmap, state = state)

                Spacer(modifier = Modifier.height(8.dp))

                // 标题
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::updateTitle,
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 字体设置
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("字号", fontSize = 13.sp, color = QringPalette.textSecondary)
                            Text("${state.fontSize.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.brand)
                        }
                        Slider(
                            value = state.fontSize,
                            onValueChange = viewModel::setFontSize,
                            valueRange = 14f..40f
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("字体", fontSize = 13.sp, color = QringPalette.textSecondary)
                            var showFontMenu by remember { mutableStateOf(false) }
                            Box {
                                Text(
                                    text = FontList.fontLabel(state.fontFamilies.getOrElse(state.fontFamilyIndex) { "sans-serif" }),
                                    fontSize = 13.sp,
                                    color = QringPalette.textPrimary,
                                    modifier = Modifier.clickable { showFontMenu = true }
                                )
                                DropdownMenu(
                                    expanded = showFontMenu,
                                    onDismissRequest = { showFontMenu = false }
                                ) {
                                    state.fontFamilies.forEachIndexed { index, family ->
                                        DropdownMenuItem(
                                            text = { Text(FontList.fontLabel(family)) },
                                            onClick = {
                                                viewModel.setFontFamilyIndex(index)
                                                showFontMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 统计
                val doneCount = state.items.count { it.done }
                Text(
                    text = "共 ${state.items.size} 项 · 已完成 $doneCount 项",
                    fontSize = 12.sp,
                    color = QringPalette.textSecondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 列表
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 100.dp)
                ) {
                    items(state.items) { item ->
                        TodoItemCard(
                            item = item,
                            onToggleDone = { viewModel.toggleDone(item.id) },
                            onDelete = { viewModel.deleteItem(item.id) },
                            onEdit = { viewModel.updateItem(item.id, it) },
                            onPriorityChange = { viewModel.setPriority(item.id, it) },
                            onMoveUp = { viewModel.moveItemUp(item.id) },
                            onMoveDown = { viewModel.moveItemDown(item.id) }
                        )
                    }
                }
            }

            // 底部打印按钮
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(QringPalette.surface)
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp)
            ) {
                if (state.resultMessage.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.resultOk)
                                androidx.compose.ui.graphics.Color(0xFF4CAF50).copy(alpha = 0.1f)
                            else
                                androidx.compose.ui.graphics.Color(0xFFFF4D4F).copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = state.resultMessage,
                            modifier = Modifier.padding(12.dp),
                            color = if (state.resultOk) androidx.compose.ui.graphics.Color(0xFF4CAF50) else androidx.compose.ui.graphics.Color(0xFFFF4D4F),
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                val connected = printerStatus.connState == ConnState.CONNECTED
                Button(
                    onClick = viewModel::print,
                    enabled = !state.printing && state.items.isNotEmpty() && connected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (state.printing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = androidx.compose.ui.graphics.Color.White,
                            strokeWidth = 2.dp
                        )
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
    }

    if (showAddDialog) {
        var newText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加待办") },
            text = {
                OutlinedTextField(
                    value = newText,
                    onValueChange = { newText = it },
                    label = { Text("内容") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.addItem(newText)
                    newText = ""
                    showAddDialog = false
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            }
        )
    }
}

// ── 预览卡片 ─────────────────────────────────────────────

@Composable
private fun TodoPreviewCard(
    preview: Bitmap?,
    state: TodoState
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (preview != null)
                    "宽 384 点 · 高 ${preview.height} 点 (${String.format("%.1f", preview.height / 8.0)}mm)"
                else if (state.items.isEmpty())
                    "添加待办后自动预览"
                else
                    "正在渲染预览…",
                fontSize = 11.sp,
                color = QringPalette.textSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Color.White)
            ) {
                val canvasWidthDp = maxWidth.value
                val scale = canvasWidthDp / 384f
                when {
                    preview != null -> {
                        val contentH = preview.height * scale
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = "Todo预览",
                                modifier = Modifier
                                    .width(canvasWidthDp.dp)
                                    .height(contentH.dp),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = "空",
                            color = QringPalette.textSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}

// ── Todo卡片 ─────────────────────────────────────────────

@Composable
private fun TodoItemCard(
    item: TodoItem,
    onToggleDone: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (String) -> Unit,
    onPriorityChange: (Int) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var editText by remember(item.id) { mutableStateOf(item.text) }

    val priorityColor = when (item.priority) {
        2 -> androidx.compose.ui.graphics.Color(0xFFE53935)  // 紧急-红
        1 -> androidx.compose.ui.graphics.Color(0xFFFB8C00)  // 重要-橙
        else -> QringPalette.textSecondary  // 普通
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 优先级色条
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 32.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(priorityColor)
            )
            Spacer(modifier = Modifier.size(8.dp))

            // 完成勾选
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (item.done) QringPalette.brand else androidx.compose.ui.graphics.Color.Transparent)
                    .border(1.5.dp, if (item.done) QringPalette.brand else QringPalette.textSecondary, CircleShape)
                    .clickable(onClick = onToggleDone),
                contentAlignment = Alignment.Center
            ) {
                if (item.done) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(modifier = Modifier.size(8.dp))

            // 内容
            Column(modifier = Modifier.weight(1f)) {
                if (editing) {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it; onEdit(it) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                } else {
                    Text(
                        text = item.text,
                        fontSize = 13.sp,
                        color = if (item.done) QringPalette.textSecondary else QringPalette.textPrimary,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null
                    )
                }
            }

            // 优先级按钮
            TextButton(
                onClick = { onPriorityChange((item.priority + 1) % 3) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
            ) {
                Text(
                    text = when (item.priority) { 2 -> "紧急"; 1 -> "重要"; else -> "普通" },
                    fontSize = 10.sp,
                    color = priorityColor
                )
            }

            // 删除
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = QringPalette.textSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
