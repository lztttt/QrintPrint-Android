package com.qring.print.ui.imageprint

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Image
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
import com.qring.print.MainViewModel
import com.qring.print.R
import com.qring.print.model.ConnState
import com.qring.print.protocol.DITHER_OPTIONS
import com.qring.print.protocol.DitherMode
import com.qring.print.ui.theme.BRAND
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

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.PAGE_PADDING.dp)
                .padding(bottom = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // 连接状态
            ConnectionBanner(printerStatus = printerStatus)

            Spacer(modifier = Modifier.height(12.dp))

            // 选图区域
            ImagePickerArea(
                hasImage = uiState.imageUri.isNotEmpty(),
                busy = uiState.busy,
                onPick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )

            if (uiState.imageUri.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))

                // 抖动模式选择
                DitherSelector(
                    selectedMode = uiState.ditherMode,
                    onModeChange = { viewModel.reRenderWithDither(it) },
                    enabled = !uiState.busy && !uiState.printing
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 浓度调节
                ThicknessSlider(
                    thickness = uiState.thickness,
                    onThicknessChange = viewModel::setThickness,
                    enabled = !uiState.printing
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { /* Preview shown automatically after decode */ },
                        enabled = !uiState.printing && !uiState.busy && uiState.sourceGray != null,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = QringPalette.surfaceSunken,
                            contentColor = QringPalette.textPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("预览")
                    }

                    Button(
                        onClick = viewModel::print,
                        enabled = !uiState.printing && !uiState.busy && uiState.sourceGray != null,
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
                            Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.print))
                    }
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
        ImagePreviewSheet(
            bitmap = uiState.previewBitmap!!,
            onDismiss = viewModel::dismissPreview
        )
    }
}

@Composable
private fun ImagePickerArea(
    hasImage: Boolean,
    busy: Boolean,
    onPick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clickable(enabled = !busy, onClick = onPick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = BRAND)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("处理中…", color = QringPalette.textSecondary, fontSize = 14.sp)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = BRAND
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (hasImage) "点击更换图片" else "点击选择图片",
                        color = QringPalette.textSecondary,
                        fontSize = 14.sp
                    )
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
                text = "抖动模式",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DITHER_OPTIONS.forEach { option ->
                    val active = option.mode == selectedMode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) BRAND else QringPalette.surfaceSunken)
                            .clickable(enabled = enabled) { onModeChange(option.mode) }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = option.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (active) Color.White else QringPalette.textPrimary
                            )
                            Text(
                                text = option.hint.split(" · ").getOrNull(0) ?: "",
                                fontSize = 10.sp,
                                color = if (active) Color.White.copy(alpha = 0.8f) else QringPalette.textSecondary
                            )
                        }
                    }
                }
            }
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
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
                    thumbColor = BRAND,
                    activeTrackColor = BRAND
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImagePreviewSheet(
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
                text = "纸宽 384 点 · 打印预览",
                fontSize = 12.sp,
                color = QringPalette.textSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "预览",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(1.dp, QringPalette.paperEdge, RoundedCornerShape(8.dp)),
                contentScale = ContentScale.FillWidth
            )
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
