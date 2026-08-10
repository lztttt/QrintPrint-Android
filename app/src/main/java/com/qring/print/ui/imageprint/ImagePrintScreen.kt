package com.qring.print.ui.imageprint

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.print.R
import com.qring.print.model.ConnState
import com.qring.print.protocol.DITHER_OPTIONS
import com.qring.print.protocol.DitherMode
import com.qring.print.ui.theme.Metrics
import com.qring.print.ui.theme.ONLINE
import com.qring.print.ui.theme.QringPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePrintScreen(
    navController: androidx.navigation.NavController,
    viewModel: ImagePrintViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()
    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.setImageUri(it.toString())
            viewModel.decodeAndPreview()
        }
    }

    // 切换抖动算法 / 阈值后实时重渲染
    LaunchedEffect(uiState.ditherMode, uiState.threshold) {
        if (uiState.sourceGray != null) {
            viewModel.reRender()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QringPalette.pageBg)
    ) {
        // 顶栏
        TopAppBar(
            title = { Text("图片打印") },
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
            hasImage = uiState.imageUri.isNotEmpty(),
            busy = uiState.busy,
            ditherMode = uiState.ditherMode,
            threshold = uiState.threshold,
            onPick = {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        )

        // 中间：设置（可滚动）
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.PAGE_PADDING.dp)
                .padding(top = 12.dp, bottom = 12.dp)
        ) {
            ConnectionBanner(printerStatus = printerStatus)

            Spacer(modifier = Modifier.height(12.dp))

            if (uiState.imageUri.isNotEmpty()) {
                // 抖动算法
                DitherSelector(
                    selectedMode = uiState.ditherMode,
                    onModeChange = viewModel::setDitherMode,
                    enabled = !uiState.busy && !uiState.printing
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 阈值
                SliderRow(
                    label = "阈值",
                    value = uiState.threshold.toFloat(),
                    min = 0f,
                    max = 255f,
                    suffix = "",
                    valueText = uiState.threshold.toString(),
                    onValueChange = { viewModel.setThreshold(Math.round(it)) },
                    enabled = !uiState.busy && !uiState.printing
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 浓度
                ThicknessSlider(
                    thickness = uiState.thickness,
                    onThicknessChange = viewModel::setThickness,
                    enabled = !uiState.printing
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "点击上方卡片选择一张图片",
                        modifier = Modifier.padding(16.dp),
                        color = QringPalette.textSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // 底部：操作按键
        BottomActionBar(
            printing = uiState.printing,
            canPrint = uiState.sourceGray != null,
            resultMessage = uiState.resultMessage,
            resultOk = uiState.resultOk,
            onPrint = viewModel::print
        )
    }
}

@Composable
private fun PreviewCard(
    preview: android.graphics.Bitmap?,
    hasImage: Boolean,
    busy: Boolean,
    ditherMode: DitherMode,
    threshold: Int,
    onPick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.PAGE_PADDING.dp)
            .padding(top = 12.dp)
            .clickable(enabled = !busy, onClick = onPick),
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
                        busy -> "正在处理…"
                        preview != null ->
                            "宽 ${preview.width} 点(${String.format("%.1f", preview.width / 8.0)}mm) × 高 ${preview.height} 点(${String.format("%.1f", preview.height / 8.0)}mm) · ${ditherMode.label()} · 阈值 $threshold"
                        hasImage -> "渲染预览…"
                        else -> "点击选择图片"
                    },
                    fontSize = 11.sp,
                    color = QringPalette.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.AddPhotoAlternate,
                    contentDescription = "选择图片",
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
                            text = "点击选择图片",
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
private fun DitherSelector(
    selectedMode: DitherMode,
    onModeChange: (DitherMode) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "抖动算法",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DITHER_OPTIONS.forEach { option ->
                    val active = option.mode == selectedMode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) QringPalette.brand else QringPalette.surfaceSunken)
                            .clickable(enabled = enabled) { onModeChange(option.mode) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = option.label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (active) Color.White else QringPalette.textPrimary
                        )
                    }
                }
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
    valueText: String,
    onValueChange: (Float) -> Unit,
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
                    text = label,
                    fontSize = 13.sp,
                    color = QringPalette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (suffix.isEmpty()) valueText else "$valueText$suffix",
                    fontSize = 13.sp,
                    color = QringPalette.textSecondary
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = min..max,
                enabled = enabled,
                colors = SliderDefaults.colors(
thumbColor = QringPalette.brand,
activeTrackColor = QringPalette.brand
                )
            )
        }
    }
}

@Composable
private fun ThicknessSlider(
    thickness: Int?,
    onThicknessChange: (Int?) -> Unit,
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
                    text = "打印浓度",
                    fontSize = 13.sp,
                    color = QringPalette.textPrimary,
                    modifier = Modifier.weight(1f)
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
}

@Composable
private fun BottomActionBar(
    printing: Boolean,
    canPrint: Boolean,
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

private fun DitherMode.label(): String =
    DITHER_OPTIONS.firstOrNull { it.mode == this }?.label ?: "无"
