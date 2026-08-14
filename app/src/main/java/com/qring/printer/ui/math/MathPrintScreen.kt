package com.qring.printer.ui.math

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.data.HistoryPayloadHolder
import com.qring.printer.data.HistoryRepository
import com.qring.printer.model.ConnState
import com.qring.printer.model.HIST_TYPE_MATH
import com.qring.printer.model.PrinterStatus
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.bitmapToRaster
import com.qring.printer.ui.common.PrintWarningDialog
import com.qring.printer.ui.theme.Metrics
import com.qring.printer.ui.theme.ONLINE
import com.qring.printer.ui.theme.QringPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.random.Random

// ── 口算题类型 ──────────────────────────────────────────────

enum class MathRange(val label: String, val max: Int) {
    TEN("10 以内", 10),
    TWENTY("20 以内", 20),
    HUNDRED("100 以内", 100),
}

enum class MathOp(val label: String, val symbol: String) {
    ADD("加法", "+"),
    SUB("减法", "−"),
    MUL("乘法", "×"),
    DIV("除法", "÷"),
    MIXED("混合", ""),
}

data class MathProblem(
    val a: Int,
    val b: Int,
    val op: MathOp,
    val answer: Int
)

data class MathUiState(
    val selectedRange: MathRange = MathRange.TWENTY,
    val selectedOp: MathOp = MathOp.ADD,
    val problemCount: Int = 20,
    val problems: List<MathProblem> = emptyList(),
    val previewBitmap: Bitmap? = null,
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false,
    val fontSize: Float = 20f,
    val columns: Int = 3,
    val showAnswer: Boolean = false,
    val leftMargin: Float = 8f,      // 左边距（点）
)

class MathPrintViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val historyRepo = HistoryRepository(application)

    private val _uiState = MutableStateFlow(MathUiState())
    val uiState: StateFlow<MathUiState> = _uiState.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    /** 题量滑杆防抖：拖动时只重新生成一次 */
    private var generateJob: Job? = null
    /** 预览防抖：滑块连续变化时只渲染最后一次 */
    private var previewJob: Job? = null
    private var previewGeneration = 0

    init {
        restoreFromHistoryPayload()
        generateProblems()
    }

    /** 从历史记录重打时恢复设置（题目随机，按设置重新生成） */
    private fun restoreFromHistoryPayload() {
        val (type, payload) = HistoryPayloadHolder.consumePayload() ?: return
        if (type != HIST_TYPE_MATH) {
            HistoryPayloadHolder.setPayload(type, payload)
            return
        }
        try {
            val obj = JSONObject(payload)
            val range = MathRange.entries.firstOrNull { it.name == obj.optString("range", "TWENTY") }
                ?: MathRange.TWENTY
            val op = MathOp.entries.firstOrNull { it.name == obj.optString("op", "ADD") }
                ?: MathOp.ADD
            _uiState.value = _uiState.value.copy(
                selectedRange = range,
                selectedOp = op,
                problemCount = obj.optInt("count", 20).coerceIn(10, 200),
                fontSize = obj.optDouble("fontSize", 20.0).toFloat().coerceIn(14f, 32f),
                columns = obj.optInt("columns", 3).coerceIn(1, 3),
                showAnswer = obj.optBoolean("showAnswer", false),
                leftMargin = obj.optDouble("leftMargin", 8.0).toFloat().coerceIn(0f, 40f)
            )
        } catch (e: Exception) { }
    }

    fun setRange(range: MathRange) {
        _uiState.value = _uiState.value.copy(selectedRange = range)
        generateProblems()
    }

    fun setOp(op: MathOp) {
        _uiState.value = _uiState.value.copy(selectedOp = op)
        generateProblems()
    }

    fun setProblemCount(count: Int) {
        _uiState.value = _uiState.value.copy(problemCount = count)
        // 滑杆拖动时防抖，停止拖动后才重新生成
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            delay(200)
            generateProblems()
        }
    }

    fun setFontSize(size: Float) {
        _uiState.value = _uiState.value.copy(fontSize = size)
        updatePreview()
    }

    fun setColumns(count: Int) {
        _uiState.value = _uiState.value.copy(columns = count)
        updatePreview()
    }

    fun toggleShowAnswer() {
        _uiState.value = _uiState.value.copy(showAnswer = !_uiState.value.showAnswer)
        updatePreview()
    }

    fun setLeftMargin(margin: Float) {
        _uiState.value = _uiState.value.copy(leftMargin = margin)
        updatePreview()
    }

    fun regenerate() {
        generateProblems()
    }

    /** 生成口算题 */
    private fun generateProblems() {
        val state = _uiState.value
        val max = state.selectedRange.max
        val problems = mutableListOf<MathProblem>()
        val rng = Random(System.currentTimeMillis())

        for (i in 0 until state.problemCount) {
            val op = if (state.selectedOp == MathOp.MIXED) {
                MathOp.entries.filter { it != MathOp.MIXED }.random(rng)
            } else {
                state.selectedOp
            }

            val (a, b, answer) = when (op) {
                MathOp.ADD -> {
                    // 修正：a + b 必须 ≤ max（10 以内加法结果不超过 10）
                    val a = rng.nextInt(1, max) // 1..max-1，给 b 留空间
                    val b = rng.nextInt(1, max - a + 1)
                    Triple(a, b, a + b)
                }
                MathOp.SUB -> {
                    val a = rng.nextInt(1, max + 1)
                    val b = rng.nextInt(1, a + 1) // 保证结果非负
                    Triple(a, b, a - b)
                }
                MathOp.MUL -> {
                    // 乘法范围缩小，避免数字太大
                    val mulMax = when (max) {
                        10 -> 9
                        20 -> 9
                        else -> 12
                    }
                    val a = rng.nextInt(2, mulMax + 1)
                    val b = rng.nextInt(2, mulMax + 1)
                    Triple(a, b, a * b)
                }
                MathOp.DIV -> {
                    // 除法：先生成商和除数，再算被除数，保证整除
                    val divMax = when (max) {
                        10 -> 9
                        20 -> 9
                        else -> 12
                    }
                    val b = rng.nextInt(2, divMax + 1)
                    val quotient = rng.nextInt(1, divMax + 1)
                    val a = b * quotient
                    Triple(a, b, quotient)
                }
                MathOp.MIXED -> { Triple(0, 0, 0) } // 不会走到
            }
            problems.add(MathProblem(a, b, op, answer))
        }

        // 洗牌打散，避免相邻题目重复/相似（如连续的 1+1=2）
        problems.shuffle(rng)

        _uiState.value = _uiState.value.copy(problems = problems)
        updatePreview()
    }

    /** 生成预览 Bitmap（防抖：滑块连续变化时只渲染最后一次） */
    private fun updatePreview() {
        val state = _uiState.value
        if (state.problems.isEmpty()) return

        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val gen = ++previewGeneration
            try {
                val old = _uiState.value.previewBitmap
                val bitmap = withContext(Dispatchers.Default) {
                    renderMathBitmap(_uiState.value)
                }
                if (gen != previewGeneration) {
                    bitmap.recycle()
                    return@launch
                }
                _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
                old?.let { if (it != bitmap) { delay(150); it.recycle() } }
            } catch (e: Exception) { }
        }
    }

    /** 渲染口算题为 Bitmap */
    private fun renderMathBitmap(state: MathUiState): Bitmap {
        val margin = state.leftMargin
        val fontSize = state.fontSize
        val columns = state.columns
        val usable = WIDTH_DOTS - margin * 2
        val colWidth = usable / columns

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            color = android.graphics.Color.BLACK
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.LEFT
        }

        // 每行高度
        val lineHeight = fontSize + 10f
        val rows = (state.problems.size + columns - 1) / columns
        val totalHeight = (margin + rows * lineHeight + margin).toInt()

        val bitmap = Bitmap.createBitmap(WIDTH_DOTS, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        for (i in state.problems.indices) {
            val col = i % columns
            val row = i / columns

            val x = margin + col * colWidth
            val y = margin + (row + 1) * lineHeight - 4f

            val p = state.problems[i]
            val expr = "${p.a} ${p.op.symbol} ${p.b} = "
            val suffix = if (state.showAnswer) "${p.answer}" else ""

            canvas.drawText(expr, x, y, paint)

            if (state.showAnswer) {
                val ansPaint = Paint(paint).apply {
                    color = android.graphics.Color.argb(200, 200, 0, 0)
                }
                val exprWidth = paint.measureText(expr)
                canvas.drawText(suffix, x + exprWidth, y, ansPaint)
            }
        }

        return bitmap
    }

    fun print() {
        val state = _uiState.value
        if (state.printing) return
        if (printerStatus.value.connState != ConnState.CONNECTED) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请先在首页连接打印机"
            )
            return
        }
        if (state.problems.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请先生成题目"
            )
            return
        }

        _uiState.value = _uiState.value.copy(printing = true, resultMessage = "")

        viewModelScope.launch {
            var thumbBitmap: Bitmap? = null
            try {
                val fault = withContext(Dispatchers.IO) {
                    printerConnection.preflightCheck()
                }
                if (fault != null) {
                    _uiState.value = _uiState.value.copy(
                        printing = false,
                        resultOk = false,
                        resultMessage = fault
                    )
                    return@launch
                }

                val result = withContext(Dispatchers.Default) {
                    // 分块打印：每块最多 150 行，防止大题量（如 200 题 1 列）一次发送超时
                    val columns = state.columns
                    val chunkRows = 150
                    var start = 0
                    var ok = true
                    var failMsg = ""
                    val rows = (state.problems.size + columns - 1) / columns
                    while (start < state.problems.size) {
                        val end = minOf(state.problems.size, start + chunkRows * columns)
                        val chunkState = state.copy(problems = state.problems.subList(start, end))
                        val chunkBmp = renderMathBitmap(chunkState)
                        val chunkRaster = bitmapToRaster(chunkBmp, 212)

                        // 首块顺便生成缩略图（必须在 recycle 之前）
                        if (thumbBitmap == null) {
                            thumbBitmap = Bitmap.createScaledBitmap(
                                chunkBmp, 200, Math.round(200f * chunkBmp.height / chunkBmp.width), true
                            )
                        }
                        chunkBmp.recycle()

                        val r = withContext(Dispatchers.IO) {
                            printerConnection.printRaster(chunkRaster, 1)
                        }
                        if (!r.ok) {
                            ok = false
                            failMsg = r.message
                            break
                        }
                        start = end
                    }

                    val printResult = if (ok) com.qring.printer.bt.PrintResult(true, "打印完成（$rows 行）") else com.qring.printer.bt.PrintResult(false, failMsg)

                    if (printResult.ok) {
                        try {
                            val payload = JSONObject().apply {
                                put("type", "math")
                                put("range", state.selectedRange.name)
                                put("op", state.selectedOp.name)
                                put("count", state.problemCount)
                                put("fontSize", state.fontSize.toDouble())
                                put("columns", state.columns)
                                put("showAnswer", state.showAnswer)
                                put("leftMargin", state.leftMargin.toDouble())
                            }.toString()
                            historyRepo.saveHistory(HIST_TYPE_MATH, thumbBitmap!!, payload)
                        } catch (e: Exception) { }
                    }

                    printResult
                }

                _uiState.value = _uiState.value.copy(
                    printing = false,
                    resultOk = result.ok,
                    resultMessage = result.message
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    printing = false,
                    resultOk = false,
                    resultMessage = "打印失败：${e.message}"
                )
            } finally {
                try { thumbBitmap?.recycle() } catch (e: Exception) { }
                if (_uiState.value.printing) {
                    _uiState.value = _uiState.value.copy(printing = false)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        generateJob?.cancel()
        previewJob?.cancel()
        _uiState.value.previewBitmap?.recycle()
    }
}

// ── UI ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MathPrintScreen(
    navController: androidx.navigation.NavController,
    viewModel: MathPrintViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QringPalette.pageBg)
    ) {
        TopAppBar(
            title = { Text("口算题打印") },
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
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.PAGE_PADDING.dp)
                .padding(top = 12.dp, bottom = 12.dp)
        ) {
            // 连接状态
            ConnectionBanner(printerStatus)

            Spacer(modifier = Modifier.height(12.dp))

            // 数字范围选择
            Text("数字范围", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.textPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MathRange.entries.forEach { range ->
                    ChipButton(
                        label = range.label,
                        selected = uiState.selectedRange == range,
                        modifier = Modifier.weight(1f)
                    ) { viewModel.setRange(range) }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 运算类型选择
            Text("运算类型", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.textPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MathOp.entries.forEach { op ->
                    ChipButton(
                        label = op.label,
                        selected = uiState.selectedOp == op,
                        modifier = Modifier.weight(1f)
                    ) { viewModel.setOp(op) }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 题目数量
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("题目数量", fontSize = 13.sp, color = QringPalette.textSecondary)
                        Text("${uiState.problemCount} 题", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.brand)
                    }
                    Slider(
                        value = uiState.problemCount.toFloat(),
                        onValueChange = { viewModel.setProblemCount(it.toInt()) },
                        valueRange = 10f..200f,
                        steps = 37
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("每行列数", fontSize = 13.sp, color = QringPalette.textSecondary)
                        Text("${uiState.columns} 列", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.brand)
                    }
                    Slider(
                        value = uiState.columns.toFloat(),
                        onValueChange = { viewModel.setColumns(it.toInt()) },
                        valueRange = 1f..3f,
                        steps = 1
                    )

                    Spacer(modifier = Modifier.height(8.dp))

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
                        valueRange = 14f..32f
                    )

                    Spacer(modifier = Modifier.height(8.dp))

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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.showAnswer,
                            onCheckedChange = { viewModel.toggleShowAnswer() }
                        )
                        Text("显示答案（红色）", fontSize = 13.sp, color = QringPalette.textPrimary)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 重新生成按钮
                    androidx.compose.material3.TextButton(
                        onClick = { viewModel.regenerate() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔄 重新生成题目", fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }

        // 底部操作栏
        BottomActionBar(
            printing = uiState.printing,
            canPrint = uiState.problems.isNotEmpty(),
            resultMessage = uiState.resultMessage,
            resultOk = uiState.resultOk,
            onPrint = viewModel::print
        )
    }

    PrintWarningDialog(onGoBack = { navController.popBackStack() })
}

@Composable
private fun ChipButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) QringPalette.brand else QringPalette.surfaceSunken)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else QringPalette.textPrimary
        )
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
