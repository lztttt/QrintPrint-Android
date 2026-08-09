package com.qring.print.ui.codeprint

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
import androidx.compose.material.icons.filled.DataMatrix
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import com.qring.print.ui.theme.BRAND
import com.qring.print.ui.theme.Metrics
import com.qring.print.ui.theme.ONLINE
import com.qring.print.ui.theme.QringPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodePrintScreen(
    navController: androidx.navigation.NavController,
    viewModel: CodePrintViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QringPalette.pageBg)
    ) {
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

            // 内容输入
            ContentInput(
                content = uiState.content,
                onContentChange = viewModel::updateContent,
                enabled = !uiState.printing
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 码制选择
            CodeTypeSelector(
                selectedIndex = uiState.codeTypeIndex,
                onSelect = viewModel::setCodeTypeIndex,
                enabled = !uiState.printing
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = viewModel::generateAndPreview,
                    enabled = !uiState.printing && !uiState.busy && uiState.content.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QringPalette.surfaceSunken,
                        contentColor = QringPalette.textPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = QringPalette.textPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.preview))
                }

                Button(
                    onClick = viewModel::print,
                    enabled = !uiState.printing && !uiState.busy && uiState.previewBitmap != null,
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

            // 结果
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
        CodePreviewSheet(
            bitmap = uiState.previewBitmap!!,
            onDismiss = viewModel::dismissPreview
        )
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
            cursorBrush = SolidColor(BRAND),
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
                itemsIndexed(CODE_TYPES.filter { it.category == CodeCategory.TWO_D }) { idx, codeType ->
                    val realIndex = CODE_TYPES.indexOf(codeType)
                    val active = realIndex == selectedIndex
                    CodeTypeChip(
                        label = codeType.label,
                        active = active,
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
                itemsIndexed(CODE_TYPES.filter { it.category == CodeCategory.ONE_D }) { idx, codeType ->
                    val realIndex = CODE_TYPES.indexOf(codeType)
                    val active = realIndex == selectedIndex
                    CodeTypeChip(
                        label = codeType.label,
                        active = active,
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
            .background(if (active) BRAND else QringPalette.surfaceSunken)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodePreviewSheet(
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
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "打印预览",
                fontSize = 14.sp,
                color = QringPalette.textSecondary,
                modifier = Modifier.fillMaxWidth()
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
