package com.qring.print.ui.textprint

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.qring.print.ui.theme.BRAND
import com.qring.print.ui.theme.Metrics
import com.qring.print.ui.theme.ONLINE
import com.qring.print.ui.theme.QringPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextPrintScreen(
    navController: androidx.navigation.NavController,
    viewModel: TextPrintViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()

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

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.PAGE_PADDING.dp)
                .padding(bottom = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // 连接状态横幅
            ConnectionBanner(printerStatus = printerStatus)

            Spacer(modifier = Modifier.height(12.dp))

            // 文本输入框
            TextInputArea(
                text = uiState.text,
                onTextChange = viewModel::updateText,
                enabled = !uiState.printing
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 排版控制
            FormattingControls(
                fontSize = uiState.fontSize,
                bold = uiState.bold,
                italic = uiState.italic,
                underline = uiState.underline,
                letterSpacing = uiState.letterSpacing,
                lineSpacing = uiState.lineSpacing,
                pageMargin = uiState.pageMargin,
                onFontSizeChange = viewModel::updateFontSize,
                onBoldToggle = viewModel::toggleBold,
                onItalicToggle = viewModel::toggleItalic,
                onUnderlineToggle = viewModel::toggleUnderline,
                onLetterSpacingChange = viewModel::updateLetterSpacing,
                onLineSpacingChange = viewModel::updateLineSpacing,
                onPageMarginChange = viewModel::updatePageMargin,
                enabled = !uiState.printing
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 预览按钮
                Button(
                    onClick = viewModel::renderPreview,
                    enabled = !uiState.printing && uiState.text.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QringPalette.surfaceSunken,
                        contentColor = QringPalette.textPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.preview))
                }

                // 打印按钮
                Button(
                    onClick = viewModel::print,
                    enabled = !uiState.printing && uiState.text.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = BRAND),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.printing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Print,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.print))
                }
            }

            // 结果提示
            if (uiState.resultMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.resultOk)
                            ONLINE.copy(alpha = 0.1f)
                        else
                            Color(0xFFFF4D4F).copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = uiState.resultMessage,
                        modifier = Modifier.padding(12.dp),
                        color = if (uiState.resultOk) ONLINE else Color(0xFFFF4D4F),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    // 预览弹窗
    if (uiState.showPreview && uiState.previewBitmap != null) {
        PreviewSheet(
            bitmap = uiState.previewBitmap!!,
            onDismiss = viewModel::dismissPreview
        )
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
            .height(160.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
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
            cursorBrush = SolidColor(BRAND),
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
private fun FormattingControls(
    fontSize: Float,
    bold: Boolean,
    italic: Boolean,
    underline: Boolean,
    letterSpacing: Float,
    lineSpacing: Float,
    pageMargin: Float,
    onFontSizeChange: (Float) -> Unit,
    onBoldToggle: () -> Unit,
    onItalicToggle: () -> Unit,
    onUnderlineToggle: () -> Unit,
    onLetterSpacingChange: (Float) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onPageMarginChange: (Float) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 字号
            SliderRow(
                label = stringResource(R.string.font_size),
                value = fontSize,
                min = 12f,
                max = 72f,
                suffix = "pt",
                onValueChange = onFontSizeChange,
                enabled = enabled
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 字距
            SliderRow(
                label = stringResource(R.string.letter_spacing),
                value = letterSpacing,
                min = -2f,
                max = 10f,
                suffix = "pt",
                onValueChange = onLetterSpacingChange,
                enabled = enabled
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 行距
            SliderRow(
                label = stringResource(R.string.line_spacing),
                value = lineSpacing,
                min = 0f,
                max = 20f,
                suffix = "pt",
                onValueChange = onLineSpacingChange,
                enabled = enabled
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 边距
            SliderRow(
                label = stringResource(R.string.page_margin),
                value = pageMargin,
                min = 0f,
                max = 40f,
                suffix = "pt",
                onValueChange = onPageMarginChange,
                enabled = enabled
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 样式切换
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToggleChip(
                    label = "B",
                    active = bold,
                    bold = true,
                    italic = false,
                    underline = false,
                    onTap = onBoldToggle,
                    modifier = Modifier.weight(1f)
                )
                ToggleChip(
                    label = "I",
                    active = italic,
                    bold = false,
                    italic = true,
                    underline = false,
                    onTap = onItalicToggle,
                    modifier = Modifier.weight(1f)
                )
                ToggleChip(
                    label = "U",
                    active = underline,
                    bold = false,
                    italic = false,
                    underline = true,
                    onTap = onUnderlineToggle,
                    modifier = Modifier.weight(1f)
                )
            }
        }
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
                thumbColor = BRAND,
                activeTrackColor = BRAND
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
            .background(if (active) BRAND else QringPalette.surfaceSunken)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSheet(
    bitmap: android.graphics.Bitmap,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = QringPalette.pageBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "纸宽 384 点",
                fontSize = 11.sp,
                color = QringPalette.textSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "预览",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White),
                contentScale = ContentScale.FillWidth
            )
        }
    }
}
