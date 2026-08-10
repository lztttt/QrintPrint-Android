package com.qring.print.ui.todo

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.qring.print.ui.theme.QringPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// ── 数据模型 ──────────────────────────────────────────────

data class TodoItem(
    val id: String,
    var text: String,
    var done: Boolean = false,
    var priority: Int = 0  // 0=普通, 1=重要, 2=紧急
)

data class TodoState(
    val items: List<TodoItem> = emptyList(),
    val title: String = "待办事项"
)

// ── ViewModel ────────────────────────────────────────────

class TodoViewModel : ViewModel() {
    private val _state = MutableStateFlow(TodoState())
    val state: StateFlow<TodoState> = _state.asStateFlow()

    init {
        // 初始化几条示例
        _state.value = TodoState(items = listOf(
            TodoItem(java.util.UUID.randomUUID().toString(), "示例待办事项")
        ))
    }

    fun addItem(text: String) {
        if (text.isBlank()) return
        _state.update { current ->
            current.copy(items = current.items + TodoItem(java.util.UUID.randomUUID().toString(), text))
        }
    }

    fun updateItem(id: String, text: String) {
        _state.update { current ->
            current.copy(items = current.items.map { if (it.id == id) it.copy(text = text) else it })
        }
    }

    fun toggleDone(id: String) {
        _state.update { current ->
            current.copy(items = current.items.map { if (it.id == id) it.copy(done = !it.done) else it })
        }
    }

    fun deleteItem(id: String) {
        _state.update { current ->
            current.copy(items = current.items.filter { it.id != id })
        }
    }

    fun setPriority(id: String, priority: Int) {
        _state.update { current ->
            current.copy(items = current.items.map { if (it.id == id) it.copy(priority = priority) else it })
        }
    }

    fun updateTitle(title: String) {
        _state.update { it.copy(title = title) }
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
    }
}

// ── 屏幕 ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoPrintScreen(navController: NavHostController) {
    val viewModel: TodoViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            // 标题
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::updateTitle,
                label = { Text("标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 80.dp)
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
        2 -> Color(0xFFE53935)  // 紧急-红
        1 -> Color(0xFFFB8C00)  // 重要-橙
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
                    .background(if (item.done) QringPalette.brand else Color.Transparent)
                    .border(1.5.dp, if (item.done) QringPalette.brand else QringPalette.textSecondary, CircleShape)
                    .clickable(onClick = onToggleDone),
                contentAlignment = Alignment.Center
            ) {
                if (item.done) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
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
