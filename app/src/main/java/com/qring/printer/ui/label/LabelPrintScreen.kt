package com.qring.printer.ui.label

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.bt.PrintResult
import com.qring.printer.model.ConnState
import com.qring.printer.model.PrinterStatus
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.RasterData
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.WIDTH_BYTES
import com.qring.printer.protocol.createBinaryCanvas
import com.qring.printer.protocol.blitBinary
import com.qring.printer.protocol.packBinaryToRaster
import com.qring.printer.protocol.renderTextToPixelMap
import com.qring.printer.protocol.renderTextToPixelMapIn
import com.qring.printer.protocol.TextRenderOptions
import com.qring.printer.protocol.bitmapToGray
import com.qring.printer.protocol.ditherToBinary
import com.qring.printer.protocol.DitherMode
import com.qring.printer.ui.common.PrintWarningDialog
import com.qring.printer.ui.theme.QringPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── 数据模型 ──────────────────────────────────────────────

data class LabelContent(
    val id: String,
    val text: String = ""
)

data class LabelConfig(
    val labelHeight: Int = 80,       // 标签纸高度（点，内部用）
    val gapHeight: Int = 20,         // 标签之间空白高度（点，内部用）
    val copies: Int = 1,             // 份数
    val fontSize: Float = 24f,
    val bold: Boolean = false,
    val pageMargin: Float = 8f,      // 上下边距
    val leftMarginDots: Int = 0,     // 左边距（点）
    val rightMarginDots: Int = 0      // 右边距（点）
) {
    // 1mm = 8dots (384dots / 48mm)
    val labelHeightMm: Float get() = labelHeight / 8f
    val gapHeightMm: Float get() = gapHeight / 8f
    val leftMarginMm: Float get() = leftMarginDots / 8f
    val rightMarginMm: Float get() = rightMarginDots / 8f
}

data class LabelState(
    val config: LabelConfig = LabelConfig(),
    val contents: List<LabelContent> = listOf(LabelContent(java.util.UUID.randomUUID().toString(), "")),
    val previewBitmap: Bitmap? = null,
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false
)

// ── ViewModel ────────────────────────────────────────────

class LabelViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val _state = MutableStateFlow(LabelState())
    val state: StateFlow<LabelState> = _state.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    fun addContent() {
        _state.update { current ->
            current.copy(contents = current.contents + LabelContent(java.util.UUID.randomUUID().toString(), ""))
        }
        updatePreview()
    }

    fun updateContent(id: String, text: String) {
        _state.update { current ->
            current.copy(contents = current.contents.map { if (it.id == id) it.copy(text = text) else it })
        }
        updatePreview()
    }

    fun deleteContent(id: String) {
        _state.update { current ->
            current.copy(contents = current.contents.filter { it.id != id })
        }
        updatePreview()
    }

    fun setLabelHeightMm(mm: Float) {
        val dots = (mm * 8).toInt().coerceIn(10, 2000)
        _state.update { it.copy(config = it.config.copy(labelHeight = dots)) }
        updatePreview()
    }

    fun setGapHeightMm(mm: Float) {
        val dots = (mm * 8).toInt().coerceIn(0, 500)
        _state.update { it.copy(config = it.config.copy(gapHeight = dots)) }
        updatePreview()
    }

    fun setLeftMarginMm(mm: Float) {
        val dots = (mm * 8).toInt().coerceIn(0, 160)
        _state.update { it.copy(config = it.config.copy(leftMarginDots = dots)) }
        updatePreview()
    }

    fun setRightMarginMm(mm: Float) {
        val dots = (mm * 8).toInt().coerceIn(0, 160)
        _state.update { it.copy(config = it.config.copy(rightMarginDots = dots)) }
        updatePreview()
    }

    fun setCopies(value: Int) {
        _state.update { it.copy(config = it.config.copy(copies = value.coerceIn(1, 99))) }
        updatePreview()
    }

    fun setFontSize(value: Float) {
        _state.update { it.copy(config = it.config.copy(fontSize = value)) }
        updatePreview()
    }

    fun setBold(value: Boolean) {
        _state.update { it.copy(config = it.config.copy(bold = value)) }
        updatePreview()
    }

    fun setPageMargin(value: Float) {
        _state.update { it.copy(config = it.config.copy(pageMargin = value)) }
        updatePreview()
    }

    private fun buildOptions(): TextRenderOptions {
        val cfg = _state.value.config
        return TextRenderOptions(
            fontSize = cfg.fontSize,
            bold = cfg.bold,
            margin = cfg.pageMargin
        )
    }

    /** 计算文字可用宽度（扣减左右边距） */
    private fun textUsableWidth(): Float {
        val cfg = _state.value.config
        return (WIDTH_DOTS - cfg.leftMarginDots - cfg.rightMarginDots).toFloat()
    }

    fun updatePreview() {
        val state = _state.value
        val hasText = state.contents.any { it.text.isNotBlank() }
        if (!hasText) {
            val old = _state.value.previewBitmap
            if (old != null) {
                _state.value = _state.value.copy(previewBitmap = null)
                old.recycle()
            }
            return
        }
        if (state.printing) return
        viewModelScope.launch {
            try {
                val old = _state.value.previewBitmap
                val bitmap = withContext(Dispatchers.Default) {
                    renderLabelPreview(state)
                }
                _state.value = _state.value.copy(previewBitmap = bitmap)
                old?.let { if (it != bitmap) { delay(150); it.recycle() } }
            } catch (e: Exception) { }
        }
    }

    /** 渲染标签预览位图：所有标签纵向排列，标签之间有间隔 */
    private fun renderLabelPreview(state: LabelState): Bitmap {
        val cfg = state.config
        val validContents = state.contents.filter { it.text.isNotBlank() }
        if (validContents.isEmpty()) {
            return Bitmap.createBitmap(WIDTH_DOTS, 1, Bitmap.Config.ARGB_8888)
        }

        // 每个标签渲染为 384 宽 × labelHeight 高的位图
        val labelBitmaps = validContents.map { content ->
            val opts = buildOptions()
            val usableWidth = textUsableWidth()
            // 按可用宽度渲染文字
            val textBmp = if (usableWidth < WIDTH_DOTS) {
                renderTextToPixelMapIn(content.text, opts, usableWidth)
            } else {
                renderTextToPixelMap(content.text, opts)
            }
            // 把文字位图放到标签画布上，水平居中在可用区域内，垂直居中
            val labelBmp = Bitmap.createBitmap(WIDTH_DOTS, cfg.labelHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(labelBmp)
            canvas.drawColor(Color.WHITE)
            val yOffset = ((cfg.labelHeight - textBmp.height) / 2f).coerceAtLeast(0f)
            // 水平偏移到可用区域居中
            val xOffset = cfg.leftMarginDots.toFloat() +
                ((usableWidth - textBmp.width) / 2f).coerceAtLeast(0f)
            canvas.drawBitmap(textBmp, xOffset, yOffset, null)
            textBmp.recycle()
            labelBmp
        }

        // 合成总画布
        val totalHeight = (cfg.labelHeight + cfg.gapHeight) * labelBitmaps.size * cfg.copies
        val result = Bitmap.createBitmap(WIDTH_DOTS, maxOf(1, totalHeight), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        var currentY = 0
        for (copy in 0 until cfg.copies) {
            for (bmp in labelBitmaps) {
                canvas.drawBitmap(bmp, 0f, currentY.toFloat(), null)
                currentY += cfg.labelHeight
                // 间隔区域留白
                if (cfg.gapHeight > 0) {
                    currentY += cfg.gapHeight
                }
            }
        }

        labelBitmaps.forEach { it.recycle() }
        return result
    }

    /** 生成打印用光栅数据 */
    private fun renderLabelRaster(state: LabelState): RasterData {
        val cfg = state.config
        val validContents = state.contents.filter { it.text.isNotBlank() }
        if (validContents.isEmpty()) {
            return RasterData(ByteArray(WIDTH_BYTES), WIDTH_BYTES, 1)
        }

        // 渲染每个标签的文字为二值数据
        val labelBinaries = validContents.map { content ->
            val opts = buildOptions()
            val usableWidth = textUsableWidth()
            val textBmp = if (usableWidth < WIDTH_DOTS) {
                renderTextToPixelMapIn(content.text, opts, usableWidth)
            } else {
                renderTextToPixelMap(content.text, opts)
            }
            val gray = bitmapToGray(textBmp)
            val binary = ditherToBinary(gray, DitherMode.NONE, 211)
            textBmp.recycle()
            Triple(binary, gray.width, gray.height)
        }

        // 合成总二值画布
        val totalHeight = (cfg.labelHeight + cfg.gapHeight) * labelBinaries.size * cfg.copies
        val canvasBinary = createBinaryCanvas(WIDTH_DOTS, maxOf(1, totalHeight))

        var currentY = 0
        for (copy in 0 until cfg.copies) {
            for ((binary, w, h) in labelBinaries) {
                val yOffset = ((cfg.labelHeight - h) / 2).coerceAtLeast(0)
                val xOffset = cfg.leftMarginDots +
                    ((cfg.leftMarginDots + cfg.rightMarginDots).let { (WIDTH_DOTS - it - w) / 2 }).coerceAtLeast(0)
                blitBinary(canvasBinary, WIDTH_DOTS, totalHeight, binary, w, h, xOffset, currentY + yOffset)
                currentY += cfg.labelHeight
                if (cfg.gapHeight > 0) {
                    currentY += cfg.gapHeight
                }
            }
        }

        return packBinaryToRaster(canvasBinary, WIDTH_DOTS, totalHeight)
    }

    fun print() {
        val state = _state.value
        if (state.printing) return
        if (printerStatus.value.connState != ConnState.CONNECTED) {
            _state.value = _state.value.copy(
                resultOk = false,
                resultMessage = "请先在首页连接打印机"
            )
            return
        }
        val hasText = state.contents.any { it.text.isNotBlank() }
        if (!hasText) {
            _state.value = _state.value.copy(
                resultOk = false,
                resultMessage = "请输入标签内容"
            )
            return
        }

        _state.value = _state.value.copy(printing = true, resultMessage = "")

        viewModelScope.launch {
            try {
                val fault = withContext(Dispatchers.IO) {
                    printerConnection.preflightCheck()
                }
                if (fault != null) {
                    _state.value = _state.value.copy(
                        printing = false,
                        resultOk = false,
                        resultMessage = fault
                    )
                    return@launch
                }

                val result = withContext(Dispatchers.Default) {
                    val raster = renderLabelRaster(_state.value)
                    withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, null)
                    }
                }

                _state.value = _state.value.copy(
                    printing = false,
                    resultOk = result.ok,
                    resultMessage = result.message
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    printing = false,
                    resultOk = false,
                    resultMessage = "打印失败：${e.message}"
                )
            }
        }
    }

    fun clearResult() {
        _state.value = _state.value.copy(resultMessage = "", resultOk = false)
    }

    override fun onCleared() {
        super.onCleared()
        _state.value.previewBitmap?.recycle()
    }
}

// ── 屏幕 ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelPrintScreen(navController: NavHostController) {
    val viewModel: LabelViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()

    // 自动预览
    LaunchedEffect(
        state.contents.map { it.text }.hashCode(),
        state.config.labelHeight,
        state.config.gapHeight,
        state.config.copies,
        state.config.fontSize,
        state.config.bold,
        state.config.pageMargin,
        state.config.leftMarginDots,
        state.config.rightMarginDots
    ) {
        delay(400)
        viewModel.updatePreview()
    }

    Scaffold(
        containerColor = QringPalette.pageBg,
        topBar = {
            TopAppBar(
                title = { Text("标签纸打印", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = QringPalette.surface,
                    titleContentColor = QringPalette.textPrimary,
                    navigationIconContentColor = QringPalette.textPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::addContent,
                containerColor = QringPalette.brand
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加标签")
            }
        }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
            // 预览
            LabelPreviewCard(
                preview = state.previewBitmap,
                config = state.config,
                contentCount = state.contents.count { it.text.isNotBlank() }
            )

            // 配置区域 + 标签内容列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 8.dp, bottom = 80.dp
                )
            ) {
                // 配置卡片
                item {
                LabelConfigCard(
                    state = state,
                    onLabelHeightMmChange = viewModel::setLabelHeightMm,
                    onGapHeightMmChange = viewModel::setGapHeightMm,
                    onCopiesChange = viewModel::setCopies,
                    onFontSizeChange = viewModel::setFontSize,
                    onBoldChange = viewModel::setBold,
                    onPageMarginChange = viewModel::setPageMargin,
                    onLeftMarginMmChange = viewModel::setLeftMarginMm,
                    onRightMarginMmChange = viewModel::setRightMarginMm
                )
                }

                // 标签内容列表
                items(state.contents) { content ->
                    LabelContentCard(
                        content = content,
                        index = state.contents.indexOf(content) + 1,
                        onTextChange = { viewModel.updateContent(content.id, it) },
                        onDelete = { viewModel.deleteContent(content.id) }
                    )
                }
            }
        }

        // 底部打印按钮
        androidx.compose.animation.AnimatedVisibility(
            visible = true,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(QringPalette.surface)
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp)
            ) {
                if (state.resultMessage.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.resultOk)
                                androidx.compose.ui.graphics.Color(0xFF4CAF50).copy(alpha = 0.1f)
                            else
                                androidx.compose.ui.graphics.Color(0xFFFF4D4F).copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = state.resultMessage,
                            modifier = Modifier.padding(12.dp),
                            color = if (state.resultOk) androidx.compose.ui.graphics.Color(0xFF4CAF50) else androidx.compose.ui.graphics.Color(0xFFFF4D4F),
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                val connected = printerStatus.connState == ConnState.CONNECTED
                Button(
                    onClick = viewModel::print,
                    enabled = !state.printing && state.contents.any { it.text.isNotBlank() } && connected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (state.printing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = androidx.compose.ui.graphics.Color.White,
                            strokeWidth = 2.dp
                        )
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
        }
    }

    // 打印前状态检查弹窗
    PrintWarningDialog(onGoBack = { navController.popBackStack() })
}

// ── 预览卡片 ─────────────────────────────────────────────

@Composable
private fun LabelPreviewCard(
    preview: Bitmap?,
    config: LabelConfig,
    contentCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            val totalHeight = if (contentCount > 0)
                (config.labelHeight + config.gapHeight) * contentCount * config.copies
            else 0
            Text(
                text = if (preview != null)
                    "宽 384 点 · 高 ${preview.height} 点 (${String.format("%.1f", preview.height / 8.0)}mm) · $contentCount 个标签 × ${config.copies} 份"
                else if (contentCount == 0)
                    "输入内容后自动预览"
                else
                    "正在渲染预览…",
                fontSize = 11.sp,
                color = QringPalette.textSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Color.White)
            ) {
                val canvasWidthDp = maxWidth.value
                val scale = canvasWidthDp / 384f
                when {
                    preview != null -> {
                        val contentH = preview.height * scale
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = "标签预览",
                                modifier = Modifier
                                    .width(canvasWidthDp.dp)
                                    .height(contentH.dp),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = "空",
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

// ── 配置卡片 ─────────────────────────────────────────────

@Composable
private fun LabelConfigCard(
    state: LabelState,
    onLabelHeightMmChange: (Float) -> Unit,
    onGapHeightMmChange: (Float) -> Unit,
    onCopiesChange: (Int) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onBoldChange: (Boolean) -> Unit,
    onPageMarginChange: (Float) -> Unit,
    onLeftMarginMmChange: (Float) -> Unit,
    onRightMarginMmChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("标签纸设置", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = QringPalette.textPrimary)
            Spacer(modifier = Modifier.height(12.dp))

            // 标签高度（mm）
            LabelMmSlider(
                label = "标签高度",
                mmValue = state.config.labelHeightMm,
                minMm = 1f,
                maxMm = 250f,
                onValueChange = onLabelHeightMmChange
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 间距空白（mm）
            LabelMmSlider(
                label = "间距空白",
                mmValue = state.config.gapHeightMm,
                minMm = 0f,
                maxMm = 60f,
                onValueChange = onGapHeightMmChange
            )

            Spacer(modifier = Modifier.height(8.dp))

            LabelNumberRow(
                label = "打印份数",
                value = state.config.copies,
                suffix = "份",
                min = 1,
                max = 99,
                onValueChange = onCopiesChange
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 文字设置
            Text("文字设置", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = QringPalette.textPrimary)
            Spacer(modifier = Modifier.height(8.dp))

            // 字号滑块
            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("字号", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                    Text("${state.config.fontSize.toInt()}pt", fontSize = 13.sp, color = QringPalette.textSecondary)
                }
                androidx.compose.material3.Slider(
                    value = state.config.fontSize,
                    onValueChange = { onFontSizeChange(it) },
                    valueRange = 12f..72f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = QringPalette.brand,
                        activeTrackColor = QringPalette.brand
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 上下边距
            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("上下边距", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                    Text("${String.format("%.1f", state.config.pageMargin / 8f)}mm", fontSize = 13.sp, color = QringPalette.textSecondary)
                }
                androidx.compose.material3.Slider(
                    value = state.config.pageMargin,
                    onValueChange = { onPageMarginChange(it) },
                    valueRange = 0f..40f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = QringPalette.brand,
                        activeTrackColor = QringPalette.brand
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 左边距
            LabelMmSlider(
                label = "左边距",
                mmValue = state.config.leftMarginMm,
                minMm = 0f,
                maxMm = 20f,
                onValueChange = onLeftMarginMmChange
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 右边距
            LabelMmSlider(
                label = "右边距",
                mmValue = state.config.rightMarginMm,
                minMm = 0f,
                maxMm = 20f,
                onValueChange = onRightMarginMmChange
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 粗体
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("加粗", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(QringPalette.surfaceSunken)
                        .clickable { onBoldChange(!state.config.bold) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (state.config.bold) "粗" else "常规",
                        fontSize = 13.sp,
                        color = if (state.config.bold) QringPalette.brand else QringPalette.textSecondary,
                        fontWeight = if (state.config.bold) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 总高度预览
            val totalHeight = (state.config.labelHeight + state.config.gapHeight) *
                state.contents.count { it.text.isNotBlank() } * state.config.copies
            Text(
                text = "预计总高度: ${String.format("%.1f", totalHeight / 8.0)}mm (${totalHeight}点)",
                fontSize = 12.sp,
                color = QringPalette.textSecondary
            )
        }
    }
}

@Composable
private fun LabelMmSlider(
    label: String,
    mmValue: Float,
    minMm: Float,
    maxMm: Float,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
            Text("${String.format("%.1f", mmValue)}mm", fontSize = 13.sp, color = QringPalette.textSecondary)
        }
        androidx.compose.material3.Slider(
            value = mmValue,
            onValueChange = { onValueChange(it) },
            valueRange = minMm..maxMm,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = QringPalette.brand,
                activeTrackColor = QringPalette.brand
            )
        )
    }
}

@Composable
private fun LabelNumberRow(
    label: String,
    value: Int,
    suffix: String,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit
) {
    var textValue by remember(value) { mutableStateOf(value.toString()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = QringPalette.textPrimary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = textValue,
                onValueChange = { input ->
                    val filtered = input.filter { it.isDigit() }
                    textValue = filtered
                    filtered.toIntOrNull()?.let { num ->
                        onValueChange(num.coerceIn(min, max))
                    }
                },
                modifier = Modifier.width(80.dp),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, textAlign = TextAlign.Center),
                suffix = { Text(suffix, fontSize = 11.sp, color = QringPalette.textSecondary) }
            )
        }
    }
}

// ── 标签内容卡片 ─────────────────────────────────────────

@Composable
private fun LabelContentCard(
    content: LabelContent,
    index: Int,
    onTextChange: (String) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("标签 $index", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.brand)
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = QringPalette.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = content.text,
                onValueChange = onTextChange,
                label = { Text("打印内容", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
            )
        }
    }
}
