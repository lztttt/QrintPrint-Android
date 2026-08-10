package com.qring.print.ui.textprint

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.print.R
import com.qring.print.model.ConnState
import com.qring.print.ui.common.FontList
import com.qring.print.ui.theme.Metrics
import com.qring.print.ui.theme.ONLINE
import com.qring.print.ui.theme.QringPalette
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextPrintScreen(
    navController: androidx.navigation.NavController,
    viewModel: TextPrintViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadFonts()
    }

    // 自动预览：内容 / 排版参数变化后防抖渲染，不弹窗
    LaunchedEffect(
        uiState.text, uiState.fontSize, uiState.bold, uiState.italic, uiState.underline,
        uiState.letterSpacing, uiState.lineSpacing, uiState.pageMargin, uiState.fontFamilyIndex
    ) {
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
            title = { Text("文字打印") },
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

        // 顶部：预览 + 提示卡片
        PreviewCard(
            preview = uiState.previewBitmap,
            text = uiState.text,
            margin = uiState.pageMargin
        )

        // 中间：输入 + 打印设置（可滚动）
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            modifier = Modifier.weight(1f)
        ) {
            InputAndSettings(
                uiState = uiState,
                printerStatus = printerStatus,
                onTextChange = viewModel::updateText,
                onFontSizeChange = viewModel::updateFontSize,
                onBoldToggle = viewModel::toggleBold,
                onItalicToggle = viewModel::toggleItalic,
                onUnderlineToggle = viewModel::toggleUnderline,
                onLetterSpacingChange = viewModel::updateLetterSpacing,
                onLineSpacingChange = viewModel::updateLineSpacing,
                onPageMarginChange = viewModel::updatePageMargin,
                onFontFamilyChange = viewModel::setFontFamilyIndex,
                onThicknessChange = viewModel::setThickness
            )
        }

        // 底部：操作按键（固定）
        BottomActionBar(
            printing = uiState.printing,
            textEmpty = uiState.text.isEmpty(),
            resultMessage = uiState.resultMessage,
            resultOk = uiState.resultOk,
            onPrint = viewModel::print
        )
    }
}

@Composable
private fun InputAndSettings(
    uiState: TextPrintUiState,
    printerStatus: com.qring.print.model.PrinterStatus,
    onTextChange: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onBoldToggle: () -> Unit,
    onItalicToggle: () -> Unit,
    onUnderlineToggle: () -> Unit,
    onLetterSpacingChange: (Float) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onPageMarginChange: (Float) -> Unit,
    onFontFamilyChange: (Int) -> Unit,
    onThicknessChange: (Int?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Metrics.PAGE_PADDING.dp)
            .padding(top = 12.dp, bottom = 12.dp)
    ) {
            // 连接状态
            ConnectionBanner(printerStatus)

            Spacer(modifier = Modifier.height(12.dp))

            // 文本输入
            TextInputArea(
                text = uiState.text,
                onTextChange = onTextChange,
                enabled = !uiState.printing
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 排版设置
            Text(
                text = "排版设置",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = QringPalette.textPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // 字体选择
                    FontSelectorRow(
                        families = uiState.fontFamilies,
                        selectedIndex = uiState.fontFamilyIndex,
                        onSelect = onFontFamilyChange,
                        enabled = !uiState.printing
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "共 ${uiState.fontFamilies.size} 种 · 画布解析不了的会回落默认字体",
                        fontSize = 10.sp,
                        color = QringPalette.textSecondary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 样式切换 B / I / U
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ToggleChip(
                            label = "B",
                            active = uiState.bold,
                            bold = true,
                            italic = false,
                            underline = false,
                            onTap = onBoldToggle,
                            modifier = Modifier.weight(1f)
                        )
                        ToggleChip(
                            label = "I",
                            active = uiState.italic,
                            bold = false,
                            italic = true,
                            underline = false,
                            onTap = onItalicToggle,
                            modifier = Modifier.weight(1f)
                        )
                        ToggleChip(
                            label = "U",
                            active = uiState.underline,
                            bold = false,
                            italic = false,
                            underline = true,
                            onTap = onUnderlineToggle,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    SliderRow(
                        label = stringResource(R.string.font_size),
                        value = uiState.fontSize,
                        min = 12f,
                        max = 72f,
                        suffix = "pt",
                        onValueChange = onFontSizeChange,
                        enabled = !uiState.printing
                    )
                    SliderRow(
                        label = stringResource(R.string.letter_spacing),
                        value = uiState.letterSpacing,
                        min = -2f,
                        max = 10f,
                        suffix = "pt",
                        onValueChange = onLetterSpacingChange,
                        enabled = !uiState.printing
                    )
                    SliderRow(
                        label = stringResource(R.string.line_spacing),
                        value = uiState.lineSpacing,
                        min = 0f,
                        max = 20f,
                        suffix = "pt",
                        onValueChange = onLineSpacingChange,
                        enabled = !uiState.printing
                    )
                    SliderRow(
                        label = stringResource(R.string.page_margin),
                        value = uiState.pageMargin,
                        min = 0f,
                        max = 40f,
                        suffix = "pt",
                        onValueChange = onPageMarginChange,
                        enabled = !uiState.printing
                    )
                    ThicknessSliderRow(
                        thickness = uiState.thickness.takeIf { it > 0 },
                        onThicknessChange = onThicknessChange,
                        enabled = !uiState.printing
                    )
                }
            }
        }
    }

@Composable
private fun BottomActionBar(
    printing: Boolean,
    textEmpty: Boolean,
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
        // 结果提示
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

        // 打印按钮
        Button(
            onClick = onPrint,
            enabled = !printing && !textEmpty,
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
private fun PreviewCard(
    preview: android.graphics.Bitmap?,
    text: String,
    margin: Float
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
                    "宽 384 点(${String.format("%.1f", 384 / 8.0)}mm) × 高 ${preview.height} 点(${String.format("%.1f", preview.height / 8.0)}mm) · 边距 ${margin.toInt()} 点"
                else if (text.isEmpty())
                    "输入内容后自动预览"
                else
                    "正在渲染预览…",
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
                        contentDescription = "打印预览",
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        contentScale = ContentScale.FillWidth
                    )
                }
                text.isEmpty() -> {
                    Text(
                        text = "空",
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
private fun ConnectionBanner(printerStatus: com.qring.print.model.PrinterStatus) {
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

@Composable
private fun TextInputArea(
    text: String,
    onTextChange: (String) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken),
        shape = RoundedCornerShape(12.dp)
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
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
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.text_input_hint),
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
private fun FontSelectorRow(
    families: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "字体",
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            color = QringPalette.textPrimary
        )
        Text(
            text = FontList.fontLabel(families.getOrElse(selectedIndex) { "sans-serif" }),
            fontSize = 13.sp,
            color = QringPalette.brand,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(QringPalette.surface)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            families.forEachIndexed { index, family ->
                DropdownMenuItem(
                    text = { Text(FontList.fontLabel(family), fontSize = 13.sp, maxLines = 1) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ThicknessSliderRow(
    thickness: Int?,
    onThicknessChange: (Int?) -> Unit,
    enabled: Boolean
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "打印浓度",
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = QringPalette.textPrimary
            )
            Text(
                text = thickness?.toString() ?: "默认",
                fontSize = 13.sp,
                color = QringPalette.textSecondary
            )
        }
        Slider(
            value = (thickness ?: 0).toFloat(),
            onValueChange = { v ->
                val rounded = Math.round(v)
                onThicknessChange(if (rounded == 0) null else rounded)
            },
            valueRange = 0f..5f,
            steps = 4,
            enabled = enabled,
            colors = SliderDefaults.colors(
thumbColor = QringPalette.brand,
activeTrackColor = QringPalette.brand
            )
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    suffix: String,
    onValueChange: (Float) -> Unit,
    enabled: Boolean
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = QringPalette.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${Math.round(value)}$suffix",
                fontSize = 13.sp,
                color = QringPalette.textSecondary
            )
        }
        Slider(
            value = value,
            onValueChange = { onValueChange(Math.round(it).toFloat()) },
            valueRange = min..max,
            enabled = enabled,
            colors = SliderDefaults.colors(
thumbColor = QringPalette.brand,
activeTrackColor = QringPalette.brand
            )
        )
    }
}

@Composable
private fun ToggleChip(
    label: String,
    active: Boolean,
    bold: Boolean,
    italic: Boolean,
    underline: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) QringPalette.brand else QringPalette.surface)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
            color = if (active) Color.White else QringPalette.textPrimary
        )
    }
}
