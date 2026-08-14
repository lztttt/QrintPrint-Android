package com.qring.printer.ui.wordbook

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.printer.model.ConnState
import com.qring.printer.ui.common.FontList
import com.qring.printer.ui.common.PrintWarningDialog
import com.qring.printer.ui.theme.Metrics
import com.qring.printer.ui.theme.ONLINE
import com.qring.printer.ui.theme.QringPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordbookScreen(
    navController: androidx.navigation.NavController,
    viewModel: WordbookViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshBooks()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QringPalette.pageBg)
    ) {
        // 顶栏
        TopAppBar(
            title = { Text("单词本打印") },
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
        if (uiState.previewBitmap != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Metrics.PAGE_PADDING.dp)
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                ) {
                    Image(
                        bitmap = uiState.previewBitmap!!.asImageBitmap(),
                        contentDescription = "预览",
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        }

        // 中间内容
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            modifier = Modifier.weight(1f)
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

                // 单词本选择
                Text(
                    text = "选择单词本",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = QringPalette.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))

                uiState.books.forEach { book ->
                    WordbookRow(
                        book = book,
                        isSelected = book.id == uiState.selectedBookId,
                        downloading = uiState.downloadingBookId == book.id,
                        downloadProgress = uiState.downloadProgress,
                        onSelect = { viewModel.selectBook(book.id) },
                        onDownload = { viewModel.downloadBook(book.id) },
                        onDelete = { viewModel.deleteBook(book.id) }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 打印选项
                if (uiState.selectedBookId.isNotEmpty() && uiState.books.find { it.id == uiState.selectedBookId }?.downloaded == true) {
                    PrintOptionsCard(uiState, viewModel)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Spacer(modifier = Modifier.height(80.dp))
            }
        }

        // 底部操作栏
        BottomActionBar(
            printing = uiState.printing,
            canPrint = uiState.wordsToPrint.isNotEmpty() &&
                uiState.selectedBookId.isNotEmpty() &&
                uiState.books.firstOrNull { it.id == uiState.selectedBookId }?.downloaded == true,
            resultMessage = uiState.resultMessage,
            resultOk = uiState.resultOk,
            onPrint = viewModel::print
        )
    }

    PrintWarningDialog(onGoBack = { navController.popBackStack() })
}

@Composable
private fun WordbookRow(
    book: WordbookBookUi,
    isSelected: Boolean,
    downloading: Boolean,
    downloadProgress: Float,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) QringPalette.brand.copy(alpha = 0.08f) else QringPalette.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = QringPalette.textPrimary
                )
                if (book.downloaded) {
                    Text(
                        text = "共 ${book.totalWords} 词 · 进度 ${book.progress}/${book.totalWords}",
                        fontSize = 11.sp,
                        color = QringPalette.textSecondary
                    )
                } else {
                    Text(
                        text = "未下载",
                        fontSize = 11.sp,
                        color = QringPalette.textSecondary
                    )
                }
            }

            if (downloading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(80.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.width(80.dp),
                        color = QringPalette.brand
                    )
                    Text(
                        text = "${(downloadProgress * 100).toInt()}%",
                        fontSize = 10.sp,
                        color = QringPalette.textSecondary
                    )
                }
            } else if (book.downloaded) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = QringPalette.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "下载",
                        tint = QringPalette.brand,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PrintOptionsCard(
    uiState: WordbookUiState,
    viewModel: WordbookViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "打印选项",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = QringPalette.textPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 进度信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "当前进度",
                    fontSize = 13.sp,
                    color = QringPalette.textSecondary
                )
                Text(
                    text = "${uiState.currentProgress} / ${uiState.totalWords}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = QringPalette.brand
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 进度输入框：可直接设置进度。
            // 输入只在失焦 / 回车时提交，避免一边输入一边把进度重置成 0
            var progressInput by remember(uiState.selectedBookId) { mutableStateOf(uiState.currentProgress.toString()) }
            LaunchedEffect(uiState.currentProgress) {
                progressInput = uiState.currentProgress.toString()
            }
            val focusManager = LocalFocusManager.current
            val maxProgress = if (uiState.totalWords > 0) uiState.totalWords - 1 else 0
            val commitProgress: () -> Unit = {
                val parsed = progressInput.toIntOrNull() ?: uiState.currentProgress
                val clamped = parsed.coerceIn(0, maxProgress)
                progressInput = clamped.toString()
                if (clamped != uiState.currentProgress) {
                    viewModel.setProgress(clamped)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("设置进度", fontSize = 13.sp, color = QringPalette.textSecondary)
                OutlinedTextField(
                    value = progressInput,
                    onValueChange = { input -> progressInput = input.filter { it.isDigit() } },
                    label = { Text("词序号", fontSize = 11.sp) },
                    modifier = Modifier
                        .width(100.dp)
                        .onFocusChanged { if (!it.isFocused) commitProgress() },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        commitProgress()
                        focusManager.clearFocus()
                    }),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { viewModel.resetProgress() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                ) {
                    Text("重置进度", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 词数选择
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("打印词数", fontSize = 13.sp, color = QringPalette.textSecondary)
                Text("${uiState.wordCount} 词", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.brand)
            }
            Slider(
                value = uiState.wordCount.toFloat(),
                onValueChange = { viewModel.setWordCount(it.toInt()) },
                valueRange = 5f..100f,
                steps = 18
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 字号
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("字号", fontSize = 13.sp, color = QringPalette.textSecondary)
                Text("${uiState.fontSize.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.brand)
            }
            Slider(
                value = uiState.fontSize,
                onValueChange = viewModel::setFontSize,
                valueRange = 12f..36f
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 行距
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("行距", fontSize = 13.sp, color = QringPalette.textSecondary)
                Text("${uiState.lineSpacing.toInt()} pt", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.brand)
            }
            Slider(
                value = uiState.lineSpacing,
                onValueChange = viewModel::setLineSpacing,
                valueRange = 4f..30f
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 左边距
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("左边距", fontSize = 13.sp, color = QringPalette.textSecondary)
                Text("${uiState.leftMargin.toInt()} pt", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.brand)
            }
            Slider(
                value = uiState.leftMargin,
                onValueChange = viewModel::setLeftMargin,
                valueRange = 0f..40f
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 字体选择
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("字体", fontSize = 13.sp, color = QringPalette.textSecondary)
                var showFontMenu by remember { mutableStateOf(false) }
                Box {
                    Text(
                        text = FontList.fontLabel(uiState.fontFamilies.getOrElse(uiState.fontFamilyIndex) { "sans-serif" }),
                        fontSize = 13.sp,
                        color = QringPalette.textPrimary,
                        modifier = Modifier.clickable { showFontMenu = true }
                    )
                    DropdownMenu(
                        expanded = showFontMenu,
                        onDismissRequest = { showFontMenu = false }
                    ) {
                        uiState.fontFamilies.forEachIndexed { index, family ->
                            DropdownMenuItem(
                                text = { Text(FontList.fontLabel(family)) },
                                onClick = {
                                    viewModel.setFontFamilyIndex(index)
                                    showFontMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 打印内容选项
            Text(
                text = "打印内容",
                fontSize = 13.sp,
                color = QringPalette.textSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = uiState.showChinese,
                    onCheckedChange = { viewModel.toggleShowChinese() }
                )
                Text("中文释义", fontSize = 13.sp, color = QringPalette.textPrimary)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = uiState.showPos,
                    onCheckedChange = { viewModel.toggleShowPos() }
                )
                Text("词性标注", fontSize = 13.sp, color = QringPalette.textPrimary)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = uiState.showPhrases,
                    onCheckedChange = { viewModel.toggleShowPhrases() }
                )
                Text("例句短语", fontSize = 13.sp, color = QringPalette.textPrimary)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = uiState.hideMode,
                    onCheckedChange = { viewModel.toggleHideMode() }
                )
                Text("默写模式（隐藏中文，留空行）", fontSize = 13.sp, color = QringPalette.brand)
            }
        }
    }
}

@Composable
private fun ConnectionBanner(printerStatus: com.qring.printer.model.PrinterStatus) {
    val connected = printerStatus.connState == ConnState.CONNECTED
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) ONLINE.copy(alpha = 0.08f) else Color(0xFFFF4D4F).copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (connected) ONLINE else Color(0xFFFF4D4F))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (connected) "已连接：${printerStatus.deviceName}" else "打印机未连接",
                fontSize = 12.sp,
                color = if (connected) ONLINE else Color(0xFFFF4D4F)
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
            Spacer(modifier = Modifier.height(8.dp))
        }
        Button(
            onClick = onPrint,
            enabled = !printing && canPrint,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = QringPalette.brand,
                disabledContainerColor = QringPalette.brand.copy(alpha = 0.4f)
            )
        ) {
            if (printing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("打印", fontSize = 15.sp)
            }
        }
    }
}
