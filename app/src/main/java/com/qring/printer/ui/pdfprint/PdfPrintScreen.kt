package com.qring.printer.ui.pdfprint

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.printer.model.ConnState
import com.qring.printer.ui.common.AdjustmentCard
import com.qring.printer.ui.common.DitherSelector
import com.qring.printer.ui.common.PrintWarningDialog
import com.qring.printer.ui.common.SliderRow
import com.qring.printer.ui.common.ThicknessSlider
import com.qring.printer.ui.common.TransformCard
import com.qring.printer.ui.theme.Metrics
import com.qring.printer.ui.theme.ONLINE
import com.qring.printer.ui.theme.QringPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPrintScreen(
    navController: androidx.navigation.NavController,
    viewModel: PdfPrintViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()
    val context = LocalContext.current

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.openPdf(uri.toString())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QringPalette.pageBg)
    ) {
        TopAppBar(
            title = { Text("PDF 打印") },
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

        // 预览区
        PdfPreviewCard(
            preview = uiState.previewBitmap,
            pageCount = uiState.pageCount,
            currentPage = uiState.currentPage,
            busy = uiState.busy,
            progressText = uiState.progressText,
            onPick = {
                pdfPickerLauncher.launch(arrayOf("application/pdf", "*/*"))
            }
        )

        // 中间设置
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.PAGE_PADDING.dp)
                .padding(top = 12.dp, bottom = 12.dp)
        ) {
            ConnectionBanner(printerStatus)

            Spacer(modifier = Modifier.height(12.dp))

            if (uiState.pageCount > 0) {
                // 页选择
                PageSelectorCard(
                    pages = uiState.pages,
                    currentPage = uiState.currentPage,
                    printAll = uiState.printAll,
                    onSelectPage = viewModel::selectPage,
                    onPrintAllChange = viewModel::setPrintAll,
                    enabled = !uiState.printing && !uiState.busy
                )

                Spacer(modifier = Modifier.height(8.dp))

                AdjustmentCard(
                    contrast = uiState.contrast,
                    brightness = uiState.brightness,
                    sharpness = uiState.sharpness,
                    onContrastChange = viewModel::setContrast,
                    onBrightnessChange = viewModel::setBrightness,
                    onSharpnessChange = viewModel::setSharpness,
                    enabled = !uiState.printing && !uiState.busy
                )

                Spacer(modifier = Modifier.height(8.dp))

                EnhanceModeCard(
                    enhanceMode = uiState.enhanceMode,
                    onEnhanceModeChange = viewModel::setEnhanceMode,
                    enabled = !uiState.printing && !uiState.busy
                )

                if (!uiState.enhanceMode) {
                    Spacer(modifier = Modifier.height(8.dp))

                    DitherSelector(
                        selectedMode = uiState.ditherMode,
                        onModeChange = viewModel::setDitherMode,
                        enabled = !uiState.printing && !uiState.busy
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SliderRow(
                        label = "阈值",
                        value = uiState.threshold.toFloat(),
                        min = 0f,
                        max = 255f,
                        suffix = "",
                        valueText = uiState.threshold.toString(),
                        onValueChange = { viewModel.setThreshold(Math.round(it)) },
                        enabled = !uiState.printing && !uiState.busy
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                ThicknessSlider(
                    thickness = uiState.thickness,
                    onThicknessChange = viewModel::setThickness,
                    enabled = !uiState.printing
                )

                Spacer(modifier = Modifier.height(8.dp))

                TransformCard(
                    rotation = uiState.rotation,
                    flipH = uiState.flipH,
                    flipV = uiState.flipV,
                    invert = uiState.invert,
                    onRotationChange = viewModel::setRotation,
                    onFlipHChange = viewModel::toggleFlipH,
                    onFlipVChange = viewModel::toggleFlipV,
                    onInvertChange = viewModel::toggleInvert,
                    enabled = !uiState.printing && !uiState.busy
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "点击上方卡片选择 PDF 文件（支持图片型 PDF，页面将按图片方式打印）",
                        modifier = Modifier.padding(16.dp),
                        color = QringPalette.textSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // 底部操作栏
        BottomActionBar(
            printing = uiState.printing,
            canPrint = uiState.pageCount > 0 && !uiState.busy,
            progressText = uiState.progressText,
            resultMessage = uiState.resultMessage,
            resultOk = uiState.resultOk,
            onPrint = viewModel::print
        )
    }

    PrintWarningDialog(onGoBack = { navController.popBackStack() })

    // 横向页面推荐弹窗：自动识别宽>高，推荐旋转 90° 打印（最佳分辨率）
    if (uiState.showLandscapeSuggestion) {
        LandscapeSuggestionDialog(
            message = uiState.landscapeSuggestionText,
            onApply = viewModel::applyLandscapeSuggestion,
            onDismiss = viewModel::dismissLandscapeSuggestion
        )
    }
}

@Composable
private fun LandscapeSuggestionDialog(
    message: String,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("推荐旋转打印") },
        text = { Text(message, fontSize = 14.sp) },
        confirmButton = {
            Button(
                onClick = onApply,
                colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand)
            ) {
                Text("旋转 90° 打印（最佳分辨率）")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = QringPalette.textSecondary)
            }
        }
    )
}

@Composable
private fun PdfPreviewCard(
    preview: android.graphics.Bitmap?,
    pageCount: Int,
    currentPage: Int,
    busy: Boolean,
    progressText: String,
    onPick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.PAGE_PADDING.dp)
            .padding(top = 12.dp)
            .clickable(enabled = !busy && pageCount == 0, onClick = onPick),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = when {
                        busy -> progressText.ifEmpty { "正在处理…" }
                        pageCount > 0 -> "共 $pageCount 页 · 第 $currentPage 页预览 · 宽 384 点"
                        else -> "点击选择 PDF"
                    },
                    fontSize = 11.sp,
                    color = QringPalette.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.Add,
                    contentDescription = "选择 PDF",
                    tint = QringPalette.brand,
                    modifier = Modifier.size(16.dp)
                )
            }
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
                    busy -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = QringPalette.brand
                        )
                    }
                    else -> {
                        Text(
                            text = "点击选择 PDF 文件",
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
private fun EnhanceModeCard(
    enhanceMode: Boolean,
    onEnhanceModeChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "文档增强",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = QringPalette.textPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (enhanceMode)
                        "Sauvola 自适应二值化：自动补偿光照/阴影，文字更清晰（适合文档页）"
                    else
                        "普通阈值抖动（适合照片型页面）",
                    fontSize = 11.sp,
                    color = QringPalette.textSecondary
                )
            }
            Switch(
                checked = enhanceMode,
                onCheckedChange = onEnhanceModeChange,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun PageSelectorCard(
    pages: List<PdfPageUi>,
    currentPage: Int,
    printAll: Boolean,
    onSelectPage: (Int) -> Unit,
    onPrintAllChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择页面",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = QringPalette.textPrimary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = printAll,
                        onCheckedChange = { onPrintAllChange(it) },
                        enabled = enabled
                    )
                    Text("打印全部页", fontSize = 12.sp, color = QringPalette.textPrimary)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pages.forEach { page ->
                    val selected = page.index == currentPage
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) QringPalette.brand.copy(alpha = 0.12f) else QringPalette.surfaceSunken)
                            .clickable(enabled = enabled) { onSelectPage(page.index) }
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (page.thumb != null) {
                            Image(
                                bitmap = page.thumb.asImageBitmap(),
                                contentDescription = "第 ${page.index} 页",
                                modifier = Modifier
                                    .height(56.dp)
                                    .width(42.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .height(56.dp)
                                    .width(42.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${page.index}",
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) QringPalette.brand else QringPalette.textSecondary
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
                Text("打印", fontSize = 16.sp)
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
