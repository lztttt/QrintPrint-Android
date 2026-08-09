package com.qring.print.ui.textprint

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.print.bt.PrinterConnection
import com.qring.print.bt.PrintResult
import com.qring.print.model.ConnState
import com.qring.print.model.PrinterStatus
import com.qring.print.model.PrinterStatusRepository
import com.qring.print.protocol.PrintResult as ProtocolPrintResult
import com.qring.print.protocol.RasterData
import com.qring.print.protocol.TextRenderOptions
import com.qring.print.protocol.bitmapToRaster
import com.qring.print.protocol.renderTextToPixelMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
)

class TextPrintViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()

    private val _uiState = MutableStateFlow(TextPrintUiState())
    val uiState: StateFlow<TextPrintUiState> = _uiState.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

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
                val bitmap = withContext(Dispatchers.Default) {
                    renderTextToPixelMap(state.text, buildOptions())
                }
                _uiState.value = _uiState.value.copy(
                    previewBitmap = bitmap,
                    showPreview = true
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    resultOk = false,
                    resultMessage = "预览生成失败"
                )
            }
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
                    bitmap.recycle()

                    withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, 1)
                    }
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
