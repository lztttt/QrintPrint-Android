package com.qring.print.ui.schedule

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.qring.print.ui.theme.QringPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// ── 数据模型 ──────────────────────────────────────────────

data class ScheduleConfig(
    val sectionCount: Int = 8,
    val showSectionTime: Boolean = true,
    val includeWeekend: Boolean = false,
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

data class ScheduleCell(
    val day: Int,      // 0=周一 ... 6=周日
    val section: Int,  // 第几节 (0-based)
    val courseName: String = "",
    val location: String = "",
    val teacher: String = ""
)

data class ScheduleState(
    val config: ScheduleConfig = ScheduleConfig(),
    val cells: Map<Pair<Int, Int>, ScheduleCell> = emptyMap()
)

// ── ViewModel ────────────────────────────────────────────

class ScheduleViewModel : ViewModel() {
    private val _state = MutableStateFlow(ScheduleState())
    val state: StateFlow<ScheduleState> = _state.asStateFlow()

    val dayLabels: List<String>
        get() = if (_state.value.config.includeWeekend)
            listOf("一", "二", "三", "四", "五", "六", "日")
        else
            listOf("一", "二", "三", "四", "五")

    fun updateConfig(config: ScheduleConfig) {
        _state.update { it.copy(config = config) }
    }

    fun setSectionCount(count: Int) {
        val clamped = count.coerceIn(1, 20)
        _state.update { current ->
            current.copy(config = current.config.copy(sectionCount = clamped))
        }
    }

    fun toggleSectionTime(enabled: Boolean) {
        _state.update { it.copy(config = it.config.copy(showSectionTime = enabled)) }
    }

    fun toggleWeekend(enabled: Boolean) {
        _state.update { it.copy(config = it.config.copy(includeWeekend = enabled)) }
    }

    fun updateSectionTime(index: Int, time: String) {
        _state.update { current ->
            val newTimes = current.config.sectionTimes.toMutableList()
            while (newTimes.size <= index) newTimes.add("")
            newTimes[index] = time
            current.copy(config = current.config.copy(sectionTimes = newTimes))
        }
    }

    fun updateCell(day: Int, section: Int, cell: ScheduleCell) {
        _state.update { current ->
            val newCells = current.cells.toMutableMap()
            if (cell.courseName.isBlank() && cell.location.isBlank() && cell.teacher.isBlank()) {
                newCells.remove(Pair(day, section))
            } else {
                newCells[Pair(day, section)] = cell
            }
            current.copy(cells = newCells)
        }
    }

    fun getCell(day: Int, section: Int): ScheduleCell {
        return _state.value.cells[Pair(day, section)] ?: ScheduleCell(day, section)
    }
}

// ── 屏幕 ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(navController: NavHostController) {
    val viewModel: ScheduleViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var showConfig by remember { mutableStateOf(false) }
    var editingCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            // 课程表网格
            ScheduleGrid(
                state = state,
                dayLabels = viewModel.dayLabels,
                onCellClick = { day, section -> editingCell = Pair(day, section) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 提示
            Text(
                text = "点击格子编辑课程 · 点击右上角设置节次和星期",
                fontSize = 12.sp,
                color = QringPalette.textSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
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
            val cell = viewModel.getCell(day, section)
            CellEditDialog(
                cell = cell,
                dayLabel = viewModel.dayLabels.getOrElse(day) { "" },
                sectionIndex = section + 1,
                onDismiss = { editingCell = null },
                onSave = { newCell ->
                    viewModel.updateCell(day, section, newCell)
                    editingCell = null
                },
                onDelete = {
                    viewModel.updateCell(day, section, ScheduleCell(day, section))
                    editingCell = null
                }
            )
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 节次信息
                    Column(
                        modifier = Modifier
                            .weight(0.5f)
                            .padding(end = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
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
                                Text(time, fontSize = 7.sp, color = QringPalette.textSecondary, maxLines = 1)
                            }
                        }
                    }
                    // 每天的格子
                    for (d in 0 until dayCount) {
                        val cell = state.cells[Pair(d, s)]
                        ScheduleCellBox(
                            cell = cell,
                            modifier = Modifier.weight(1f),
                            onClick = { onCellClick(d, s) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun ScheduleCellBox(
    cell: ScheduleCell?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val hasContent = cell != null && cell.courseName.isNotBlank()
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (hasContent) QringPalette.brand.copy(alpha = 0.12f) else QringPalette.paper)
            .border(0.5.dp, QringPalette.paperEdge, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        if (hasContent) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = cell!!.courseName,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = QringPalette.textPrimary,
                    maxLines = 2,
                    textAlign = TextAlign.Center
                )
                if (cell.location.isNotBlank()) {
                    Text(
                        text = cell.location,
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
                // 节次数
                Text("节次数: ${state.config.sectionCount}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(4, 6, 8, 10, 12).forEach { count ->
                        TextButton(
                            onClick = { onSectionCountChange(count) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (state.config.sectionCount == count) QringPalette.brand else QringPalette.surfaceSunken,
                                contentColor = if (state.config.sectionCount == count) Color.White else QringPalette.textPrimary
                            )
                        ) {
                            Text("$count", fontSize = 13.sp)
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

// ── 单元格编辑弹窗 ───────────────────────────────────────

@Composable
private fun CellEditDialog(
    cell: ScheduleCell,
    dayLabel: String,
    sectionIndex: Int,
    onDismiss: () -> Unit,
    onSave: (ScheduleCell) -> Unit,
    onDelete: () -> Unit
) {
    var courseName by remember { mutableStateOf(cell.courseName) }
    var location by remember { mutableStateOf(cell.location) }
    var teacher by remember { mutableStateOf(cell.teacher) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("星期$dayLabel · 第${sectionIndex}节") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(ScheduleCell(cell.day, cell.section, courseName.trim(), location.trim(), teacher.trim()))
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (cell.courseName.isNotBlank()) {
                    TextButton(onClick = onDelete) { Text("删除", color = Color.Red) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
