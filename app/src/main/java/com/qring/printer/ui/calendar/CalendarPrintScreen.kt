package com.qring.printer.ui.calendar

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.model.ConnState
import com.qring.printer.model.PrinterStatus
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.RasterData
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.WIDTH_BYTES
import com.qring.printer.protocol.packBinaryToRaster
import com.qring.printer.protocol.bitmapToGray
import com.qring.printer.protocol.ditherToBinary
import com.qring.printer.protocol.DitherMode
import com.qring.printer.ui.common.PrintWarningDialog
import com.qring.printer.ui.theme.QringPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ── 数据模型 ──────────────────────────────────────────────

data class CalendarEvent(
    val id: Long,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val location: String?,
    val description: String?,
    val done: Boolean = false
)

data class CalendarPrintState(
    val events: List<CalendarEvent> = emptyList(),
    val selectedDates: List<Long> = emptyList(),
    val title: String = "",
    val hasPermission: Boolean = false,
    val loading: Boolean = false,
    val previewBitmap: Bitmap? = null,
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false
)

// ── ViewModel ────────────────────────────────────────────

class CalendarPrintViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val _state = MutableStateFlow(CalendarPrintState())
    val state: StateFlow<CalendarPrintState> = _state.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    fun setPermission(granted: Boolean) {
        _state.update { it.copy(hasPermission = granted) }
    }

    fun toggleDate(timestamp: Long) {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val normalized = cal.timeInMillis
        _state.update { current ->
            val dates = current.selectedDates.toMutableList()
            if (dates.contains(normalized)) {
                dates.remove(normalized)
            } else {
                if (dates.size >= 5) {
                    // 最多5天
                    return@update current
                }
                dates.add(normalized)
                dates.sort()
            }
            current.copy(selectedDates = dates)
        }
        loadEventsForDates()
        updateTitle()
        updatePreview()
    }

    fun clearDates() {
        _state.update { it.copy(selectedDates = emptyList(), events = emptyList()) }
        updatePreview()
    }

    private fun updateTitle() {
        val dates = _state.value.selectedDates
        if (dates.isEmpty()) {
            _state.update { it.copy(title = "") }
            return
        }
        val fmt = SimpleDateFormat("MM月dd日", Locale.getDefault())
        if (dates.size == 1) {
            _state.update { it.copy(title = "${fmt.format(Date(dates[0]))}日程") }
        } else {
            _state.update { it.copy(title = "${fmt.format(Date(dates[0]))}-${fmt.format(Date(dates.last()))}日程") }
        }
    }

    fun updateTitle(title: String) {
        _state.update { it.copy(title = title) }
        updatePreview()
    }

    fun toggleEventDone(eventId: Long) {
        _state.update { current ->
            current.copy(events = current.events.map { e ->
                if (e.id == eventId) e.copy(done = !e.done) else e
            })
        }
        updatePreview()
    }

    fun updateEventTitle(eventId: Long, newTitle: String) {
        _state.update { current ->
            current.copy(events = current.events.map { e ->
                if (e.id == eventId) e.copy(title = newTitle) else e
            })
        }
        updatePreview()
    }

    fun loadEventsForDates() {
        val context = getApplication<Application>()
        val dates = _state.value.selectedDates
        if (dates.isEmpty()) {
            _state.update { it.copy(events = emptyList()) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val allEvents = mutableListOf<CalendarEvent>()
            withContext(Dispatchers.IO) {
                dates.forEach { date ->
                    allEvents.addAll(readCalendarEvents(context, date))
                }
            }
            _state.update { it.copy(events = allEvents.sortedBy { it.startTime }, loading = false) }
            updatePreview()
        }
    }

    fun syncEventDoneToSystem(context: android.content.Context, event: CalendarEvent) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(CalendarContract.Events.TITLE, if (event.done) "✓ ${event.title}" else event.title.removePrefix("✓ "))
                }
                resolver.update(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id),
                    values, null, null
                )
            } catch (_: Exception) { }
        }
    }

    fun syncEventTitleToSystem(context: android.content.Context, event: CalendarEvent) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    put(CalendarContract.Events.TITLE, event.title)
                }
                context.contentResolver.update(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id),
                    values, null, null
                )
            } catch (_: Exception) { }
        }
    }

    fun addEvent(title: String, dateMillis: Long, startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, location: String) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = dateMillis
                    set(Calendar.HOUR_OF_DAY, startHour)
                    set(Calendar.MINUTE, startMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startTime = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, endHour)
                cal.set(Calendar.MINUTE, endMinute)
                val endTime = cal.timeInMillis

                val calendarId = getPrimaryCalendarId(context)
                if (calendarId == -1L) {
                    _state.update { it.copy(resultMessage = "未找到可写入的系统日历", resultOk = false) }
                    return@launch
                }

                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DTSTART, startTime)
                    put(CalendarContract.Events.DTEND, endTime)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "Asia/Shanghai")
                    if (location.isNotEmpty()) put(CalendarContract.Events.EVENT_LOCATION, location)
                }

                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                if (uri != null) {
                    withContext(Dispatchers.Main) {
                        loadEventsForDates()
                    }
                    _state.update { it.copy(resultMessage = "日程已添加到系统日历", resultOk = true) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(resultMessage = "添加失败：${e.message}", resultOk = false) }
            }
        }
    }

    fun deleteEventFromSystem(context: android.content.Context, eventId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rows = context.contentResolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                    null, null
                )
                if (rows > 0) {
                    _state.update { current ->
                        current.copy(events = current.events.filter { it.id != eventId })
                    }
                    withContext(Dispatchers.Main) {
                        updatePreview()
                    }
                    _state.update { it.copy(resultMessage = "日程已从系统日历删除", resultOk = true) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(resultMessage = "删除失败：${e.message}", resultOk = false) }
            }
        }
    }

    private fun getPrimaryCalendarId(context: android.content.Context): Long {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection, selection, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0)
                }
            }
        } catch (_: Exception) { }
        return -1L
    }

    private fun readCalendarEvents(context: android.content.Context, dateMillis: Long): List<CalendarEvent> {
        val result = mutableListOf<CalendarEvent>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = dateMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayStart = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val dayEnd = cal.timeInMillis

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ?"
        val selectionArgs = arrayOf(dayStart.toString(), dayEnd.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(CalendarContract.Events._ID)
                val titleCol = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val startCol = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                val endCol = cursor.getColumnIndex(CalendarContract.Events.DTEND)
                val locCol = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
                val descCol = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleCol) ?: ""
                    result.add(CalendarEvent(
                        id = cursor.getLong(idCol),
                        title = title,
                        startTime = cursor.getLong(startCol),
                        endTime = if (endCol >= 0) cursor.getLong(endCol) else 0L,
                        location = if (locCol >= 0) cursor.getString(locCol) else null,
                        description = if (descCol >= 0) cursor.getString(descCol) else null,
                        done = title.startsWith("✓ ")
                    ))
                }
            }
        } catch (_: Exception) { }
        return result
    }

    fun updatePreview() {
        val state = _state.value
        if (state.selectedDates.isEmpty()) {
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
                    renderCalendarPreview(_state.value)
                }
                _state.value = _state.value.copy(previewBitmap = bitmap)
                old?.let { if (it != bitmap) { delay(150); it.recycle() } }
            } catch (e: Exception) { }
        }
    }

    /** 渲染日程预览：每天一组，竖向排列 */
    private fun renderCalendarPreview(state: CalendarPrintState): Bitmap {
        val margin = 12f
        val titleSize = 26f
        val dateHeaderSize = 22f
        val eventSize = 20f
        val lineSpacing = 4f
        val dayGap = 20f
        val timeColWidth = 50f

        data class RenderLine(val text: String, val textSize: Float, val bold: Boolean, val color: Int, val yOffset: Float, val strikeThrough: Boolean = false)

        val lines = mutableListOf<RenderLine>()
        var currentY = margin

        // 总标题
        if (state.title.isNotBlank()) {
            lines.add(RenderLine(state.title, titleSize, true, Color.BLACK, currentY))
            currentY += titleSize + lineSpacing + dayGap
        }

        val dateFmt = SimpleDateFormat("MM月dd日 EEEE", Locale.getDefault())
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        // 按日期分组事件
        val eventsByDate = state.selectedDates.associateWith { date ->
            val cal = Calendar.getInstance().apply {
                timeInMillis = date
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val dayStart = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val dayEnd = cal.timeInMillis
            state.events.filter { it.startTime >= dayStart && it.startTime < dayEnd }
        }

        for (date in state.selectedDates) {
            // 日期标题
            lines.add(RenderLine(dateFmt.format(Date(date)), dateHeaderSize, true, Color.BLACK, currentY))
            currentY += dateHeaderSize + lineSpacing + 4f

            val dayEvents = eventsByDate[date] ?: emptyList()
            if (dayEvents.isEmpty()) {
                lines.add(RenderLine("  无日程", eventSize, false, Color.GRAY, currentY))
                currentY += eventSize + lineSpacing
            } else {
                for (event in dayEvents) {
                    val timeStr = timeFmt.format(Date(event.startTime))
                    val titleText = event.title.removePrefix("✓ ")
                    val eventText = "$timeStr  $titleText"
                    val color = if (event.done) Color.GRAY else Color.BLACK
                    lines.add(RenderLine(eventText, eventSize, false, color, currentY, event.done))
                    if (event.location != null && event.location!!.isNotEmpty()) {
                        currentY += eventSize + lineSpacing
                        lines.add(RenderLine("      📍 ${event.location}", 16f, false, Color.GRAY, currentY))
                    }
                    currentY += eventSize + lineSpacing
                }
            }
            currentY += dayGap
        }

        val totalHeight = (currentY + margin).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(WIDTH_DOTS, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val dividerPaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }

        for ((index, line) in lines.withIndex()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = line.textSize
                color = line.color
                typeface = if (line.bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
                textAlign = Paint.Align.LEFT
                isStrikeThruText = line.strikeThrough
            }
            val fm = paint.fontMetrics
            val y = line.yOffset - fm.ascent
            canvas.drawText(line.text, margin, y, paint)

            // 在日期标题后面画一条短分隔线
            if (line.bold && line.textSize == dateHeaderSize && index < lines.size - 1) {
                val textWidth = paint.measureText(line.text)
                val dividerY = line.yOffset + line.textSize + 2f
                canvas.drawLine(margin, dividerY, WIDTH_DOTS - margin, dividerY, dividerPaint)
            }
        }

        return bitmap
    }

    private fun renderCalendarRaster(state: CalendarPrintState): RasterData {
        val previewBmp = renderCalendarPreview(state)
        val gray = bitmapToGray(previewBmp)
        val binary = ditherToBinary(gray, DitherMode.NONE, 211)
        previewBmp.recycle()
        return packBinaryToRaster(binary, gray.width, gray.height)
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
        if (state.selectedDates.isEmpty()) {
            _state.value = _state.value.copy(
                resultOk = false,
                resultMessage = "请先选择日期"
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
                    val raster = renderCalendarRaster(_state.value)
                    withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, null)
                    }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarPrintScreen(navController: NavHostController) {
    val viewModel: CalendarPrintViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()
    val context = LocalContext.current
    var showDatePicker by remember { mutableStateOf(false) }
    var showAddEventDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.READ_CALENDAR] == true
        viewModel.setPermission(granted)
        if (granted) {
            viewModel.loadEventsForDates()
        }
    }

    LaunchedEffect(Unit) {
        val hasRead = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        viewModel.setPermission(hasRead)
        if (!hasRead) {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        }
    }

    Scaffold(
        containerColor = QringPalette.pageBg,
        topBar = {
            TopAppBar(
                title = { Text("日程打印", fontWeight = FontWeight.Bold) },
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
                // 日期选择
                DateSelector(
                    selectedDates = state.selectedDates,
                    onAddDate = { showDatePicker = true },
                    onClearDates = viewModel::clearDates,
                    onRemoveDate = { viewModel.toggleDate(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 标题编辑
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::updateTitle,
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 预览
                CalendarPreviewCard(preview = state.previewBitmap, state = state)

                Spacer(modifier = Modifier.height(12.dp))

                // 日程列表
                if (state.hasPermission) {
                    if (state.loading) {
                        Text("加载中...", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = QringPalette.textSecondary)
                    } else if (state.events.isEmpty() && state.selectedDates.isNotEmpty()) {
                        Text("所选日期无日程", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = QringPalette.textSecondary)
                    } else if (state.selectedDates.isNotEmpty()) {
                        // 按日期分组显示
                        val dateFmt = SimpleDateFormat("MM月dd日 EEEE", Locale.getDefault())
                        state.selectedDates.forEach { date ->
                            val cal = Calendar.getInstance().apply {
                                timeInMillis = date
                                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                            }
                            val dayStart = cal.timeInMillis
                            cal.add(Calendar.DAY_OF_MONTH, 1)
                            val dayEnd = cal.timeInMillis
                            val dayEvents = state.events.filter { it.startTime >= dayStart && it.startTime < dayEnd }

                            Text(
                                dateFmt.format(Date(date)),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = QringPalette.brand
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (dayEvents.isEmpty()) {
                                Text("  无日程", fontSize = 12.sp, color = QringPalette.textSecondary)
                            } else {
                                dayEvents.forEach { event ->
                                    CalendarEventCard(
                                        event = event,
                                        onToggleDone = {
                                            viewModel.toggleEventDone(event.id)
                                            viewModel.syncEventDoneToSystem(context, event.copy(done = !event.done))
                                        },
                                        onEditTitle = {
                                            viewModel.updateEventTitle(event.id, it)
                                            viewModel.syncEventTitleToSystem(context, event.copy(title = it))
                                        },
                                        onDelete = { viewModel.deleteEventFromSystem(context, event.id) }
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                } else {
                    Text("需要日历权限才能读取日程", color = QringPalette.textSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }) {
                        Text("授予权限")
                    }
                }

                // 添加日程按钮
                if (state.hasPermission && state.selectedDates.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showAddEventDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = QringPalette.surface,
                            contentColor = QringPalette.brand
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("添加日程", fontSize = 14.sp)
                    }
                }

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
                    enabled = !state.printing && state.selectedDates.isNotEmpty() && connected,
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

    // 日期选择器弹窗
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        viewModel.toggleDate(it)
                    }
                    showDatePicker = false
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // 添加日程弹窗
    if (showAddEventDialog && state.selectedDates.isNotEmpty()) {
        AddEventDialog(
            selectedDates = state.selectedDates,
            onDismiss = { showAddEventDialog = false },
            onAdd = { title, date, sH, sM, eH, eM, loc ->
                viewModel.addEvent(title, date, sH, sM, eH, eM, loc)
                showAddEventDialog = false
            }
        )
    }

    // 打印前状态检查弹窗
    PrintWarningDialog(onGoBack = { navController.popBackStack() })
}

// ── 添加日程弹窗 ─────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddEventDialog(
    selectedDates: List<Long>,
    onDismiss: () -> Unit,
    onAdd: (String, Long, Int, Int, Int, Int, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var selectedDateIndex by remember { mutableStateOf(0) }
    var startTime by remember { mutableStateOf("08:00") }
    var endTime by remember { mutableStateOf("09:00") }
    var location by remember { mutableStateOf("") }

    val dateFmt = SimpleDateFormat("MM月dd日 EEEE", Locale.getDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加日程") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("选择日期", fontSize = 13.sp, color = QringPalette.textSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selectedDates.forEachIndexed { index, date ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (index == selectedDateIndex) QringPalette.brand.copy(alpha = 0.12f) else QringPalette.surfaceSunken)
                                .clickable { selectedDateIndex = index }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = dateFmt.format(Date(date)),
                                fontSize = 12.sp,
                                color = if (index == selectedDateIndex) QringPalette.brand else QringPalette.textSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = startTime,
                        onValueChange = { startTime = it },
                        label = { Text("开始 HH:mm") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                    )
                    OutlinedTextField(
                        value = endTime,
                        onValueChange = { endTime = it },
                        label = { Text("结束 HH:mm") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isNotBlank()) {
                    val parts = startTime.split(":")
                    val sH = parts.getOrNull(0)?.toIntOrNull() ?: 8
                    val sM = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    val endParts = endTime.split(":")
                    val eH = endParts.getOrNull(0)?.toIntOrNull() ?: 9
                    val eM = endParts.getOrNull(1)?.toIntOrNull() ?: 0
                    val date = selectedDates.getOrElse(selectedDateIndex) { selectedDates.firstOrNull() ?: return@TextButton }
                    onAdd(title.trim(), date, sH, sM, eH, eM, location.trim())
                }
            }) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ── 日期选择器 ───────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DateSelector(
    selectedDates: List<Long>,
    onAddDate: () -> Unit,
    onClearDates: () -> Unit,
    onRemoveDate: (Long) -> Unit
) {
    val dateFmt = SimpleDateFormat("MM/dd", Locale.getDefault())
    val weekFmt = SimpleDateFormat("EEE", Locale.getDefault())

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
                Text(
                    "已选日期 (${selectedDates.size}/5)",
                    fontSize = 13.sp,
                    color = QringPalette.textSecondary
                )
                Row {
                    IconButton(onClick = onAddDate, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = "选择日期", tint = QringPalette.brand, modifier = Modifier.size(20.dp))
                    }
                    if (selectedDates.isNotEmpty()) {
                        IconButton(onClick = onClearDates, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = "清空", tint = QringPalette.textSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (selectedDates.isEmpty()) {
                Text("点击日历图标选择日期（最多5天）", fontSize = 12.sp, color = QringPalette.textSecondary)
            } else {
                // 已选日期芯片
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selectedDates.forEach { date ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(QringPalette.brand.copy(alpha = 0.12f))
                                .clickable { onRemoveDate(date) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${dateFmt.format(Date(date))} ${weekFmt.format(Date(date))}",
                                fontSize = 12.sp,
                                color = QringPalette.brand,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "移除",
                                tint = QringPalette.brand,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 预览卡片 ─────────────────────────────────────────────

@Composable
private fun CalendarPreviewCard(
    preview: Bitmap?,
    state: CalendarPrintState
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (preview != null)
                    "宽 384 点 · 高 ${preview.height} 点 (${String.format("%.1f", preview.height / 8.0)}mm) · ${state.selectedDates.size}天日程"
                else if (state.selectedDates.isEmpty())
                    "选择日期后自动预览"
                else
                    "正在渲染预览…",
                fontSize = 11.sp,
                color = QringPalette.textSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
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
                                .height(180.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = "日程预览",
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

// ── 日程卡片 ─────────────────────────────────────────────

@Composable
private fun CalendarEventCard(
    event: CalendarEvent,
    onToggleDone: () -> Unit,
    onEditTitle: (String) -> Unit,
    onDelete: () -> Unit
) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    var editing by remember { mutableStateOf(false) }
    var editTitle by remember(event.id) { mutableStateOf(event.title.removePrefix("✓ ")) }

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
            // 时间
            Column(modifier = Modifier.width(46.dp)) {
                Text(timeFormat.format(Date(event.startTime)), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = QringPalette.brand)
            }

            // 标题
            Column(modifier = Modifier.weight(1f)) {
                if (editing) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it; onEditTitle(it) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                    )
                } else {
                    Text(
                        text = event.title.removePrefix("✓ "),
                        fontSize = 12.sp,
                        color = if (event.done) QringPalette.textSecondary else QringPalette.textPrimary,
                        textDecoration = if (event.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                    )
                    if (event.location != null && event.location!!.isNotEmpty()) {
                        Text(event.location!!, fontSize = 10.sp, color = QringPalette.textSecondary)
                    }
                }
            }

            // 编辑按钮
            IconButton(onClick = { editing = !editing }, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(14.dp), tint = QringPalette.textSecondary)
            }

            // 完成标记
            IconButton(onClick = onToggleDone, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "标记完成",
                    modifier = Modifier.size(16.dp),
                    tint = if (event.done) QringPalette.brand else QringPalette.textSecondary
                )
            }

            // 删除
            IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(14.dp), tint = QringPalette.textSecondary)
            }
        }
    }
}
