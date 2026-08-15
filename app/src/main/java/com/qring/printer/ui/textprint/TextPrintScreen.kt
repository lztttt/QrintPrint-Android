package com.qring.printer.ui.textprint

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.printer.R
import com.qring.printer.model.ConnState
import com.qring.printer.ui.common.FontList
import com.qring.printer.ui.common.PrintWarningDialog
import com.qring.printer.ui.theme.Metrics
import com.qring.printer.ui.theme.ONLINE
import com.qring.printer.ui.theme.QringPalette
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextPrintScreen(
    navController: androidx.navigation.NavController,
    viewModel: TextPrintViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()

    // 字体文件选择器
    val fontPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importFont(uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadFonts()
    }

    // 自动预览：内容 / 排版参数变化后防抖渲染，不弹窗
    LaunchedEffect(
        uiState.text, uiState.fontSize, uiState.bold, uiState.italic, uiState.underline,
        uiState.letterSpacing, uiState.lineSpacing, uiState.pageMargin, uiState.fontFamilyIndex,
        uiState.landscape
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
            margin = uiState.pageMargin,
            landscape = uiState.landscape,
            overflow = uiState.landscapeOverflow,
            alignment = uiState.alignment,
            printWidth = if (uiState.landscapeTargetWidth > 0) uiState.landscapeTargetWidth else (uiState.previewBitmap?.height ?: 0)
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
                onThicknessChange = viewModel::setThickness,
                onLandscapeChange = viewModel::setLandscape,
                onLandscapeWidthChange = viewModel::setLandscapeTargetWidth,
                onAlignmentChange = viewModel::setAlignment,
                onImportFont = { fontPickerLauncher.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream", "*/*")) },
                onDeleteFont = viewModel::deleteImportedFont,
                isImportedFont = viewModel::isImportedFont,
                alignment = uiState.alignment
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

    // 打印前状态检查弹窗
    PrintWarningDialog(onGoBack = { navController.popBackStack() })
}

@Composable
private fun InputAndSettings(
    uiState: TextPrintUiState,
    printerStatus: com.qring.printer.model.PrinterStatus,
    onTextChange: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onBoldToggle: () -> Unit,
    onItalicToggle: () -> Unit,
    onUnderlineToggle: () -> Unit,
    onLetterSpacingChange: (Float) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onPageMarginChange: (Float) -> Unit,
    onFontFamilyChange: (Int) -> Unit,
    onThicknessChange: (Int?) -> Unit,
    onLandscapeChange: (Boolean) -> Unit,
    onLandscapeWidthChange: (Int) -> Unit,
    onAlignmentChange: (TextAlignment) -> Unit,
    onImportFont: () -> Unit = {},
    onDeleteFont: (String) -> Unit = {},
    isImportedFont: (String) -> Boolean = { false },
    alignment: TextAlignment
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

            // 文本输入（横排时单行横向滚动编辑）
            TextInputArea(
                text = uiState.text,
                onTextChange = onTextChange,
                enabled = !uiState.printing,
                horizontalScrollEnabled = uiState.landscape
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
                    // 字体选择 + 导入
                    FontSelectorRow(
                        families = uiState.fontFamilies,
                        selectedIndex = uiState.fontFamilyIndex,
                        onSelect = onFontFamilyChange,
                        onImportFont = onImportFont,
                        onDeleteFont = onDeleteFont,
                        isImportedFont = isImportedFont,
                        enabled = !uiState.printing
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "共 ${uiState.fontFamilies.size} 种 · 支持 TTF/OTF 导入",
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

                    Spacer(modifier = Modifier.height(8.dp))

                    // 横排切换
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "横排打印",
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp,
                            color = QringPalette.textPrimary
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(QringPalette.surface)
                                .clickable(enabled = !uiState.printing) { onLandscapeChange(!uiState.landscape) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (uiState.landscape) "横排" else "竖排",
                                fontSize = 13.sp,
                                color = QringPalette.brand
                            )
                        }
                    }

                    // 横排打印宽度（仅横排显示）：按实际打印宽度（点）设置，可手动输入
                    if (uiState.landscape) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("打印宽度", modifier = Modifier.weight(1f), fontSize = 13.sp, color = QringPalette.textPrimary)
                            if (uiState.landscapeTargetWidth > 0) {
                                Text(
                                    "${uiState.landscapeTargetWidth} 点(${String.format("%.1f", uiState.landscapeTargetWidth / 8.0)}mm)",
                                    fontSize = 13.sp, color = QringPalette.brand
                                )
                            } else {
                                val autoW = uiState.previewBitmap?.height ?: 0
                                Text(
                                    if (autoW > 0) "自适应 ${autoW} 点(${String.format("%.1f", autoW / 8.0)}mm)"
                                    else "自适应原尺寸",
                                    fontSize = 12.sp, color = QringPalette.brand
                                )
                            }
                        }
                        Text(
                            text = "设置横排实际打印宽度（超 384 点自动分段）；0 或自适应 = 原尺寸",
                            fontSize = 10.sp,
                            color = QringPalette.textSecondary
                        )
                        Slider(
                            value = (if (uiState.landscapeTargetWidth > 0) uiState.landscapeTargetWidth else 384).toFloat(),
                            onValueChange = { onLandscapeWidthChange(Math.round(it).toInt()) },
                            valueRange = 100f..1200f,
                            steps = 21,
                            enabled = !uiState.printing,
                            colors = SliderDefaults.colors(thumbColor = QringPalette.brand, activeTrackColor = QringPalette.brand)
                        )
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = if (uiState.landscapeTargetWidth > 0) uiState.landscapeTargetWidth.toString() else "",
                                onValueChange = { v ->
                                    val n = v.filter { it.isDigit() }.toIntOrNull() ?: 0
                                    onLandscapeWidthChange(n)
                                },
                                label = { Text("宽度(点)，0=自适应") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                enabled = !uiState.printing,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = TextStyle(fontSize = 14.sp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = { onLandscapeWidthChange(0) },
                                enabled = !uiState.printing
                            ) { Text("自适应", fontSize = 12.sp, color = QringPalette.brand) }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 对齐方式
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "对齐",
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp,
                            color = QringPalette.textPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val alignOptions = if (uiState.landscape) {
                            listOf(TextAlignment.LEFT, TextAlignment.CENTER, TextAlignment.RIGHT)
                        } else {
                            TextAlignment.entries.toList()
                        }
                        alignOptions.forEach { option ->
                            AlignChip(
                                label = option.label,
                                active = alignment == option,
                                modifier = Modifier.weight(1f),
                                onTap = { onAlignmentChange(option) }
                            )
                        }
                    }
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
    margin: Float,
    landscape: Boolean = false,
    overflow: Boolean = false,
    alignment: TextAlignment = TextAlignment.LEFT,
    printWidth: Int = 0
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
                    if (landscape)
                        "横排打印宽 ${preview.width} 点(${String.format("%.1f", preview.width / 8.0)}mm) · 上下滚动查看 · ${alignment.label}"
                    else
                        "宽 384 点(${String.format("%.1f", 384 / 8.0)}mm) × 高 ${preview.height} 点(${String.format("%.1f", preview.height / 8.0)}mm) · ${alignment.label}"
                else if (text.isEmpty())
                    "输入内容后自动预览"
                else
                    "正在渲染预览…",
                fontSize = 11.sp,
                color = QringPalette.textSecondary
            )
        Spacer(modifier = Modifier.height(6.dp))
        if (overflow) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFF4D4F).copy(alpha = 0.1f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "横排内容较长：将自动分段打印（每段 384 点宽），完整输出不会漏内容",
                    modifier = Modifier.padding(8.dp),
                    color = Color(0xFFFF4D4F),
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        // 使用 BoxWithConstraints 获取可用宽度，计算画布缩放比
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
        ) {
            val canvasWidthDp = maxWidth.value
            // 基于 384 点计算缩放比（与自定义打印画布一致）
            val scale = canvasWidthDp / 384f
            val scrollState = rememberScrollState()

            when {
                preview != null -> {
                    if (landscape) {
                        // 横排：文字正着显示（不旋转），按打印宽度等比缩放（字符不变形）。
                        // 渲染图 W×H；宽度映射到画布宽，高度按比例 → 等比，不拉伸。
                        val previewW = preview.width.coerceAtLeast(1)
                        val dispH = preview.height.toFloat() * (canvasWidthDp / previewW)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = "横排打印预览",
                                modifier = Modifier
                                    .width(canvasWidthDp.dp)
                                    .height(dispH.dp),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                    } else {
                        // 竖排：文字正着显示，垂直滚动（preview 尺寸 384×H）
                        val contentH = (preview.height * scale)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = "打印预览",
                                modifier = Modifier
                                    .width(canvasWidthDp.dp)
                                    .height(contentH.dp),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                    }
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

@Composable
private fun TextInputArea(
text: String,
onTextChange: (String) -> Unit,
enabled: Boolean,
horizontalScrollEnabled: Boolean = false
) {
val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
Card(
modifier = Modifier
.fillMaxWidth()
.height(140.dp),
colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken),
shape = RoundedCornerShape(12.dp)
) {
Box {
BasicTextField(
value = text,
onValueChange = onTextChange,
enabled = enabled,
        modifier = Modifier
        .fillMaxSize()
        .padding(12.dp)
        .padding(end = 40.dp),
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
// 粘贴按钮
IconButton(
onClick = {
val clipText = clipboardManager.getText()?.text ?: ""
if (clipText.isNotEmpty()) {
onTextChange(clipText)
}
},
modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
) {
Icon(
imageVector = Icons.Default.ContentPaste,
contentDescription = "粘贴",
tint = QringPalette.textSecondary,
modifier = Modifier.size(20.dp)
)
}
}
}
}

@Composable
private fun FontSelectorRow(
    families: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onImportFont: () -> Unit = {},
    onDeleteFont: (String) -> Unit = {},
    isImportedFont: (String) -> Boolean = { false },
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
        Spacer(modifier = Modifier.width(4.dp))
        // 导入字体按钮
        androidx.compose.material3.IconButton(
            onClick = onImportFont,
            enabled = enabled,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "导入字体",
                tint = QringPalette.brand,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            families.forEachIndexed { index, family ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                FontList.fontLabel(family),
                                fontSize = 13.sp,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            if (isImportedFont(family)) {
                                androidx.compose.material3.TextButton(
                                    onClick = { onDeleteFont(family) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "删除字体",
                                        tint = androidx.compose.ui.graphics.Color.Red,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    },
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

@Composable
private fun AlignChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit
) {
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) QringPalette.brand else QringPalette.surface)
            .clickable(onClick = onTap),
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
