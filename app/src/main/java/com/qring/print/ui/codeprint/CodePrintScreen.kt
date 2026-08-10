package com.qring.print.ui.codeprint

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.print.R
import com.qring.print.model.CODE_TYPES
import com.qring.print.model.CodeCategory
import com.qring.print.model.ConnState
import com.qring.print.ui.theme.Metrics
import com.qring.print.ui.theme.ONLINE
import com.qring.print.ui.theme.QringPalette
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodePrintScreen(
    navController: androidx.navigation.NavController,
    viewModel: CodePrintViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()

    // 实时预览：内容/码制变化后防抖渲染
    LaunchedEffect(uiState.content, uiState.codeTypeIndex) {
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

            Spacer(modifier = Modifier.height(12.dp))

            // 码制选择
            CodeTypeSelector(
                selectedIndex = uiState.codeTypeIndex,
                onSelect = viewModel::setCodeTypeIndex,
                enabled = !uiState.printing
            )
        }

        // 底部：操作按键
        BottomActionBar(
            printing = uiState.printing,
            canPrint = uiState.content.isNotEmpty(),
            resultMessage = uiState.resultMessage,
            resultOk = uiState.resultOk,
            onPrint = viewModel::print
        )
    }
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
