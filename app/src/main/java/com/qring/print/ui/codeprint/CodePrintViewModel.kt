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
import com.qring.print.model.ConnState
import com.qring.print.model.PrinterStatus
import com.qring.print.model.PrinterStatusRepository
import com.qring.print.protocol.RasterData
import com.qring.print.protocol.WIDTH_DOTS
import com.qring.print.protocol.bitmapToPreviewBitmap
import com.qring.print.protocol.bitmapToRaster
import com.qring.print.protocol.ditherToBinary
import com.qring.print.protocol.GrayImage
import com.qring.print.protocol.DitherMode
import com.qring.print.protocol.packBinaryToRaster
import com.qring.print.protocol.scaleGrayNearest
import com.qring.print.protocol.squeezeRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val _uiState = MutableStateFlow(CodePrintUiState())
    val uiState: StateFlow<CodePrintUiState> = _uiState.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    fun updateContent(content: String) {
        _uiState.value = _uiState.value.copy(content = content)
    }

    fun setCodeTypeIndex(index: Int) {
        _uiState.value = _uiState.value.copy(codeTypeIndex = index)
    }

    /**
     * 生成条码并预览。
     * 使用 ZXing MultiFormatWriter 生成条码位图，然后缩放、二值化、生成预览。
     */
    fun generateAndPreview() {
        val state = _uiState.value
        if (state.content.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请输入条码内容"
            )
            return
        }

        _uiState.value = _uiState.value.copy(busy = true, resultMessage = "")

        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val codeType = com.qring.print.model.CODE_TYPES[state.codeTypeIndex]
                    val format = when (codeType.label) {
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

                    // 生成条码位图（384x384）
                    val size = 384
                    val hints = mapOf(
                        EncodeHintType.MARGIN to 2,
                        EncodeHintType.CHARACTER_SET to "UTF-8"
                    )
                    val bitMatrix: BitMatrix = MultiFormatWriter().encode(
                        state.content, format, size, size, hints
                    )

                    // BitMatrix -> GrayImage
                    val grayData = IntArray(size * size)
                    for (y in 0 until size) {
                        for (x in 0 until size) {
                            grayData[y * size + x] = if (bitMatrix.get(x, y)) 0 else 255
                        }
                    }
                    var gray = GrayImage(grayData, size, size)

                    // 一维码压扁
                    if (codeType.category == com.qring.print.model.CodeCategory.ONE_D) {
                        gray = squeezeRows(gray, 140)
                    }

                    // 缩放到目标尺寸（二维码 160px，一维码 280x140）
                    val targetW = if (codeType.category == com.qring.print.model.CodeCategory.ONE_D) 280 else 160
                    val targetH = if (codeType.category == com.qring.print.model.CodeCategory.ONE_D) 140 else 160
                    val scaled = scaleGrayNearest(gray, targetW, targetH)

                    // 二值化（纯阈值，不抖动）
                    val binary = ditherToBinary(scaled, DitherMode.NONE, 128)

                    // 生成预览位图
                    val preview = binaryToPreviewBitmap(binary, scaled.width, scaled.height, false)

                    _uiState.value = _uiState.value.copy(
                        previewBitmap = preview,
                        showPreview = true,
                        busy = false
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    busy = false,
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
        if (state.previewBitmap == null) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请先生成条码"
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

                // 重新生成并打印（预览位图不适合直接打印，需要重新走管线）
                val result = withContext(Dispatchers.Default) {
                    val codeType = com.qring.print.model.CODE_TYPES[state.codeTypeIndex]
                    val format = when (codeType.label) {
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

                    if (codeType.category == com.qring.print.model.CodeCategory.ONE_D) {
                        gray = squeezeRows(gray, 140)
                    }

                    val targetW = if (codeType.category == com.qring.print.model.CodeCategory.ONE_D) 280 else 160
                    val targetH = if (codeType.category == com.qring.print.model.CodeCategory.ONE_D) 140 else 160
                    val scaled = scaleGrayNearest(gray, targetW, targetH)
                    val binary = ditherToBinary(scaled, DitherMode.NONE, 128)
                    val raster = packBinaryToRaster(binary, scaled.width, scaled.height)

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

    fun dismissPreview() {
        val old = _uiState.value.previewBitmap
        _uiState.value = _uiState.value.copy(showPreview = false, previewBitmap = null)
        old?.recycle()
    }

    fun clearResult() {
        _uiState.value = _uiState.value.copy(resultMessage = "", resultOk = false)
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.previewBitmap?.recycle()
    }
}
