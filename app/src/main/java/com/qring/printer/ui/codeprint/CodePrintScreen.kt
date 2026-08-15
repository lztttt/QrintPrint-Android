package com.qring.printer.ui.codeprint

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.printer.R
import com.qring.printer.model.CODE_TYPES
import com.qring.printer.model.CodeCategory
import com.qring.printer.model.ConnState
import com.qring.printer.ui.theme.Metrics
import com.qring.printer.ui.theme.ONLINE
import com.qring.printer.ui.theme.QringPalette
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodePrintScreen(
    navController: androidx.navigation.NavController,
    viewModel: CodePrintViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()
    var templateDialog by remember { mutableStateOf<CodeTemplate?>(null) }

    // 实时预览：内容/码制/尺寸/对齐变化后防抖渲染
    LaunchedEffect(uiState.content, uiState.codeTypeIndex, uiState.scalePercent, uiState.alignment) {
        delay(400)
        viewModel.updatePreview()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QringPalette.pageBg)
    ) {
        // 顶栏
        TopAppBar(
            title = { Text("条码打印") },
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

        // 顶部：实时预览卡片
        PreviewCard(
            preview = uiState.previewBitmap,
            content = uiState.content,
            codeTypeLabel = CODE_TYPES.getOrNull(uiState.codeTypeIndex)?.label ?: "QR Code"
        )

        // 中间：输入 + 设置（可滚动）
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.PAGE_PADDING.dp)
                .padding(top = 12.dp, bottom = 12.dp)
        ) {
            ConnectionBanner(printerStatus = printerStatus)

            Spacer(modifier = Modifier.height(12.dp))

            // 内容输入
            ContentInput(
                content = uiState.content,
                onContentChange = viewModel::updateContent,
                enabled = !uiState.printing
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 变量占位符快捷插入（图形化编辑）
            VariableInsertBar(
                onInsert = viewModel::insertPlaceholder,
                enabled = !uiState.printing
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 码制选择
            CodeTypeSelector(
                selectedIndex = uiState.codeTypeIndex,
                onSelect = viewModel::setCodeTypeIndex,
                enabled = !uiState.printing
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 纠错等级（仅二维码显示）
            if ((CODE_TYPES.getOrNull(uiState.codeTypeIndex)?.label ?: "QR Code") == "QR Code") {
                EccSelector(
                    ecc = uiState.ecc,
                    onEccChange = viewModel::setEcc,
                    enabled = !uiState.printing
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 尺寸与对齐
            CodeSizeAndAlignment(
                scalePercent = uiState.scalePercent,
                alignment = uiState.alignment,
                onScaleChange = viewModel::setScalePercent,
                onAlignmentChange = viewModel::setAlignment,
                enabled = !uiState.printing
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 上下方标注
            CaptionSettings(
                showTopText = uiState.showTopText,
                topText = uiState.topText,
                showBottomText = uiState.showBottomText,
                bottomText = uiState.bottomText,
                captionFontSize = uiState.captionFontSize,
                onShowTopChange = viewModel::setShowTopText,
                onTopTextChange = viewModel::setTopText,
                onShowBottomChange = viewModel::setShowBottomText,
                onBottomTextChange = viewModel::setBottomText,
                onFontSizeChange = viewModel::setCaptionFontSize,
                enabled = !uiState.printing
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 批量打印
            BatchSettings(
                batchCount = uiState.batchCount,
                onBatchCountChange = viewModel::setBatchCount,
                enabled = !uiState.printing
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 快速模板
            QuickTemplateSelector(
                onSelect = { template ->
                    templateDialog = template
                },
                enabled = !uiState.printing
            )
        }

        // 模板输入对话框
        templateDialog?.let { template ->
            TemplateInputDialog(
                template = template,
                viewModel = viewModel,
                onDismiss = { templateDialog = null }
            )
        }

        // 底部：操作按键
        BottomActionBar(
            printing = uiState.printing,
            canPrint = uiState.content.isNotEmpty(),
            progressText = uiState.progressText,
            resultMessage = uiState.resultMessage,
            resultOk = uiState.resultOk,
            onPrint = viewModel::print
        )
    }

    // 打印前状态检查弹窗
    com.qring.printer.ui.common.PrintWarningDialog(onGoBack = { navController.popBackStack() })
}

@Composable
private fun PreviewCard(
    preview: android.graphics.Bitmap?,
    content: String,
    codeTypeLabel: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.PAGE_PADDING.dp)
            .padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = if (preview != null)
                    "宽 ${preview.width} 点(${String.format("%.1f", preview.width / 8.0)}mm) × 高 ${preview.height} 点(${String.format("%.1f", preview.height / 8.0)}mm) · $codeTypeLabel"
                else if (content.isEmpty())
                    "输入内容后自动预览"
                else
                    "正在生成预览…",
                fontSize = 11.sp,
                color = QringPalette.textSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
            ) {
                when {
                    preview != null -> {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = "条码预览",
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                    content.isEmpty() -> {
                        Text(
                            text = "输入内容后自动预览",
                            color = QringPalette.textSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> {
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
}

@Composable
private fun ContentInput(
    content: String,
    onContentChange: (String) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        BasicTextField(
            value = content,
            onValueChange = onContentChange,
            enabled = enabled,
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            textStyle = TextStyle(
                fontSize = 16.sp,
                color = QringPalette.textPrimary
            ),
            cursorBrush = SolidColor(QringPalette.brand),
            decorationBox = { innerTextField ->
                Box {
                    if (content.isEmpty()) {
                        Text(
                            text = "输入条码内容（URL、文本、数字…）",
                            color = QringPalette.textSecondary,
                            fontSize = 16.sp
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun CodeTypeSelector(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "码制",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 二维码类别
            Text(
                text = "二维码",
                fontSize = 12.sp,
                color = QringPalette.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(CODE_TYPES.filter { it.category == CodeCategory.TWO_D }) { _, codeType ->
                    val realIndex = CODE_TYPES.indexOf(codeType)
                    CodeTypeChip(
                        label = codeType.label,
                        active = realIndex == selectedIndex,
                        enabled = enabled,
                        onClick = { onSelect(realIndex) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 一维码类别
            Text(
                text = "一维码",
                fontSize = 12.sp,
                color = QringPalette.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(CODE_TYPES.filter { it.category == CodeCategory.ONE_D }) { _, codeType ->
                    val realIndex = CODE_TYPES.indexOf(codeType)
                    CodeTypeChip(
                        label = codeType.label,
                        active = realIndex == selectedIndex,
                        enabled = enabled,
                        onClick = { onSelect(realIndex) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeSizeAndAlignment(
    scalePercent: Int,
    alignment: CodeAlignment,
    onScaleChange: (Int) -> Unit,
    onAlignmentChange: (CodeAlignment) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 尺寸滑块
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "打印尺寸",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = QringPalette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$scalePercent%",
                    fontSize = 13.sp,
                    color = QringPalette.brand
                )
            }
            Slider(
                value = scalePercent.toFloat(),
                onValueChange = { onScaleChange(Math.round(it)) },
                valueRange = 10f..100f,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = QringPalette.brand,
                    activeTrackColor = QringPalette.brand
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 对齐方式
            Text(
                text = "对齐方式",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CodeAlignment.entries.forEach { option ->
                    AlignChip(
                        label = option.label,
                        active = alignment == option,
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                        onTap = { onAlignmentChange(option) }
                    )
                }
            }
        }
    }
}

// ── 变量占位符插入 ────────────────────────────────────────

@Composable
private fun VariableInsertBar(
    onInsert: (String) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "插入变量（追加到内容末尾，打印时自动替换）",
                fontSize = 11.sp,
                color = QringPalette.textSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VariableChip("{content}", "条码内容", Modifier.weight(1f), enabled) { onInsert("{content}") }
                VariableChip("{time_now}", "当前时间", Modifier.weight(1f), enabled) { onInsert("{time_now}") }
                VariableChip("{(1:100)}", "序号 1-100", Modifier.weight(1f), enabled) { onInsert("{(1:100)}") }
            }
        }
    }
}

@Composable
private fun VariableChip(
    label: String,
    hint: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(QringPalette.surface)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = QringPalette.brand)
            Text(text = hint, fontSize = 9.sp, color = QringPalette.textSecondary)
        }
    }
}

// ── 二维码纠错等级 ────────────────────────────────────────

@Composable
private fun EccSelector(
    ecc: com.qring.printer.ui.codeprint.QrEcc,
    onEccChange: (com.qring.printer.ui.codeprint.QrEcc) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "二维码纠错等级",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "等级越高容错越强，但码点越密（适合贴面/磨损场景）",
                fontSize = 11.sp,
                color = QringPalette.textSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                com.qring.printer.ui.codeprint.QrEcc.entries.forEach { option ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (ecc == option) QringPalette.brand else QringPalette.surfaceSunken)
                            .clickable(enabled = enabled) { onEccChange(option) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = option.label,
                            fontSize = 11.sp,
                            fontWeight = if (ecc == option) FontWeight.Bold else FontWeight.Normal,
                            color = if (ecc == option) Color.White else QringPalette.textPrimary
                        )
                    }
                }
            }
        }
    }
}

// ── 上下方标注 ────────────────────────────────────────────

@Composable
private fun CaptionSettings(
    showTopText: Boolean,
    topText: String,
    showBottomText: Boolean,
    bottomText: String,
    captionFontSize: Float,
    onShowTopChange: (Boolean) -> Unit,
    onTopTextChange: (String) -> Unit,
    onShowBottomChange: (Boolean) -> Unit,
    onBottomTextChange: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "文字标注",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "支持 {content} {time_now} {(1:100)} 变量，如「SN：{content}」",
                fontSize = 11.sp,
                color = QringPalette.textSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 上方标注
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("上方文字", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                Switch(checked = showTopText, onCheckedChange = onShowTopChange, enabled = enabled)
            }
            if (showTopText) {
                OutlinedTextField(
                    value = topText,
                    onValueChange = onTopTextChange,
                    enabled = enabled,
                    label = { Text("如：SN：{content}") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 下方标注
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("下方文字", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                Switch(checked = showBottomText, onCheckedChange = onShowBottomChange, enabled = enabled)
            }
            if (showBottomText) {
                OutlinedTextField(
                    value = bottomText,
                    onValueChange = onBottomTextChange,
                    enabled = enabled,
                    label = { Text("如：ISBN：{content}") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 标注字号
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("标注字号", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                Text("${captionFontSize.toInt()}pt", fontSize = 13.sp, color = QringPalette.brand)
            }
            Slider(
                value = captionFontSize,
                onValueChange = onFontSizeChange,
                valueRange = 10f..24f,
                enabled = enabled,
                colors = SliderDefaults.colors(thumbColor = QringPalette.brand, activeTrackColor = QringPalette.brand)
            )
        }
    }
}

// ── 批量打印 ──────────────────────────────────────────────

@Composable
private fun BatchSettings(
    batchCount: Int,
    onBatchCountChange: (Int) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "批量打印",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = QringPalette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text("$batchCount 张", fontSize = 13.sp, color = QringPalette.brand)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "内容含 {(start:end)} 时自动按区间数量批量，序号逐张递增；否则按下方数量打印相同内容",
                fontSize = 11.sp,
                color = QringPalette.textSecondary
            )
            Slider(
                value = batchCount.toFloat(),
                onValueChange = { onBatchCountChange(Math.round(it)) },
                valueRange = 1f..200f,
                steps = 198,
                enabled = enabled,
                colors = SliderDefaults.colors(thumbColor = QringPalette.brand, activeTrackColor = QringPalette.brand)
            )
        }
    }
}

@Composable
private fun AlignChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onTap: () -> Unit
) {
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) QringPalette.brand else QringPalette.surfaceSunken)
            .clickable(enabled = enabled) { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) Color.White else QringPalette.textPrimary
        )
    }
}

@Composable
private fun CodeTypeChip(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) QringPalette.brand else QringPalette.surfaceSunken)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (active) Color.White else QringPalette.textPrimary
        )
    }
}

@Composable
private fun BottomActionBar(
    printing: Boolean,
    canPrint: Boolean,
    progressText: String,
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
        if (progressText.isNotEmpty()) {
            Text(
                text = progressText,
                fontSize = 12.sp,
                color = QringPalette.textSecondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        if (resultMessage.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (resultOk)
                        ONLINE.copy(alpha = 0.1f)
                    else
                        Color(0xFFFF4D4F).copy(alpha = 0.1f)
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
            Spacer(modifier = Modifier.height(10.dp))
        }

        Button(
            onClick = onPrint,
            enabled = !printing && canPrint,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (printing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("打印中…", fontSize = 15.sp)
            } else {
                Icon(
                    Icons.Default.Print,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.print), fontSize = 16.sp)
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

// ── 快速模板 ──────────────────────────────────────────────

enum class CodeTemplate(val label: String, val icon: String) {
    URL("网址", "🔗"),
    PHONE("电话", "📞"),
    WIFI("WiFi", "📶"),
    EMAIL("邮箱", "✉"),
    SMS("短信", "💬"),
    CARD("名片", "👤")
}

@Composable
private fun QuickTemplateSelector(
    onSelect: (CodeTemplate) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "快速模板",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "点击填充对应格式内容，修改后打印",
                fontSize = 11.sp,
                color = QringPalette.textSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(CodeTemplate.entries.toList()) { _, template ->
                    TemplateChip(
                        template = template,
                        enabled = enabled,
                        onClick = { onSelect(template) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateChip(
    template: CodeTemplate,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(QringPalette.surfaceSunken)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = template.icon, fontSize = 14.sp)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = template.label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = QringPalette.textPrimary
            )
        }
    }
}

// ── 模板输入对话框 ─────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateInputDialog(
    template: CodeTemplate,
    viewModel: CodePrintViewModel,
    onDismiss: () -> Unit
) {
    when (template) {
        CodeTemplate.WIFI -> WifiTemplateDialog(viewModel = viewModel, onDismiss = onDismiss)
        CodeTemplate.URL -> SimpleInputDialog(
            title = "网址二维码",
            label = "输入网址",
            placeholder = "https://www.example.com",
            keyboardType = KeyboardType.Uri,
            onConfirm = { viewModel.applyUrlTemplate(it); onDismiss() },
            onDismiss = onDismiss
        )
        CodeTemplate.PHONE -> SimpleInputDialog(
            title = "电话条码",
            label = "输入电话号码",
            placeholder = "+8613800138000",
            keyboardType = KeyboardType.Phone,
            onConfirm = { viewModel.applyPhoneTemplate(it); onDismiss() },
            onDismiss = onDismiss
        )
        CodeTemplate.EMAIL -> SimpleInputDialog(
            title = "邮箱二维码",
            label = "输入邮箱地址",
            placeholder = "hello@example.com",
            keyboardType = KeyboardType.Email,
            onConfirm = { viewModel.applyEmailTemplate(it); onDismiss() },
            onDismiss = onDismiss
        )
        CodeTemplate.SMS -> SimpleInputDialog(
            title = "短信二维码",
            label = "输入电话号码",
            placeholder = "+8613800138000",
            keyboardType = KeyboardType.Phone,
            onConfirm = { viewModel.applySmsTemplate(it); onDismiss() },
            onDismiss = onDismiss
        )
        CodeTemplate.CARD -> CardTemplateDialog(viewModel = viewModel, onDismiss = onDismiss)
    }
}

@Composable
private fun SimpleInputDialog(
    title: String,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text("生成", color = QringPalette.brand, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiTemplateDialog(
    viewModel: CodePrintViewModel,
    onDismiss: () -> Unit
) {
    // 尝试预填当前 WiFi
    var ssid by remember {
        mutableStateOf("")
    }
    var password by remember { mutableStateOf("") }
    var encryption by remember { mutableStateOf("WPA/WPA2") }
    var expanded by remember { mutableStateOf(false) }

    // 首次打开时尝试获取当前 WiFi
    LaunchedEffect(Unit) {
        val current = viewModel.getCurrentWifiSsid()
        if (current.isNotEmpty()) {
            ssid = current
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WiFi 二维码", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column {
                // SSID
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("WiFi 名称 (SSID)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 密码
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 加密方式
                Box {
                    OutlinedTextField(
                        value = encryption,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("加密方式") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = true },
                        trailingIcon = {
                            Text("▼", fontSize = 10.sp, color = QringPalette.textSecondary)
                        }
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        listOf("WPA/WPA2", "WEP", "无密码").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    encryption = option
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.applyWifiTemplate(ssid, password, encryption)
                    onDismiss()
                },
                enabled = ssid.isNotBlank()
            ) { Text("生成", color = QringPalette.brand, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardTemplateDialog(
    viewModel: CodePrintViewModel,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("名片二维码", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("姓名 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("电话") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("邮箱") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = org,
                    onValueChange = { org = it },
                    label = { Text("公司/组织") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("职位") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.applyCardTemplate(name, phone, email, org, title)
                    onDismiss()
                },
                enabled = name.isNotBlank()
            ) { Text("生成", color = QringPalette.brand, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
