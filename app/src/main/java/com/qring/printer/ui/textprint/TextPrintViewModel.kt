package com.qring.printer.ui.textprint

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.bt.PrintResult
import com.qring.printer.data.HistoryPayloadHolder
import com.qring.printer.data.HistoryRepository
import com.qring.printer.model.ConnState
import com.qring.printer.model.HIST_TYPE_TEXT
import com.qring.printer.model.PrinterStatus
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.TextRenderOptions
import com.qring.printer.ui.common.FontList
import com.qring.printer.protocol.bitmapToRaster
import com.qring.printer.protocol.renderTextToPixelMap
import com.qring.printer.protocol.renderTextToPixelMapIn
import com.qring.printer.protocol.packBinaryToRaster
import com.qring.printer.protocol.bitmapToGray
import com.qring.printer.protocol.ditherToBinary
import com.qring.printer.protocol.DitherMode
import com.qring.printer.protocol.GrayImage
import com.qring.printer.protocol.TextAlignment as ProtoTextAlignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class TextAlignment(val label: String) {
    LEFT("左对齐"),
    CENTER("居中"),
    RIGHT("右对齐"),
    STRETCH("整行平铺")
}

data class TextPrintUiState(
    val text: String = "",
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false,
    val previewBitmap: Bitmap? = null,
    val showPreview: Boolean = false,
    // 排版参数
    val fontSize: Float = 24f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 6f,
    val pageMargin: Float = 8f,
    val fontFamilies: List<String> = listOf("sans-serif", "serif", "monospace"),
    val fontFamilyIndex: Int = 0,
    // 打印浓度 1~5，0 表示默认（不发浓度命令）
    val thickness: Int = 1,
    // 横排模式：true = 侧向打印
    val landscape: Boolean = false,
    // 横排目标打印宽度（点）：0 = 自适应原尺寸，>0 = 缩放到指定宽度（100~1200）
    val landscapeTargetWidth: Int = 0,
    // 横排模式下内容宽度是否超限（目标宽>384点，自动分块）
    val landscapeOverflow: Boolean = false,
    // 文字对齐方式
    val alignment: TextAlignment = TextAlignment.LEFT,
)

class TextPrintViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val historyRepo = HistoryRepository(application)

    private val _uiState = MutableStateFlow(TextPrintUiState())
    val uiState: StateFlow<TextPrintUiState> = _uiState.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    init {
        // 从历史记录重打时恢复参数
        restoreFromHistoryPayload()
    }

    private fun restoreFromHistoryPayload() {
        val (type, payload) = HistoryPayloadHolder.consumePayload() ?: return
        if (type != HIST_TYPE_TEXT) {
            // 不是自己的类型，放回
            HistoryPayloadHolder.setPayload(type, payload)
            return
        }
        try {
            val obj = JSONObject(payload)
            _uiState.value = _uiState.value.copy(
                text = obj.optString("text", ""),
                fontSize = obj.optDouble("fontSize", 24.0).toFloat(),
                bold = obj.optBoolean("bold", false),
                italic = obj.optBoolean("italic", false),
                underline = obj.optBoolean("underline", false),
                letterSpacing = obj.optDouble("letterSpacing", 0.0).toFloat(),
                lineSpacing = obj.optDouble("lineSpacing", 6.0).toFloat(),
                pageMargin = obj.optDouble("pageMargin", 8.0).toFloat(),
                fontFamilyIndex = obj.optInt("fontIndex", 0),
                thickness = obj.optInt("thickness", 1),
                landscape = obj.optBoolean("landscape", false),
                landscapeTargetWidth = obj.optInt("landscapeTargetWidth", 0).coerceIn(0, 1200)
            )
        } catch (e: Exception) { }
    }

    fun updateText(text: String) {
        _uiState.value = _uiState.value.copy(text = text)
    }

    fun updateFontSize(size: Float) {
        _uiState.value = _uiState.value.copy(fontSize = size)
    }

    fun toggleBold() {
        _uiState.value = _uiState.value.copy(bold = !_uiState.value.bold)
    }

    fun toggleItalic() {
        _uiState.value = _uiState.value.copy(italic = !_uiState.value.italic)
    }

    fun toggleUnderline() {
        _uiState.value = _uiState.value.copy(underline = !_uiState.value.underline)
    }

    fun updateLetterSpacing(spacing: Float) {
        _uiState.value = _uiState.value.copy(letterSpacing = spacing)
    }

    fun updateLineSpacing(spacing: Float) {
        _uiState.value = _uiState.value.copy(lineSpacing = spacing)
    }

    fun updatePageMargin(margin: Float) {
        _uiState.value = _uiState.value.copy(pageMargin = margin)
    }

    fun setFontFamilyIndex(index: Int) {
        val families = _uiState.value.fontFamilies
        if (index in families.indices) {
            _uiState.value = _uiState.value.copy(fontFamilyIndex = index)
        }
    }

    fun setThickness(thickness: Int?) {
        _uiState.value = _uiState.value.copy(thickness = thickness ?: 0)
    }

    fun setLandscape(landscape: Boolean) {
        _uiState.value = _uiState.value.copy(landscape = landscape)
        updatePreview()
    }

    /**
     * 设置横排目标打印宽度（点）。
     * 0 = 自适应原尺寸；100~1200 = 缩放到指定宽度（超 384 自动分段）。
     */
    fun setLandscapeTargetWidth(width: Int) {
        val w = if (width <= 0) 0 else width.coerceIn(100, 1200)
        _uiState.value = _uiState.value.copy(landscapeTargetWidth = w)
        updatePreview()
    }

    /** 横排实际打印宽度（点）：目标宽度 > 0 用之，否则为旋转后原宽（文本高度） */
    fun landscapePrintWidth(): Int {
        val st = _uiState.value
        val h = _uiState.value.previewBitmap?.height ?: 0
        return if (st.landscapeTargetWidth > 0) st.landscapeTargetWidth else h
    }

    /**
     * 渲染文本位图。
     * 竖排：固定 384 宽折行；横排：折行宽度 = 打印宽度（目标 > 0 时），
     * 返回 W×H（W=折行宽/打印宽，H=行数高度），字符不被拉伸。
     */
    private fun renderBitmap(): android.graphics.Bitmap {
        val st = _uiState.value
        if (!st.landscape || st.landscapeTargetWidth <= 0) {
            return renderTextToPixelMap(st.text, buildOptions())
        }
        return renderTextToPixelMapIn(st.text, buildOptions(), st.landscapeTargetWidth.toFloat())
    }

    fun setAlignment(alignment: TextAlignment) {
        _uiState.value = _uiState.value.copy(alignment = alignment)
        updatePreview()
    }

    fun loadFonts() {
        val fonts = FontList.getSystemFonts(getApplication())
        _uiState.value = _uiState.value.copy(fontFamilies = fonts)
    }

    /** 导入字体文件，返回导入后的族名 */
    fun importFont(uri: android.net.Uri): String? {
        val name = FontList.importFont(getApplication(), uri)
        if (name != null) {
            loadFonts()
            // 自动选中新导入的字体
            val idx = _uiState.value.fontFamilies.indexOf(name)
            if (idx >= 0) {
                _uiState.value = _uiState.value.copy(fontFamilyIndex = idx)
                updatePreview()
            }
        }
        return name
    }

    /** 删除已导入的字体 */
    fun deleteImportedFont(family: String) {
        FontList.deleteImportedFont(family)
        loadFonts()
        // 如果删除的是当前选中的字体，重置为默认
        val idx = _uiState.value.fontFamilyIndex
        if (idx >= _uiState.value.fontFamilies.size) {
            _uiState.value = _uiState.value.copy(fontFamilyIndex = 0)
            updatePreview()
        }
    }

    /** 判断字体是否为导入字体 */
    fun isImportedFont(family: String): Boolean = FontList.isImported(family)

    private fun currentFamily(): String {
        val idx = _uiState.value.fontFamilyIndex
        val families = _uiState.value.fontFamilies
        return if (idx in families.indices) families[idx] else "sans-serif"
    }

    private fun buildOptions(): TextRenderOptions {
        val state = _uiState.value
        val protoAlign = when (state.alignment) {
            TextAlignment.LEFT -> ProtoTextAlignment.LEFT
            TextAlignment.CENTER -> ProtoTextAlignment.CENTER
            TextAlignment.RIGHT -> ProtoTextAlignment.RIGHT
            TextAlignment.STRETCH -> ProtoTextAlignment.STRETCH
        }
        return TextRenderOptions(
            fontFamily = currentFamily(),
            fontSize = state.fontSize,
            bold = state.bold,
            italic = state.italic,
            underline = state.underline,
            letterSpacing = state.letterSpacing,
            lineSpacing = state.lineSpacing,
            margin = state.pageMargin,
            alignment = protoAlign
        )
    }

    fun renderPreview() {
        val state = _uiState.value
        if (state.text.isEmpty() || state.printing) return
        viewModelScope.launch {
            try {
                val old = _uiState.value.previewBitmap
                val bitmap = withContext(Dispatchers.Default) {
                    renderTextToPixelMap(state.text, buildOptions())
                }
                _uiState.value = _uiState.value.copy(
                    previewBitmap = bitmap,
                    showPreview = true
                )
                old?.let { if (it != bitmap) { delay(150); it.recycle() } }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    resultOk = false,
                    resultMessage = "预览生成失败"
                )
            }
        }
    }

    /** 自动预览：内容/参数变化时调用，不弹窗，直接更新底部常驻预览 */
    fun updatePreview() {
        val state = _uiState.value
        if (state.text.isEmpty()) {
            val old = _uiState.value.previewBitmap
            if (old != null) {
                _uiState.value = _uiState.value.copy(previewBitmap = null)
                old.recycle()
            }
            return
        }
        if (state.printing) return
        viewModelScope.launch {
            try {
                val old = _uiState.value.previewBitmap
                val bitmap = withContext(Dispatchers.Default) {
                    val bmp = renderBitmap()
                    // 横排：旋转 90° 后纸宽方向 = 行数高度 H（须 ≤ 384），纸长 = 打印宽度
                    val rowCount = bmp.height  // 渲染图高 = 行数高度（横排时）
                    val overflow = state.landscape && rowCount > com.qring.printer.protocol.WIDTH_DOTS
                    _uiState.value = _uiState.value.copy(landscapeOverflow = overflow)
                    // 横排预览：不旋转，文字正着显示（和自定义画布一样）
                    // 打印时才旋转数据
                    bmp
                }
                _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
                old?.let { if (it != bitmap) { delay(150); it.recycle() } }
            } catch (e: Exception) { }
        }
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
        if (state.text.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请输入要打印的内容"
            )
            return
        }

        _uiState.value = _uiState.value.copy(printing = true, resultMessage = "")

        viewModelScope.launch {
            try {
                // 打印前体检
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

                // 渲染 + 光栅化 + 打印
                val result = withContext(Dispatchers.Default) {
                    val bitmap = renderBitmap()
                    // 横排打印：文本按「打印宽度」折行渲染（W×H）→ 旋转 90° 得 H×W →
                    // 纸宽方向（行数 H）须 ≤ 384，纸长方向 = 打印宽度 W 沿长分块拼接。
                    val printAction: suspend () -> com.qring.printer.bt.PrintResult = if (state.landscape) {
                        val m = android.graphics.Matrix().apply { postRotate(90f) }
                        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                        val gray = bitmapToGray(rotated)
                        rotated.recycle()
                        val binary = ditherToBinary(gray, DitherMode.NONE, 211)
                        // 旋转后：宽 = 原行数高度 H（须 ≤ 384），高 = 原折行宽度 W（纸长）
                        val rowCount = gray.width   // 行数高度（纸宽方向）
                        val printLen = gray.height  // 打印宽度（纸长方向）
                        if (rowCount > com.qring.printer.protocol.WIDTH_DOTS) {
                            // 行数超过纸宽放不下（提示源自 updatePreview 的 overflow）
                            suspend { com.qring.printer.bt.PrintResult(false, "横排行数超过纸宽(384点)，请减小行数或字号") }
                        } else if (printLen <= com.qring.printer.protocol.WIDTH_DOTS) {
                            // 单块：纸宽 rowCount（右补到 384），纸长 printLen
                            val raster = packBinaryToRaster(binary, rowCount, printLen)
                            suspend { printerConnection.printRaster(raster, state.thickness.takeIf { it > 0 }) }
                        } else {
                            // 纸长超过 384：沿高方向切块（每块高 384，宽 rowCount 补到 384）
                            val chunks = mutableListOf<com.qring.printer.protocol.RasterData>()
                            var off = 0
                            while (off < printLen) {
                                val bh = minOf(com.qring.printer.protocol.WIDTH_DOTS, printLen - off)
                                val block = ByteArray(rowCount * bh)
                                for (y in 0 until bh) {
                                    System.arraycopy(binary, (off + y) * rowCount, block, y * rowCount, rowCount)
                                }
                                chunks.add(packBinaryToRaster(block, rowCount, bh))
                                off += com.qring.printer.protocol.WIDTH_DOTS
                            }
                            suspend { printerConnection.printRasterChunks(chunks, state.thickness.takeIf { it > 0 }) }
                        }
                    } else {
                        val raster = bitmapToRaster(bitmap, 211)
                        suspend { printerConnection.printRaster(raster, state.thickness.takeIf { it > 0 }) }
                    }

                    // 生成缩略图用于历史记录
                    val thumbBitmap = if (state.landscape) {
                        // 横排缩略图旋转 270°CW
                        val m = android.graphics.Matrix().apply { postRotate(270f) }
                        val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                        Bitmap.createScaledBitmap(rotated, 200, Math.round(200f * rotated.height / rotated.width), true).also {
                            if (rotated != bitmap) rotated.recycle()
                        }
                    } else {
                        Bitmap.createScaledBitmap(bitmap, 200, Math.round(200f * bitmap.height / bitmap.width), true)
                    }

                    val printResult = withContext(Dispatchers.IO) {
                        printAction()
                    }

                    // 打印成功后保存历史
                    if (printResult.ok) {
                        try {
                            val payload = JSONObject().apply {
                                put("text", state.text)
                                put("fontSize", state.fontSize.toDouble())
                                put("bold", state.bold)
                                put("italic", state.italic)
                                put("underline", state.underline)
                                put("letterSpacing", state.letterSpacing.toDouble())
                                put("lineSpacing", state.lineSpacing.toDouble())
                                put("pageMargin", state.pageMargin.toDouble())
                                put("fontIndex", state.fontFamilyIndex)
                                put("thickness", state.thickness)
                                put("landscape", state.landscape)
                                put("landscapeTargetWidth", state.landscapeTargetWidth)
                            }.toString()
                            historyRepo.saveHistory(HIST_TYPE_TEXT, thumbBitmap, payload)
                        } catch (e: Exception) { }
                    }

                    bitmap.recycle()
                    thumbBitmap.recycle()
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
            }
        }
    }

    fun clearResult() {
        _uiState.value = _uiState.value.copy(resultMessage = "", resultOk = false)
    }

    fun dismissPreview() {
        _uiState.value = _uiState.value.copy(showPreview = false)
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.previewBitmap?.recycle()
    }
}
