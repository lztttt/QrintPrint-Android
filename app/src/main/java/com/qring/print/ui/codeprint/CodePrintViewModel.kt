package com.qring.print.ui.codeprint

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.qring.print.bt.PrinterConnection
import com.qring.print.data.HistoryPayloadHolder
import com.qring.print.data.HistoryRepository
import com.qring.print.model.ConnState
import com.qring.print.model.HIST_TYPE_CODE
import com.qring.print.model.PrinterStatus
import com.qring.print.model.PrinterStatusRepository
import com.qring.print.protocol.RasterData
import com.qring.print.protocol.WIDTH_DOTS
import com.qring.print.protocol.binaryToPreviewBitmap
import com.qring.print.protocol.bitmapToRaster
import com.qring.print.protocol.ditherToBinary
import com.qring.print.protocol.GrayImage
import com.qring.print.protocol.DitherMode
import com.qring.print.protocol.packBinaryToRaster
import com.qring.print.protocol.scaleGrayNearest
import com.qring.print.protocol.squeezeRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class CodePrintUiState(
    val content: String = "",
    val codeTypeIndex: Int = 0, // 默认 QR Code
    val previewBitmap: Bitmap? = null,
    val showPreview: Boolean = false,
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false,
    val busy: Boolean = false,
)

class CodePrintViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val historyRepo = HistoryRepository(application)

    private val _uiState = MutableStateFlow(CodePrintUiState())
    val uiState: StateFlow<CodePrintUiState> = _uiState.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    init {
        restoreFromHistoryPayload()
    }

    private fun restoreFromHistoryPayload() {
        val (type, payload) = HistoryPayloadHolder.consumePayload() ?: return
        if (type != HIST_TYPE_CODE) {
            HistoryPayloadHolder.setPayload(type, payload)
            return
        }
        try {
            val obj = JSONObject(payload)
            val content = obj.optString("content", "")
            val codeTypeIndex = obj.optInt("codeTypeIndex", 0)
            _uiState.value = _uiState.value.copy(
                content = content,
                codeTypeIndex = codeTypeIndex
            )
            if (content.isNotEmpty()) {
                updatePreview()
            }
        } catch (e: Exception) { }
    }

    fun updateContent(content: String) {
        _uiState.value = _uiState.value.copy(content = content)
    }

    fun setCodeTypeIndex(index: Int) {
        _uiState.value = _uiState.value.copy(codeTypeIndex = index)
    }

    /** 码制对应的 ZXing 格式 */
    private fun formatFor(codeTypeIndex: Int): BarcodeFormat {
        val label = com.qring.print.model.CODE_TYPES.getOrNull(codeTypeIndex)?.label ?: "QR Code"
        return when (label) {
            "QR Code" -> BarcodeFormat.QR_CODE
            "Data Matrix" -> BarcodeFormat.DATA_MATRIX
            "Aztec" -> BarcodeFormat.AZTEC
            "PDF417" -> BarcodeFormat.PDF_417
            "Code 128" -> BarcodeFormat.CODE_128
            "Code 39" -> BarcodeFormat.CODE_39
            "Code 93" -> BarcodeFormat.CODE_93
            "EAN-13" -> BarcodeFormat.EAN_13
            "EAN-8" -> BarcodeFormat.EAN_8
            "UPC-A" -> BarcodeFormat.UPC_A
            "ITF" -> BarcodeFormat.ITF
            else -> BarcodeFormat.QR_CODE
        }
    }

    private fun isOneD(codeTypeIndex: Int): Boolean =
        com.qring.print.model.CODE_TYPES.getOrNull(codeTypeIndex)?.category == com.qring.print.model.CodeCategory.ONE_D

    /**
     * 把内容渲染成 384 点宽的条码灰度 + 二值，返回 (binary, gray)。内容为空返回 null。
     */
    private fun renderCode(state: CodePrintUiState): Pair<ByteArray, GrayImage>? {
        if (state.content.isEmpty()) return null
        val format = formatFor(state.codeTypeIndex)
        val size = 384
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix: BitMatrix = MultiFormatWriter().encode(
            state.content, format, size, size, hints
        )
        val grayData = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                grayData[y * size + x] = if (bitMatrix.get(x, y)) 0 else 255
            }
        }
        var gray = GrayImage(grayData, size, size)
        // 一维码压扁
        if (isOneD(state.codeTypeIndex)) {
            gray = squeezeRows(gray, 140)
        }
        // 缩放到目标尺寸
        val targetW = if (isOneD(state.codeTypeIndex)) 280 else 160
        val targetH = if (isOneD(state.codeTypeIndex)) 140 else 160
        val scaled = scaleGrayNearest(gray, targetW, targetH)
        // 纯阈值，不抖动
        val binary = ditherToBinary(scaled, DitherMode.NONE, 128)
        return Pair(binary, scaled)
    }

    /** 实时预览：内容/码制变化时调用 */
    fun updatePreview() {
        val state = _uiState.value
        if (state.content.isEmpty()) {
            val old = _uiState.value.previewBitmap
            if (old != null) {
                _uiState.value = _uiState.value.copy(previewBitmap = null)
                old.recycle()
            }
            return
        }
        viewModelScope.launch {
            try {
                val old = _uiState.value.previewBitmap
                val preview = withContext(Dispatchers.Default) {
                    val result = renderCode(_uiState.value) ?: return@withContext null
                    binaryToPreviewBitmap(result.first, result.second.width, result.second.height, false)
                } ?: return@launch
                _uiState.value = _uiState.value.copy(previewBitmap = preview)
                // 等当前帧画完旧位图再回收
                old?.let { if (it != preview) { delay(150); it.recycle() } }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    resultOk = false,
                    resultMessage = "条码生成失败：${e.message}"
                )
            }
        }
    }

    fun print() {
        val state = _uiState.value
        if (state.printing || state.busy) return
        if (printerStatus.value.connState != ConnState.CONNECTED) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请先在首页连接打印机"
            )
            return
        }
        if (state.content.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请输入条码内容"
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

                // 重新生成并打印
                val result = withContext(Dispatchers.Default) {
                    val r = renderCode(_uiState.value) ?: return@withContext null
                    val (binary, scaled) = r
                    val raster = packBinaryToRaster(binary, scaled.width, scaled.height)

                    // 生成缩略图
                    val fullBmp = binaryToPreviewBitmap(binary, scaled.width, scaled.height, false)
                    val thumbBmp = Bitmap.createScaledBitmap(fullBmp, 200, Math.round(200f * fullBmp.height / fullBmp.width), true)

                    val printResult = withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, 1)
                    }

                    // 打印成功后保存历史
                    if (printResult.ok) {
                        try {
                            val payload = org.json.JSONObject().apply {
                                put("content", _uiState.value.content)
                                put("codeTypeIndex", _uiState.value.codeTypeIndex)
                            }.toString()
                            historyRepo.saveHistory(HIST_TYPE_CODE, thumbBmp, payload)
                        } catch (e: Exception) { }
                    }

                    fullBmp.recycle()
                    printResult
                }

                _uiState.value = _uiState.value.copy(
                    printing = false,
                    resultOk = result?.ok ?: false,
                    resultMessage = result?.message ?: "请输入条码内容"
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

    fun dismissPreview() {
        _uiState.value = _uiState.value.copy(showPreview = false)
    }

    fun clearResult() {
        _uiState.value = _uiState.value.copy(resultMessage = "", resultOk = false)
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.previewBitmap?.recycle()
    }
}
