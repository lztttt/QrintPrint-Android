package com.qring.print.ui.label

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

data class LabelContent(
    val id: String,
    var text: String = ""
)

data class LabelConfig(
    val labelHeight: Int = 80,       // 标签纸高度（点）
    val gapHeight: Int = 20,         // 标签之间空白高度（点）
    val copies: Int = 1              // 份数
)

data class LabelState(
    val config: LabelConfig = LabelConfig(),
    val contents: List<LabelContent> = listOf(LabelContent(java.util.UUID.randomUUID().toString(), ""))
)

// ── ViewModel ────────────────────────────────────────────

class LabelViewModel : ViewModel() {
    private val _state = MutableStateFlow(LabelState())
    val state: StateFlow<LabelState> = _state.asStateFlow()

    fun addContent() {
        _state.update { current ->
            current.copy(contents = current.contents + LabelContent(java.util.UUID.randomUUID().toString(), ""))
        }
    }

    fun updateContent(id: String, text: String) {
        _state.update { current ->
            current.copy(contents = current.contents.map { if (it.id == id) it.copy(text = text) else it })
        }
    }

    fun deleteContent(id: String) {
        _state.update { current ->
            current.copy(contents = current.contents.filter { it.id != id })
        }
    }

    fun updateConfig(config: LabelConfig) {
        _state.update { it.copy(config = config) }
    }

    fun setLabelHeight(value: Int) {
        _state.update { it.copy(config = it.config.copy(labelHeight = value.coerceIn(10, 2000))) }
    }

    fun setGapHeight(value: Int) {
        _state.update { it.copy(config = it.config.copy(gapHeight = value.coerceIn(0, 500))) }
    }

    fun setCopies(value: Int) {
        _state.update { it.copy(config = it.config.copy(copies = value.coerceIn(1, 99))) }
    }
}

// ── 屏幕 ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelPrintScreen(navController: NavHostController) {
    val viewModel: LabelViewModel = viewModel()
    val state by viewModel.state.collectAsState()

    Scaffold(
        containerColor = QringPalette.pageBg,
        topBar = {
            TopAppBar(
                title = { Text("标签纸打印", fontWeight = FontWeight.Bold) },
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
                onClick = viewModel::addContent,
                containerColor = QringPalette.brand
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加标签")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 配置区域
            LabelConfigCard(
                state = state,
                onLabelHeightChange = viewModel::setLabelHeight,
                onGapHeightChange = viewModel::setGapHeight,
                onCopiesChange = viewModel::setCopies
            )

            // 标签内容列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 8.dp, bottom = 80.dp
                )
            ) {
                items(state.contents) { content ->
                    LabelContentCard(
                        content = content,
                        index = state.contents.indexOf(content) + 1,
                        onTextChange = { viewModel.updateContent(content.id, it) },
                        onDelete = { viewModel.deleteContent(content.id) }
                    )
                }
            }
        }
    }
}

// ── 配置卡片 ─────────────────────────────────────────────

@Composable
private fun LabelConfigCard(
    state: LabelState,
    onLabelHeightChange: (Int) -> Unit,
    onGapHeightChange: (Int) -> Unit,
    onCopiesChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("标签纸设置", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = QringPalette.textPrimary)
            Spacer(modifier = Modifier.height(12.dp))

            // 标签高度
            LabelNumberRow(
                label = "标签高度",
                value = state.config.labelHeight,
                suffix = "点",
                min = 10,
                max = 2000,
                onValueChange = onLabelHeightChange
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 间距高度
            LabelNumberRow(
                label = "间距空白",
                value = state.config.gapHeight,
                suffix = "点",
                min = 0,
                max = 500,
                onValueChange = onGapHeightChange
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 份数
            LabelNumberRow(
                label = "打印份数",
                value = state.config.copies,
                suffix = "份",
                min = 1,
                max = 99,
                onValueChange = onCopiesChange
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 总高度预览
            val totalHeight = (state.config.labelHeight + state.config.gapHeight) * state.contents.size * state.config.copies
            Text(
                text = "预计总高度: ${totalHeight}点 (${String.format("%.1f", totalHeight / 8.0)}mm)",
                fontSize = 12.sp,
                color = QringPalette.textSecondary
            )
        }
    }
}

@Composable
private fun LabelNumberRow(
    label: String,
    value: Int,
    suffix: String,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit
) {
    var textValue by remember(value) { mutableStateOf(value.toString()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = QringPalette.textPrimary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = textValue,
                onValueChange = { input ->
                    val filtered = input.filter { it.isDigit() }
                    textValue = filtered
                    filtered.toIntOrNull()?.let { num ->
                        onValueChange(num.coerceIn(min, max))
                    }
                },
                modifier = Modifier.width(80.dp),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, textAlign = TextAlign.Center),
                suffix = { Text(suffix, fontSize = 11.sp, color = QringPalette.textSecondary) }
            )
        }
    }
}

// ── 标签内容卡片 ─────────────────────────────────────────

@Composable
private fun LabelContentCard(
    content: LabelContent,
    index: Int,
    onTextChange: (String) -> Unit,
    onDelete: () -> Unit
) {
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
                Text("标签 $index", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.brand)
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = QringPalette.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = content.text,
                onValueChange = onTextChange,
                label = { Text("打印内容", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
            )
        }
    }
}
