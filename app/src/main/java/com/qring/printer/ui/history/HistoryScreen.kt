package com.qring.printer.ui.history

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.printer.data.HistoryPayloadHolder
import com.qring.printer.model.HistoryRecord
import com.qring.printer.model.HIST_TYPE_TEXT
import com.qring.printer.model.HIST_TYPE_MATH
import com.qring.printer.model.HIST_TYPE_IMAGE
import com.qring.printer.model.HIST_TYPE_CODE
import com.qring.printer.model.HIST_TYPE_CUSTOM
import com.qring.printer.model.HIST_TYPE_SCHEDULE
import com.qring.printer.model.HIST_TYPE_TODO
import com.qring.printer.model.HIST_TYPE_WRONGBOOK
import com.qring.printer.model.HIST_TYPE_WORDBOOK
import com.qring.printer.model.HIST_TYPE_PDF
import com.qring.printer.model.HIST_TYPE_BATCH
import com.qring.printer.ui.theme.Metrics
import com.qring.printer.ui.theme.QringPalette
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    onReopen: (HistoryRecord) -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val records by viewModel.records.collectAsState()
    val isEmpty by viewModel.isEmpty.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val selectedFilterTag by viewModel.selectedFilterTag.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadHistory() }

    val filteredRecords = remember(records, selectedFilterTag) {
        if (selectedFilterTag == null) records
        else records.filter { extractTags(it.payload).contains(selectedFilterTag) }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(QringPalette.pageBg).statusBarsPadding()
    ) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().background(QringPalette.surface)
                .padding(horizontal = Metrics.PAGE_PADDING.dp).height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("历史记录", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
            if (records.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearAll() }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null,
                        tint = QringPalette.textSecondary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清空", color = QringPalette.textSecondary, fontSize = 14.sp)
                }
            }
        }

        // 标签筛选栏
        if (allTags.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = Metrics.PAGE_PADDING.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedFilterTag == null,
                    onClick = { viewModel.setFilterTag(null) },
                    label = { Text("全部", fontSize = 11.sp) }
                )
                allTags.forEach { tag ->
                    FilterChip(
                        selected = selectedFilterTag == tag,
                        onClick = { viewModel.setFilterTag(if (selectedFilterTag == tag) null else tag) },
                        label = { Text("#$tag", fontSize = 11.sp) }
                    )
                }
            }
        }

        if (isEmpty) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = QringPalette.textSecondary.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("还没有打印记录", color = QringPalette.textSecondary, fontSize = 14.sp)
                    Text("去打印点东西吧", color = QringPalette.textSecondary.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
        } else if (filteredRecords.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("该标签下暂无记录", color = QringPalette.textSecondary, fontSize = 14.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(Metrics.PAGE_PADDING.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredRecords, key = { it.id }) { record ->
                    HistoryCard(record = record,
                        onClick = { HistoryPayloadHolder.setRecord(record); onReopen(record) },
                        onDelete = { viewModel.deleteRecord(record.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryCard(record: HistoryRecord, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
    ) {
        Column {
            // 缩略图
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                val thumbFile = File(record.thumbnailPath)
                if (thumbFile.exists()) {
                    val bmp = remember(thumbFile.absolutePath) { BitmapFactory.decodeFile(thumbFile.absolutePath) }
                    if (bmp != null) {
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                } else {
                    Text("无图", color = QringPalette.textSecondary, fontSize = 12.sp)
                }
            }

            // 信息
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(typeLabel(record.typeName), fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, color = QringPalette.textPrimary)
                        Text(formatDate(record.createdAt), fontSize = 11.sp, color = QringPalette.textSecondary)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "删除",
                            tint = QringPalette.textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
                // 错题本标签
                val tags = extractTags(record.payload)
                if (tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.take(3).forEach { tag ->
                            Text("#$tag", fontSize = 10.sp, color = QringPalette.brand,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                    .background(QringPalette.brand.copy(alpha = 0.1f))
                                    .padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                        if (tags.size > 3) {
                            Text("+${tags.size - 3}", fontSize = 10.sp, color = QringPalette.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

private fun extractTags(payload: String): List<String> {
    return try {
        val json = JSONObject(payload)
        val tagsStr = json.optString("tags", "")
        if (tagsStr.isNotEmpty()) tagsStr.split(",").filter { it.isNotEmpty() } else emptyList()
    } catch (e: Exception) { emptyList() }
}

private fun typeLabel(typeName: String): String = when (typeName) {
    HIST_TYPE_TEXT -> "文字打印"
    HIST_TYPE_MATH -> "口算题"
    HIST_TYPE_IMAGE -> "图片打印"
    HIST_TYPE_CODE -> "条码打印"
    HIST_TYPE_CUSTOM -> "自定义打印"
    HIST_TYPE_SCHEDULE -> "课程表"
    HIST_TYPE_TODO -> "待办事项"
    HIST_TYPE_WRONGBOOK -> "错题本"
    HIST_TYPE_WORDBOOK -> "单词本"
    HIST_TYPE_PDF -> "PDF 打印"
    HIST_TYPE_BATCH -> "批量打印"
    else -> "打印"
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
