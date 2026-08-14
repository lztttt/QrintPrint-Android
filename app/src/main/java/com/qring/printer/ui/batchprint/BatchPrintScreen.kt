package com.qring.printer.ui.batchprint

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.printer.model.ConnState
import com.qring.printer.ui.common.DitherSelector
import com.qring.printer.ui.common.PrintWarningDialog
import com.qring.printer.ui.common.SliderRow
import com.qring.printer.ui.common.ThicknessSlider
import com.qring.printer.ui.common.FontList
import com.qring.printer.ui.theme.Metrics
import com.qring.printer.ui.theme.ONLINE
import com.qring.printer.ui.theme.QringPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchPrintScreen(
    navController: androidx.navigation.NavController,
    viewModel: BatchPrintViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()

    // 文字文件多选（txt / md）
    val textPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addTextFiles(uris)
    }

    // 图片多选
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addImageUris(uris)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QringPalette.pageBg)
    ) {
        TopAppBar(
            title = { Text("批量打印") },
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

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.PAGE_PADDING.dp)
                .padding(top = 12.dp, bottom = 12.dp)
        ) {
            ConnectionBanner(printerStatus)

            Spacer(modifier = Modifier.height(12.dp))

            // 添加按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AddButton(
                    label = "添加文字",
                    icon = { Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.printing,
                    onClick = { textPickerLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }
                )
                AddButton(
                    label = "添加图片",
                    icon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.printing,
                    onClick = {
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 文件清单
            if (uiState.items.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "还没有项目。\n添加文字文件（txt / Markdown）或图片，可多选，支持批量连续打印。",
                        modifier = Modifier.padding(16.dp),
                        color = QringPalette.textSecondary,
                        fontSize = 13.sp
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "文件清单（${uiState.items.size}）",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = QringPalette.textPrimary
                    )
                    TextButton(onClick = {
                        val allSelected = uiState.items.all { it.selected }
                        viewModel.setAllSelected(!allSelected)
                    }) {
                        Text(
                            text = if (uiState.items.all { it.selected }) "取消全选" else "全选",
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))

                uiState.items.forEach { item ->
                    BatchItemRow(
                        item = item,
                        enabled = !uiState.printing,
                        onToggle = { viewModel.toggleSelected(item.id) },
                        onRemove = { viewModel.removeItem(item.id) }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (uiState.items.isNotEmpty()) {
                // 文字排版设置
                TextSettingsCard(
                    fontSize = uiState.textFontSize,
                    lineSpacing = uiState.textLineSpacing,
                    fontLabel = FontList.fontLabel(
                        uiState.fontFamilies.getOrElse(uiState.fontFamilyIndex) { "sans-serif" }
                    ),
                    onFontSizeChange = viewModel::setTextFontSize,
                    onLineSpacingChange = viewModel::setTextLineSpacing,
                    enabled = !uiState.printing
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 图片预设参数
                ImagePresetCard(
                    ditherMode = uiState.imageDitherMode,
                    threshold = uiState.imageThreshold,
                    thickness = uiState.thickness,
                    onDitherChange = viewModel::setImageDitherMode,
                    onThresholdChange = viewModel::setImageThreshold,
                    onThicknessChange = viewModel::setThickness,
                    enabled = !uiState.printing
                )
            }

            Spacer(modifier = Modifier.height(80.dp))
        }

        // 底部
        BottomActionBar(
            printing = uiState.printing,
            canPrint = uiState.items.any { it.selected },
            progressIndex = uiState.progressIndex,
            progressTotal = uiState.progressTotal,
            progressName = uiState.progressName,
            resultMessage = uiState.resultMessage,
            resultOk = uiState.resultOk,
            onPrint = viewModel::print
        )
    }

    PrintWarningDialog(onGoBack = { navController.popBackStack() })
}

@Composable
private fun AddButton(
    label: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = QringPalette.surface,
            contentColor = QringPalette.brand
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        icon()
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun BatchItemRow(
    item: BatchItem,
    enabled: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.selected) QringPalette.brand.copy(alpha = 0.06f) else QringPalette.surface
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = item.selected,
                onCheckedChange = { onToggle() },
                enabled = enabled
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = QringPalette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (item.kind == BatchItemKind.IMAGE) "图片" else "文字",
                    fontSize = 11.sp,
                    color = if (item.kind == BatchItemKind.IMAGE) QringPalette.brand else QringPalette.textSecondary
                )
            }
            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "移除",
                    tint = QringPalette.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun TextSettingsCard(
    fontSize: Float,
    lineSpacing: Float,
    fontLabel: String,
    onFontSizeChange: (Float) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "文字排版（txt / Markdown 共用）",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("字体：$fontLabel", fontSize = 12.sp, color = QringPalette.textSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("字号", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                Text("${fontSize.toInt()}pt", fontSize = 13.sp, color = QringPalette.textSecondary)
            }
            Slider(
                value = fontSize,
                onValueChange = onFontSizeChange,
                valueRange = 8f..24f,
                enabled = enabled,
                colors = SliderDefaults.colors(thumbColor = QringPalette.brand, activeTrackColor = QringPalette.brand)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("行距", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                Text("${lineSpacing.toInt()}pt", fontSize = 13.sp, color = QringPalette.textSecondary)
            }
            Slider(
                value = lineSpacing,
                onValueChange = onLineSpacingChange,
                valueRange = 0f..16f,
                enabled = enabled,
                colors = SliderDefaults.colors(thumbColor = QringPalette.brand, activeTrackColor = QringPalette.brand)
            )
        }
    }
}

@Composable
private fun ImagePresetCard(
    ditherMode: com.qring.printer.protocol.DitherMode,
    threshold: Int,
    thickness: Int?,
    onDitherChange: (com.qring.printer.protocol.DitherMode) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onThicknessChange: (Int?) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "图片预设参数（应用到所有图片）",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            DitherSelector(
                selectedMode = ditherMode,
                onModeChange = onDitherChange,
                enabled = enabled
            )
            Spacer(modifier = Modifier.height(8.dp))
            SliderRow(
                label = "阈值",
                value = threshold.toFloat(),
                min = 0f,
                max = 255f,
                suffix = "",
                valueText = threshold.toString(),
                onValueChange = { onThresholdChange(Math.round(it)) },
                enabled = enabled
            )
            Spacer(modifier = Modifier.height(8.dp))
            ThicknessSlider(
                thickness = thickness,
                onThicknessChange = onThicknessChange,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun BottomActionBar(
    printing: Boolean,
    canPrint: Boolean,
    progressIndex: Int,
    progressTotal: Int,
    progressName: String,
    resultMessage: String,
    resultOk: Boolean,
    onPrint: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(QringPalette.surface)
            .padding(horizontal = Metrics.PAGE_PADDING.dp)
            .padding(top = 10.dp, bottom = 16.dp)
    ) {
        if (printing && progressTotal > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { progressIndex.toFloat() / progressTotal },
                    modifier = Modifier.weight(1f),
                    color = QringPalette.brand
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$progressIndex/$progressTotal",
                    fontSize = 12.sp,
                    color = QringPalette.textSecondary
                )
            }
            if (progressName.isNotEmpty()) {
                Text(
                    text = "正在打印：$progressName",
                    fontSize = 12.sp,
                    color = QringPalette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        if (resultMessage.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (resultOk) ONLINE.copy(alpha = 0.1f) else Color(0xFFFF4D4F).copy(alpha = 0.1f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = resultMessage,
                    modifier = Modifier.padding(12.dp),
                    color = if (resultOk) ONLINE else Color(0xFFFF4D4F),
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Button(
            onClick = onPrint,
            enabled = !printing && canPrint,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (printing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("打印中…", fontSize = 15.sp)
            } else {
                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("批量打印", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ConnectionBanner(printerStatus: com.qring.printer.model.PrinterStatus) {
    val connected = printerStatus.connState == ConnState.CONNECTED
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (connected) ONLINE else QringPalette.offline)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (connected) "已连接 ${printerStatus.deviceName}"
                else "未连接打印机 —— 请回首页点状态卡选择设备",
                fontSize = 12.sp,
                color = QringPalette.textSecondary,
                maxLines = 1
            )
        }
    }
}
