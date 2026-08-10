package com.qring.print.ui.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.qring.print.ui.theme.QringPalette
import kotlinx.coroutines.Dispatchers
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
    val selectedDate: Long = System.currentTimeMillis(),
    val title: String = "",
    val hasPermission: Boolean = false,
    val loading: Boolean = false
)

// ── ViewModel ────────────────────────────────────────────

class CalendarPrintViewModel : ViewModel() {
    private val _state = MutableStateFlow(CalendarPrintState())
    val state: StateFlow<CalendarPrintState> = _state.asStateFlow()

    fun setPermission(granted: Boolean) {
        _state.update { it.copy(hasPermission = granted) }
    }

    fun setSelectedDate(timestamp: Long) {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dateStr = SimpleDateFormat("MM月dd日", Locale.getDefault()).format(Date(cal.timeInMillis))
        _state.update {
            it.copy(
                selectedDate = cal.timeInMillis,
                title = "${dateStr}日程"
            )
        }
    }

    fun updateTitle(title: String) {
        _state.update { it.copy(title = title) }
    }

    fun toggleEventDone(eventId: Long) {
        _state.update { current ->
            current.copy(events = current.events.map { e ->
                if (e.id == eventId) e.copy(done = !e.done) else e
            })
        }
    }

    fun updateEventTitle(eventId: Long, newTitle: String) {
        _state.update { current ->
            current.copy(events = current.events.map { e ->
                if (e.id == eventId) e.copy(title = newTitle) else e
            })
        }
    }

    fun loadEvents(context: android.content.Context) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val events = withContext(Dispatchers.IO) {
                readCalendarEvents(context, _state.value.selectedDate)
            }
            // 初始化标题
            if (_state.value.title.isBlank()) {
                setSelectedDate(_state.value.selectedDate)
            }
            _state.update { it.copy(events = events, loading = false) }
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
}

// ── 屏幕 ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarPrintScreen(navController: NavHostController) {
    val viewModel: CalendarPrintViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.READ_CALENDAR] == true
        viewModel.setPermission(granted)
        if (granted) {
            viewModel.loadEvents(context)
        }
    }

    LaunchedEffect(Unit) {
        val hasRead = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        viewModel.setPermission(hasRead)
        if (hasRead) {
            viewModel.loadEvents(context)
        } else {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            // 日期选择
            DateSelector(
                selectedDate = state.selectedDate,
                onDateChange = { viewModel.setSelectedDate(it); viewModel.loadEvents(context) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 标题编辑
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::updateTitle,
                label = { Text("卡片标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 日程列表
            if (state.hasPermission) {
                if (state.loading) {
                    Text("加载中...", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = QringPalette.textSecondary)
                } else if (state.events.isEmpty()) {
                    Text("该日无日程", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = QringPalette.textSecondary)
                } else {
                    Text("当日日程 (${state.events.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = QringPalette.textPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    state.events.forEach { event ->
                        CalendarEventCard(
                            event = event,
                            onToggleDone = {
                                viewModel.toggleEventDone(event.id)
                                viewModel.syncEventDoneToSystem(context, event.copy(done = !event.done))
                            },
                            onEditTitle = { viewModel.updateEventTitle(event.id, it) }
                        )
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

            Spacer(modifier = Modifier.height(16.dp))

            // 打印按钮
            Button(
                onClick = { /* TODO: 生成打印数据 */ },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.events.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("打印", fontSize = 15.sp)
            }
        }
    }
}

// ── 日期选择器 ───────────────────────────────────────────

@Composable
private fun DateSelector(
    selectedDate: Long,
    onDateChange: (Long) -> Unit
) {
    val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.getDefault())
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val maxDate = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 30) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("选择日期（未来30天）", fontSize = 13.sp, color = QringPalette.textSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                var offset = 0
                while (offset <= 30) {
                    val cal = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, offset) }
                    val isSelected = cal.timeInMillis == selectedDate
                    TextButton(
                        onClick = { onDateChange(cal.timeInMillis) },
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) QringPalette.brand else QringPalette.surfaceSunken)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}",
                            fontSize = 11.sp,
                            color = if (isSelected) androidx.compose.ui.graphics.Color.White else QringPalette.textPrimary
                        )
                    }
                    offset++
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = dateFormat.format(Date(selectedDate)),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = QringPalette.brand
            )
        }
    }
}

// ── 日程卡片 ─────────────────────────────────────────────

@Composable
private fun CalendarEventCard(
    event: CalendarEvent,
    onToggleDone: () -> Unit,
    onEditTitle: (String) -> Unit
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
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 时间
            Column(modifier = Modifier.width(50.dp)) {
                Text(timeFormat.format(Date(event.startTime)), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = QringPalette.brand)
            }

            // 标题
            Column(modifier = Modifier.weight(1f)) {
                if (editing) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it; onEditTitle(it) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                } else {
                    Text(
                        text = event.title.removePrefix("✓ "),
                        fontSize = 13.sp,
                        color = if (event.done) QringPalette.textSecondary else QringPalette.textPrimary,
                        textDecoration = if (event.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                    )
                    if (event.location != null && event.location!!.isNotEmpty()) {
                        Text(event.location!!, fontSize = 11.sp, color = QringPalette.textSecondary)
                    }
                }
            }

            // 编辑按钮
            IconButton(onClick = { editing = !editing }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(16.dp), tint = QringPalette.textSecondary)
            }

            // 完成标记
            IconButton(onClick = onToggleDone, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "标记完成",
                    modifier = Modifier.size(18.dp),
                    tint = if (event.done) QringPalette.brand else QringPalette.textSecondary
                )
            }
        }
    }
}
