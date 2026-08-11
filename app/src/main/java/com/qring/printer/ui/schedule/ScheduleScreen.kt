package com.qring.printer.ui.schedule

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.data.HistoryPayloadHolder
import com.qring.printer.data.HistoryRepository
import com.qring.printer.model.ConnState
import com.qring.printer.model.PrinterStatus
import com.qring.printer.model.HIST_TYPE_SCHEDULE
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.RasterData
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.WIDTH_BYTES
import com.qring.printer.protocol.packBinaryToRaster
import com.qring.printer.protocol.bitmapToGray
import com.qring.printer.protocol.ditherToBinary
import com.qring.printer.protocol.DitherMode
import com.qring.printer.ui.theme.QringPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import org.json.JSONArray
import org.json.JSONObject

// ── 数据模型 ──────────────────────────────────────────────

data class ScheduleConfig(
    val sectionCount: Int = 8,
    val showSectionTime: Boolean = true,
    val includeWeekend: Boolean = false,
    val title: String = "课程表",
    val sectionTimes: List<String> = listOf(
        "08:00-08:45",
        "08:55-09:40",
        "10:00-10:45",
        "10:55-11:40",
        "14:00-14:45",
        "14:55-15:40",
        "16:00-16:45",
        "19:00-19:45"
    )
)

/**
 * 课程条目：一门课可以跨多个节次
 * sections 存储该课覆盖的节次列表（0-based），合并单元格
 */
data class ScheduleEntry(
    val id: String,
    val day: Int,              // 0=周一 ... 6=周日
    val sections: List<Int>,   // 覆盖的节次列表 (0-based)，如 [2,3] 表示第3-4节
    val courseName: String = "",
    val location: String = "",
    val teacher: String = ""
)

data class ScheduleState(
    val config: ScheduleConfig = ScheduleConfig(),
    val entries: List<ScheduleEntry> = emptyList(),
    val previewBitmap: Bitmap? = null,
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false
)

// ── ViewModel ────────────────────────────────────────────

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val historyRepo = HistoryRepository(application)
    private val _state = MutableStateFlow(ScheduleState())
    val state: StateFlow<ScheduleState> = _state.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    init {
        restoreFromHistoryPayload()
    }

    private fun restoreFromHistoryPayload() {
        val (type, payload) = HistoryPayloadHolder.consumePayload() ?: return
        if (type != HIST_TYPE_SCHEDULE) {
            HistoryPayloadHolder.setPayload(type, payload)
            return
        }
        try {
            val obj = JSONObject(payload)
            val sectionTimes = mutableListOf<String>()
            val timesArray = obj.optJSONArray("sectionTimes")
            if (timesArray != null) {
                for (i in 0 until timesArray.length()) {
                    sectionTimes.add(timesArray.getString(i))
                }
            }
            val entries = mutableListOf<ScheduleEntry>()
            val entriesArray = obj.optJSONArray("entries")
            if (entriesArray != null) {
                for (i in 0 until entriesArray.length()) {
                    val entryObj = entriesArray.getJSONObject(i)
                    val sections = mutableListOf<Int>()
                    val sectionsArray = entryObj.getJSONArray("sections")
                    for (j in 0 until sectionsArray.length()) {
                        sections.add(sectionsArray.getInt(j))
                    }
                    entries.add(ScheduleEntry(
                        id = entryObj.getString("id"),
                        day = entryObj.getInt("day"),
                        sections = sections,
                        courseName = entryObj.optString("courseName", ""),
                        location = entryObj.optString("location", ""),
                        teacher = entryObj.optString("teacher", "")
                    ))
                }
            }
            _state.value = ScheduleState(
                config = ScheduleConfig(
                    sectionCount = obj.optInt("sectionCount", 8),
                    showSectionTime = obj.optBoolean("showSectionTime", true),
                    includeWeekend = obj.optBoolean("includeWeekend", false),
                    title = obj.optString("title", "课程表"),
                    sectionTimes = sectionTimes
                ),
                entries = entries
            )
            updatePreview()
        } catch (e: Exception) { }
    }

    private fun serializeScheduleState(state: ScheduleState): String {
        return JSONObject().apply {
            put("title", state.config.title)
            put("sectionCount", state.config.sectionCount)
            put("showSectionTime", state.config.showSectionTime)
            put("includeWeekend", state.config.includeWeekend)
            val timesArray = JSONArray()
            state.config.sectionTimes.forEach { timesArray.put(it) }
            put("sectionTimes", timesArray)
            val entriesArray = JSONArray()
            state.entries.forEach { entry ->
                val sectionsArray = JSONArray()
                entry.sections.forEach { sectionsArray.put(it) }
                entriesArray.put(JSONObject().apply {
                    put("id", entry.id)
                    put("day", entry.day)
                    put("sections", sectionsArray)
                    put("courseName", entry.courseName)
                    put("location", entry.location)
                    put("teacher", entry.teacher)
                })
            }
            put("entries", entriesArray)
        }.toString()
    }

    val dayLabels: List<String>
        get() = if (_state.value.config.includeWeekend)
            listOf("一", "二", "三", "四", "五", "六", "日")
        else
            listOf("一", "二", "三", "四", "五")

    fun setSectionCount(count: Int) {
        val clamped = count.coerceIn(4, 16)
        _state.update { current ->
            // 移除超出范围的课程条目
            val filteredEntries = current.entries.filter { entry ->
                entry.sections.all { it < clamped }
            }
            current.copy(
                config = current.config.copy(sectionCount = clamped),
                entries = filteredEntries
            )
        }
        updatePreview()
    }

    fun toggleSectionTime(enabled: Boolean) {
        _state.update { it.copy(config = it.config.copy(showSectionTime = enabled)) }
        updatePreview()
    }

    fun toggleWeekend(enabled: Boolean) {
        _state.update { current ->
            val dayCount = if (enabled) 7 else 5
            val filteredEntries = current.entries.filter { it.day < dayCount }
            current.copy(
                config = current.config.copy(includeWeekend = enabled),
                entries = filteredEntries
            )
        }
        updatePreview()
    }

    fun updateSectionTime(index: Int, time: String) {
        _state.update { current ->
            val newTimes = current.config.sectionTimes.toMutableList()
            while (newTimes.size <= index) newTimes.add("")
            newTimes[index] = time
            current.copy(config = current.config.copy(sectionTimes = newTimes))
        }
        updatePreview()
    }

    fun updateTitle(title: String) {
        _state.update { it.copy(config = it.config.copy(title = title)) }
        updatePreview()
    }

    /**
     * 添加或更新课程条目
     * 如果同一日期同一节次已有其他课程覆盖，先移除旧的
     */
    fun upsertEntry(entry: ScheduleEntry) {
        _state.update { current ->
            // 获取该日期涉及的所有节次
            val sectionsToRemove = mutableSetOf<Int>()
            sectionsToRemove.addAll(entry.sections)

            // 移除与该课程冲突的旧条目（同一日期同一节次）
            val filtered = current.entries.filter { existing ->
                !(existing.day == entry.day && existing.sections.any { it in sectionsToRemove })
            }

            // 如果课程名为空则不添加（相当于删除）
            val newEntries = if (entry.courseName.isNotBlank()) {
                filtered + entry
            } else {
                filtered
            }
            current.copy(entries = newEntries)
        }
        updatePreview()
    }

    fun deleteEntry(id: String) {
        _state.update { current ->
            current.copy(entries = current.entries.filter { it.id != id })
        }
        updatePreview()
    }

    /** 获取某天某节的课程条目 */
    fun getEntryAt(day: Int, section: Int): ScheduleEntry? {
        return _state.value.entries.find { it.day == day && section in it.sections }
    }

    /** 获取某天的所有课程条目 */
    fun getEntriesForDay(day: Int): List<ScheduleEntry> {
        return _state.value.entries.filter { it.day == day }.sortedBy { it.sections.minOrNull() ?: 0 }
    }

    fun updatePreview() {
        val state = _state.value
        if (state.entries.isEmpty()) {
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
                    renderSchedulePreview(_state.value)
                }
                _state.value = _state.value.copy(previewBitmap = bitmap)
                old?.let { if (it != bitmap) { delay(150); it.recycle() } }
            } catch (e: Exception) { }
        }
    }

    /** 渲染课程表预览位图 */
    private fun renderSchedulePreview(state: ScheduleState): Bitmap {
        val config = state.config
        val dayCount = if (config.includeWeekend) 7 else 5
        val sectionCount = config.sectionCount

        // 布局参数
        val margin = 8f
        val labelColWidth = 48f  // 节次列宽
        val cellWidth = (WIDTH_DOTS - margin * 2 - labelColWidth) / dayCount
        val titleHeight = 20f
        val headerHeight = 24f
        val sectionHeight = 40f
        val showTime = config.showSectionTime
        val timeHeight = if (showTime) 18f else 0f  // 两行时间需要更多高度
        val rowHeight = sectionHeight + timeHeight

        val gridWidth = (margin + labelColWidth + cellWidth * dayCount + margin).toInt()
        val gridHeight = (margin + titleHeight + headerHeight + rowHeight * sectionCount + margin).toInt()

        val bitmap = Bitmap.createBitmap(WIDTH_DOTS, maxOf(1, gridHeight), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val gridPaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }

        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
        }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val sectionNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 8f
            textAlign = Paint.Align.CENTER
        }

        val left = margin
        val top = margin
        val right = margin + labelColWidth + cellWidth * dayCount
        val bottom = top + titleHeight + headerHeight + rowHeight * sectionCount

        // 绘制标题（上方居中加粗）
        if (config.title.isNotBlank()) {
            val titleCx = (left + right) / 2f
            canvas.drawText(config.title, titleCx, top + titleHeight - 2f, titlePaint)
        }

        // 表头起始 Y
        val headerTop = top + titleHeight

        // 绘制表头背景
        fillPaint.color = Color.rgb(240, 240, 240)
        canvas.drawRect(left, headerTop, right, headerTop + headerHeight, fillPaint)

        // 绘制表头文字
        val dayLabels = if (config.includeWeekend)
            listOf("一", "二", "三", "四", "五", "六", "日")
        else
            listOf("一", "二", "三", "四", "五")

        // 左上角“节”
        canvas.drawText("节", left + labelColWidth / 2f, headerTop + headerHeight - 6f, headerTextPaint)
        for (d in 0 until dayCount) {
            val cx = left + labelColWidth + cellWidth * d + cellWidth / 2f
            canvas.drawText(dayLabels[d], cx, headerTop + headerHeight - 6f, headerTextPaint)
        }

        // 绘制网格线和节次
        for (s in 0 until sectionCount) {
            val rowTop = headerTop + headerHeight + s * rowHeight
            val rowBottom = rowTop + sectionHeight

            // 节次号
            val cy = rowTop + sectionHeight / 2f
            canvas.drawText("${s + 1}", left + labelColWidth / 2f, cy + 5f, sectionNumPaint)
            if (showTime) {
                val time = config.sectionTimes.getOrElse(s) { "" }
                if (time.isNotEmpty()) {
                    // 时间分两行显示：开始时间 / 结束时间
                    val parts = time.split("-", "～", "~")
                    if (parts.size >= 2) {
                        canvas.drawText(parts[0].trim(), left + labelColWidth / 2f, rowBottom + 8f, timePaint)
                        canvas.drawText(parts[1].trim(), left + labelColWidth / 2f, rowBottom + 16f, timePaint)
                    } else {
                        canvas.drawText(time, left + labelColWidth / 2f, rowBottom + 12f, timePaint)
                    }
                }
            }

            // 网格线 - 每行
            canvas.drawLine(left, rowTop, right, rowTop, gridPaint)

            // 每列
            for (d in 0 until dayCount) {
                val colLeft = left + labelColWidth + cellWidth * d
                if (s == 0) {
                    canvas.drawLine(colLeft, headerTop, colLeft, bottom, gridPaint)
                }
            }
        }

        // 最后一行和列的线
        canvas.drawLine(left, bottom, right, bottom, gridPaint)
        canvas.drawLine(left, headerTop, left, bottom, gridPaint)
        canvas.drawLine(right, headerTop, right, bottom, gridPaint)
        canvas.drawLine(left + labelColWidth, headerTop, left + labelColWidth, bottom, gridPaint)

        // 绘制课程（合并单元格）— 按连续区间分组渲染
        for (entry in state.entries) {
            if (entry.day >= dayCount) continue
            val validSections = entry.sections.filter { it < sectionCount }.sorted()
            if (validSections.isEmpty()) continue

            // 将节次列表分组为连续区间，如 [0,1,4] -> [[0,1], [4]]
            val contiguousRanges = mutableListOf<List<Int>>()
            var currentRange = mutableListOf(validSections.first())
            for (i in 1 until validSections.size) {
                if (validSections[i] == validSections[i - 1] + 1) {
                    currentRange.add(validSections[i])
                } else {
                    contiguousRanges.add(currentRange)
                    currentRange = mutableListOf(validSections[i])
                }
            }
            contiguousRanges.add(currentRange)

            val cellLeft = left + labelColWidth + cellWidth * entry.day

            // 为每个连续区间绘制合并单元格
            for (range in contiguousRanges) {
                val minS = range.first()
                val maxS = range.last()
                val cellTop = headerTop + headerHeight + minS * rowHeight
                val cellBottom = headerTop + headerHeight + (maxS + 1) * rowHeight - (if (showTime) timeHeight else 0f)
                val cellHeight = cellBottom - cellTop

                // 背景
                fillPaint.color = Color.rgb(230, 240, 255)
                canvas.drawRect(cellLeft + 1, cellTop + 1, cellLeft + cellWidth - 1, cellBottom - 1, fillPaint)

                // 边框
                fillPaint.color = Color.rgb(100, 149, 237)
                fillPaint.style = Paint.Style.STROKE
                canvas.drawRect(cellLeft + 1, cellTop + 1, cellLeft + cellWidth - 1, cellBottom - 1, fillPaint)
                fillPaint.style = Paint.Style.FILL

                // 课程名（只在每个区间的第一个节次居中显示）
                val textCenterY = cellTop + cellHeight / 2f
                val nameSize = if (cellHeight > 60) 14f else 12f
                textPaint.textSize = nameSize
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textPaint.color = Color.BLACK

                // 截断过长文字
                val maxNameWidth = cellWidth - 4
                var displayName = entry.courseName
                while (textPaint.measureText(displayName) > maxNameWidth && displayName.length > 2) {
                    displayName = displayName.substring(0, displayName.length - 1)
                }
                if (displayName != entry.courseName) {
                    displayName = displayName.substring(0, displayName.length - 1) + "…"
                }

                canvas.drawText(displayName, cellLeft + cellWidth / 2f, textCenterY - 2f, textPaint)

                // 地点
                if (entry.location.isNotBlank() && cellHeight > 30) {
                    textPaint.textSize = 9f
                    textPaint.typeface = Typeface.DEFAULT
                    textPaint.color = Color.GRAY
                    var loc = entry.location
                    while (textPaint.measureText(loc) > maxNameWidth && loc.length > 2) {
                        loc = loc.substring(0, loc.length - 1)
                    }
                    if (loc != entry.location) {
                        loc = loc.substring(0, loc.length - 1) + "…"
                    }
                    canvas.drawText(loc, cellLeft + cellWidth / 2f, textCenterY + 12f, textPaint)
                }
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
        if (state.entries.isEmpty()) {
            _state.value = _state.value.copy(
                resultOk = false,
                resultMessage = "请先添加课程"
            )
            return
        }

        _state.value = _state.value.copy(printing = true, resultMessage = "")

        viewModelScope.launch {
            var previewBmp: Bitmap? = null
            var thumbBitmap: Bitmap? = null
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
                    previewBmp = renderSchedulePreview(_state.value)
                    thumbBitmap = Bitmap.createScaledBitmap(previewBmp!!, 200, Math.round(200f * previewBmp!!.height / previewBmp!!.width), true)
                    val gray = bitmapToGray(previewBmp!!)
                    val binary = ditherToBinary(gray, DitherMode.NONE, 211)
                    previewBmp!!.recycle()
                    previewBmp = null
                    val raster = packBinaryToRaster(binary, gray.width, gray.height)

                    val printResult = withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, null)
                    }

                    if (printResult.ok) {
                        try {
                            val payload = serializeScheduleState(_state.value)
                            historyRepo.saveHistory(HIST_TYPE_SCHEDULE, thumbBitmap!!, payload)
                        } catch (e: Exception) { }
                    }

                    printResult
                }

                _state.value = _state.value.copy(
                    printing = false,
                    resultOk = result.ok,
                    resultMessage = result.message
                )
            } catch (e: Exception) {
                Timber.tag("ScheduleVM").e(e, "print failed")
                _state.value = _state.value.copy(
                    printing = false,
                    resultOk = false,
                    resultMessage = "打印失败：${e.message}"
                )
            } finally {
                try { previewBmp?.recycle() } catch (e: Exception) { }
                try { thumbBitmap?.recycle() } catch (e: Exception) { }
                if (_state.value.printing) {
                    _state.value = _state.value.copy(printing = false)
                }
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
fun ScheduleScreen(navController: NavHostController) {
    val viewModel: ScheduleViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()
    var showConfig by remember { mutableStateOf(false) }
    var editingCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // 自动预览
    LaunchedEffect(
        state.entries.hashCode(),
        state.config.sectionCount,
        state.config.includeWeekend,
        state.config.showSectionTime,
        state.config.title,
        state.config.sectionTimes.hashCode()
    ) {
        delay(400)
        viewModel.updatePreview()
    }

    Scaffold(
        containerColor = QringPalette.pageBg,
        topBar = {
            TopAppBar(
                title = { Text("课程表", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showConfig = true }) {
                        Icon(Icons.Default.Schedule, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = QringPalette.surface,
                    titleContentColor = QringPalette.textPrimary,
                    navigationIconContentColor = QringPalette.textPrimary,
                    actionIconContentColor = QringPalette.brand
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                // 预览
                SchedulePreviewCard(preview = state.previewBitmap, state = state)

                Spacer(modifier = Modifier.height(12.dp))

                // 标题编辑
                OutlinedTextField(
                    value = state.config.title,
                    onValueChange = viewModel::updateTitle,
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 课程表网格
                ScheduleGrid(
                    state = state,
                    dayLabels = viewModel.dayLabels,
                    onCellClick = { day, section -> editingCell = Pair(day, section) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 已添加课程列表
                if (state.entries.isNotEmpty()) {
                    Text(
                        "已添加课程 (${state.entries.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = QringPalette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    state.entries.forEach { entry ->
                        val sectionRange = if (entry.sections.size > 1) {
                            "第${entry.sections.minOrNull()!! + 1}-${entry.sections.maxOrNull()!! + 1}节"
                        } else {
                            "第${entry.sections.first() + 1}节"
                        }
                        val dayLabel = viewModel.dayLabels.getOrElse(entry.day) { "" }
                        EntryRow(
                            entry = entry,
                            dayLabel = "周$dayLabel",
                            sectionRange = sectionRange,
                            onDelete = { viewModel.deleteEntry(entry.id) }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "点击格子编辑课程 · 点击右上角设置节次和星期",
                    fontSize = 12.sp,
                    color = QringPalette.textSecondary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(80.dp))
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
                    enabled = !state.printing && state.entries.isNotEmpty() && connected,
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

        // 配置弹窗
        if (showConfig) {
            ScheduleConfigDialog(
                state = state,
                onDismiss = { showConfig = false },
                onSectionCountChange = viewModel::setSectionCount,
                onToggleSectionTime = viewModel::toggleSectionTime,
                onToggleWeekend = viewModel::toggleWeekend,
                onSectionTimeChange = viewModel::updateSectionTime
            )
        }

        // 编辑单元格弹窗
        editingCell?.let { (day, section) ->
            val existingEntry = viewModel.getEntryAt(day, section)
            CellEditDialog(
                existingEntry = existingEntry,
                day = day,
                section = section,
                dayLabel = viewModel.dayLabels.getOrElse(day) { "" },
                sectionCount = state.config.sectionCount,
                dayCount = viewModel.dayLabels.size,
                allEntries = state.entries,
                onDismiss = { editingCell = null },
                onSave = { entry ->
                    viewModel.upsertEntry(entry)
                    editingCell = null
                },
                onDelete = {
                    existingEntry?.let { viewModel.deleteEntry(it.id) }
                    editingCell = null
                }
            )
        }
    }
}

// ── 预览卡片 ─────────────────────────────────────────────

@Composable
private fun SchedulePreviewCard(
    preview: Bitmap?,
    state: ScheduleState
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (preview != null)
                    "宽 384 点 · 高 ${preview.height} 点 (${String.format("%.1f", preview.height / 8.0)}mm) · ${state.config.sectionCount}节"
                else if (state.entries.isEmpty())
                    "添加课程后自动预览"
                else
                    "正在渲染预览…",
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
                        val contentH = preview.height * scale
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = "课程表预览",
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

// ── 课程表网格 ───────────────────────────────────────────

@Composable
private fun ScheduleGrid(
    state: ScheduleState,
    dayLabels: List<String>,
    onCellClick: (Int, Int) -> Unit
) {
    val dayCount = dayLabels.size
    val sectionCount = state.config.sectionCount
    val showTime = state.config.showSectionTime

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            // 表头行：节次列 + 星期列
            Row(modifier = Modifier.fillMaxWidth()) {
                // 左上角空白
                Box(
                    modifier = Modifier
                        .weight(0.5f)
                        .height(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("节", fontSize = 11.sp, color = QringPalette.textSecondary)
                }
                // 星期
                for (d in 0 until dayCount) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(dayLabels[d], fontSize = 12.sp, fontWeight = FontWeight.Bold, color = QringPalette.textPrimary)
                    }
                }
            }

            // 每一节
            for (s in 0 until sectionCount) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(if (showTime) 56.dp else 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 节次信息
                    Column(
                        modifier = Modifier
                            .weight(0.5f)
                            .padding(end = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "${s + 1}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = QringPalette.brand
                        )
                        if (showTime) {
                            val time = state.config.sectionTimes.getOrElse(s) { "" }
                            if (time.isNotEmpty()) {
                                // 时间分两行显示
                                val parts = time.split("-", "～", "~")
                                if (parts.size >= 2) {
                                    Text(parts[0].trim(), fontSize = 7.sp, color = QringPalette.textSecondary, maxLines = 1)
                                    Text(parts[1].trim(), fontSize = 7.sp, color = QringPalette.textSecondary, maxLines = 1)
                                } else {
                                    Text(time, fontSize = 7.sp, color = QringPalette.textSecondary, maxLines = 1)
                                }
                            }
                        }
                    }
                    // 每天的格子
                    for (d in 0 until dayCount) {
                        // 查找该格子是否有课程
                        val entry = state.entries.find { it.day == d && s in it.sections }
                        if (entry != null) {
                            val sortedSections = entry.sections.sorted()
                            val isFirst = s == sortedSections.first()
                            val isLast = s == sortedSections.last()
                            ScheduleMergedCellBox(
                                entry = entry,
                                isFirst = isFirst,
                                isLast = isLast,
                                modifier = Modifier.weight(1f),
                                onClick = { onCellClick(d, s) }
                            )
                        } else {
                            ScheduleMergedCellBox(
                                entry = null,
                                isFirst = true,
                                isLast = true,
                                modifier = Modifier.weight(1f),
                                onClick = { onCellClick(d, s) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleMergedCellBox(
    entry: ScheduleEntry?,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val hasContent = entry != null && entry.courseName.isNotBlank()
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(
                topStart = if (isFirst) 4.dp else 0.dp,
                topEnd = if (isFirst) 4.dp else 0.dp,
                bottomStart = if (isLast) 4.dp else 0.dp,
                bottomEnd = if (isLast) 4.dp else 0.dp
            ))
            .background(if (hasContent) QringPalette.brand.copy(alpha = 0.12f) else QringPalette.paper)
            .border(0.5.dp, QringPalette.paperEdge, RoundedCornerShape(
                topStart = if (isFirst) 4.dp else 0.dp,
                topEnd = if (isFirst) 4.dp else 0.dp,
                bottomStart = if (isLast) 4.dp else 0.dp,
                bottomEnd = if (isLast) 4.dp else 0.dp
            ))
            .clickable(onClick = onClick)
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        if (hasContent && isFirst) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = entry!!.courseName,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = QringPalette.textPrimary,
                    maxLines = 2,
                    textAlign = TextAlign.Center
                )
                if (entry.location.isNotBlank()) {
                    Text(
                        text = entry.location,
                        fontSize = 7.sp,
                        color = QringPalette.textSecondary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ── 配置弹窗 ─────────────────────────────────────────────

@Composable
private fun ScheduleConfigDialog(
    state: ScheduleState,
    onDismiss: () -> Unit,
    onSectionCountChange: (Int) -> Unit,
    onToggleSectionTime: (Boolean) -> Unit,
    onToggleWeekend: (Boolean) -> Unit,
    onSectionTimeChange: (Int, String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("课程表设置") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 节次数 - 4到16
                Text("节次数: ${state.config.sectionCount}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 显示 4-16 的按钮，分两行
                    listOf(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16).forEach { count ->
                        TextButton(
                            onClick = { onSectionCountChange(count) },
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (state.config.sectionCount == count) QringPalette.brand else QringPalette.surfaceSunken,
                                contentColor = if (state.config.sectionCount == count) androidx.compose.ui.graphics.Color.White else QringPalette.textPrimary
                            )
                        ) {
                            Text("$count", fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 显示节次时间
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示节次时间", fontSize = 14.sp)
                    Switch(checked = state.config.showSectionTime, onCheckedChange = onToggleSectionTime)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 包含周末
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("包含周六日", fontSize = 14.sp)
                    Switch(checked = state.config.includeWeekend, onCheckedChange = onToggleWeekend)
                }

                // 节次时间编辑
                if (state.config.showSectionTime) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("节次时间", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.textSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (i in 0 until state.config.sectionCount) {
                            OutlinedTextField(
                                value = state.config.sectionTimes.getOrElse(i) { "" },
                                onValueChange = { onSectionTimeChange(i, it) },
                                label = { Text("第${i + 1}节", fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

// ── 单元格编辑弹窗（支持多节次选择+合并） ─────────────────

@Composable
private fun CellEditDialog(
    existingEntry: ScheduleEntry?,
    day: Int,
    section: Int,
    dayLabel: String,
    sectionCount: Int,
    dayCount: Int,
    allEntries: List<ScheduleEntry>,
    onDismiss: () -> Unit,
    onSave: (ScheduleEntry) -> Unit,
    onDelete: () -> Unit
) {
    var courseName by remember { mutableStateOf(existingEntry?.courseName ?: "") }
    var location by remember { mutableStateOf(existingEntry?.location ?: "") }
    var teacher by remember { mutableStateOf(existingEntry?.teacher ?: "") }

    // 选中的节次列表
    var selectedSections by remember {
        mutableStateOf(existingEntry?.sections?.toSet() ?: setOf(section))
    }

    // 该天已被其他课程占用的节次（不能选）
    val occupiedSections = allEntries
        .filter { it.day == day && it.id != existingEntry?.id }
        .flatMap { it.sections }
        .toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("周$dayLabel · 选择节次") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 节次选择网格
                Text("选择节次（可多选，连续节次将合并单元格）", fontSize = 12.sp, color = QringPalette.textSecondary)
                Spacer(modifier = Modifier.height(8.dp))

                // 节次按钮网格，每行7个
                val rows = (0 until sectionCount).chunked(7)
                rows.forEach { rowSections ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowSections.forEach { s ->
                            val isSelected = s in selectedSections
                            val isOccupied = s in occupiedSections
                            TextButton(
                                onClick = {
                                    if (!isOccupied) {
                                        selectedSections = if (isSelected) {
                                            selectedSections - s
                                        } else {
                                            selectedSections + s
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isOccupied,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = when {
                                        isSelected -> QringPalette.brand
                                        isOccupied -> QringPalette.surfaceSunken.copy(alpha = 0.3f)
                                        else -> QringPalette.surfaceSunken
                                    },
                                    contentColor = when {
                                        isSelected -> androidx.compose.ui.graphics.Color.White
                                        isOccupied -> QringPalette.textSecondary.copy(alpha = 0.3f)
                                        else -> QringPalette.textPrimary
                                    },
                                    disabledContainerColor = QringPalette.surfaceSunken.copy(alpha = 0.3f),
                                    disabledContentColor = QringPalette.textSecondary.copy(alpha = 0.3f)
                                )
                            ) {
                                Text("第${s + 1}节", fontSize = 10.sp, maxLines = 1)
                            }
                        }
                        // 填充空白
                        repeat(7 - rowSections.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = courseName,
                    onValueChange = { courseName = it },
                    label = { Text("课程名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("上课地点") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("授课教师") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (selectedSections.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val sorted = selectedSections.sorted()
                    val rangeText = if (sorted.size > 1) {
                        "第${sorted.first() + 1}-${sorted.last() + 1}节 (${sorted.size}节)"
                    } else {
                        "第${sorted.first() + 1}节"
                    }
                    Text(
                        "已选: $rangeText",
                        fontSize = 12.sp,
                        color = QringPalette.brand,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (selectedSections.isNotEmpty() && courseName.isNotBlank()) {
                    onSave(ScheduleEntry(
                        id = existingEntry?.id ?: java.util.UUID.randomUUID().toString(),
                        day = day,
                        sections = selectedSections.sorted(),
                        courseName = courseName.trim(),
                        location = location.trim(),
                        teacher = teacher.trim()
                    ))
                }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (existingEntry != null) {
                    TextButton(onClick = onDelete) { Text("删除", color = androidx.compose.ui.graphics.Color.Red) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

// ── 课程条目行 ───────────────────────────────────────────

@Composable
private fun EntryRow(
    entry: ScheduleEntry,
    dayLabel: String,
    sectionRange: String,
    onDelete: () -> Unit
) {
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
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$dayLabel $sectionRange",
                        fontSize = 11.sp,
                        color = QringPalette.brand,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = entry.courseName,
                        fontSize = 13.sp,
                        color = QringPalette.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (entry.location.isNotBlank() || entry.teacher.isNotBlank()) {
                    val subText = listOfNotNull(
                        entry.location.takeIf { it.isNotBlank() },
                        entry.teacher.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                    Text(subText, fontSize = 11.sp, color = QringPalette.textSecondary)
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = QringPalette.textSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
