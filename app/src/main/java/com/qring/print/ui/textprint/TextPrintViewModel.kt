package com.qring.print.ui.textprint

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.print.bt.PrinterConnection
import com.qring.print.bt.PrintResult
import com.qring.print.data.HistoryPayloadHolder
import com.qring.print.data.HistoryRepository
import com.qring.print.model.ConnState
import com.qring.print.model.HIST_TYPE_TEXT
import com.qring.print.model.PrinterStatus
import com.qring.print.model.PrinterStatusRepository
import com.qring.print.protocol.TextRenderOptions
import com.qring.print.ui.common.FontList
import com.qring.print.protocol.bitmapToRaster
import com.qring.print.protocol.renderTextToPixelMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
                thickness = obj.optInt("thickness", 1)
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

    fun loadFonts() {
        val fonts = FontList.getSystemFonts(getApplication())
        _uiState.value = _uiState.value.copy(fontFamilies = fonts)
    }

    private fun currentFamily(): String {
        val idx = _uiState.value.fontFamilyIndex
        val families = _uiState.value.fontFamilies
        return if (idx in families.indices) families[idx] else "sans-serif"
    }

    private fun buildOptions(): TextRenderOptions {
        val state = _uiState.value
        return TextRenderOptions(
            fontFamily = currentFamily(),
            fontSize = state.fontSize,
            bold = state.bold,
            italic = state.italic,
            underline = state.underline,
            letterSpacing = state.letterSpacing,
            lineSpacing = state.lineSpacing,
            margin = state.pageMargin
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
                // 清空时旧位图已不再显示，可安全回收
                old.recycle()
            }
            return
        }
        if (state.printing) return
        viewModelScope.launch {
            try {
                val old = _uiState.value.previewBitmap
                val bitmap = withContext(Dispatchers.Default) {
                    renderTextToPixelMap(state.text, buildOptions())
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
                    val bitmap = renderTextToPixelMap(state.text, buildOptions())
                    val raster = bitmapToRaster(bitmap, 211) // THRESHOLD_TEXT = 212

                    // 生成缩略图用于历史记录
                    val thumbBitmap = Bitmap.createScaledBitmap(bitmap, 200, Math.round(200f * bitmap.height / bitmap.width), true)

                    val printResult = withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, state.thickness.takeIf { it > 0 })
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
