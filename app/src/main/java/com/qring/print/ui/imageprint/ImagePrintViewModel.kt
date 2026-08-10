package com.qring.print.ui.imageprint

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.print.bt.PrinterConnection
import com.qring.print.data.HistoryPayloadHolder
import com.qring.print.data.HistoryRepository
import com.qring.print.model.ConnState
import com.qring.print.model.HIST_TYPE_IMAGE
import com.qring.print.model.PrinterStatus
import com.qring.print.model.PrinterStatusRepository
import com.qring.print.protocol.DITHER_OPTIONS
import com.qring.print.protocol.DitherMode
import com.qring.print.protocol.GrayImage
import com.qring.print.protocol.RasterData
import com.qring.print.protocol.bitmapToGray
import com.qring.print.protocol.binaryToPreviewBitmap
import com.qring.print.protocol.createBinaryCanvas
import com.qring.print.protocol.decodeSourceToPrintWidth
import com.qring.print.protocol.ditherToBinary
import com.qring.print.protocol.packBinaryToRaster
import com.qring.print.protocol.scaleGrayArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ImagePrintUiState(
    val imageUri: String = "",
    val previewBitmap: Bitmap? = null,
    val showPreview: Boolean = false,
    val ditherMode: DitherMode = DitherMode.FLOYD_STEINBERG,
    // 量化阈值 0~255，影响所有抖动算法（默认 128）
    val threshold: Int = 128,
    val thickness: Int? = null,
    val busy: Boolean = false,
    val busyHint: String = "",
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false,
    val sourceGray: GrayImage? = null,
)

class ImagePrintViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val app = application
    private val historyRepo = HistoryRepository(application)

    private val _uiState = MutableStateFlow(ImagePrintUiState())
    val uiState: StateFlow<ImagePrintUiState> = _uiState.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    init {
        restoreFromHistoryPayload()
    }

    private fun restoreFromHistoryPayload() {
        val (type, payload) = HistoryPayloadHolder.consumePayload() ?: return
        if (type != HIST_TYPE_IMAGE) {
            HistoryPayloadHolder.setPayload(type, payload)
            return
        }
        try {
            val obj = JSONObject(payload)
            val uri = obj.optString("imageUri", "")
            val ditherCode = obj.optInt("ditherMode", 1)
            _uiState.value = _uiState.value.copy(
                imageUri = uri,
                ditherMode = DitherMode.entries.getOrElse(ditherCode) { DitherMode.FLOYD_STEINBERG }
            )
            if (uri.isNotEmpty()) {
                decodeAndPreview()
            }
        } catch (e: Exception) { }
    }

    fun setImageUri(uri: String) {
        _uiState.value = _uiState.value.copy(imageUri = uri)
    }

    fun setDitherMode(mode: DitherMode) {
        _uiState.value = _uiState.value.copy(ditherMode = mode)
    }

    fun setThreshold(threshold: Int) {
        _uiState.value = _uiState.value.copy(threshold = threshold)
    }

    fun setThickness(thickness: Int?) {
        _uiState.value = _uiState.value.copy(thickness = thickness)
    }

    /**
     * 解码图片并生成灰度缓存 + 实时预览。
     */
    fun decodeAndPreview() {
        val uri = _uiState.value.imageUri
        if (uri.isEmpty()) return

        _uiState.value = _uiState.value.copy(busy = true, busyHint = "正在解码图片…")

        viewModelScope.launch {
            try {
                val old = _uiState.value.previewBitmap
                val result = withContext(Dispatchers.Default) {
                    // 解码并缩放（content:// 与文件路径都能处理）
                    val bitmap = decodeSourceToPrintWidth(app, uri)
                    val gray = bitmapToGray(bitmap)
                    bitmap.recycle()

                    val binary = ditherToBinary(gray, _uiState.value.ditherMode, _uiState.value.threshold)
                    val preview = binaryToPreviewBitmap(binary, gray.width, gray.height, false)
                    Pair(preview, gray)
                }
                val (preview, gray) = result
                _uiState.value = _uiState.value.copy(
                    previewBitmap = preview,
                    sourceGray = gray,
                    showPreview = true,
                    busy = false
                )
                // 等当前帧画完旧位图再回收，避免画已回收位图崩溃
                old?.let { if (it != preview) { delay(150); it.recycle() } }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    resultOk = false,
                    resultMessage = "图片解码失败：${e.message}"
                )
            }
        }
    }

    /**
     * 切换抖动模式 / 阈值后重新生成实时预览。
     */
    fun reRender() {
        val gray = _uiState.value.sourceGray ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            try {
                val old = _uiState.value.previewBitmap
                val preview = withContext(Dispatchers.Default) {
                    val binary = ditherToBinary(gray, _uiState.value.ditherMode, _uiState.value.threshold)
                    binaryToPreviewBitmap(binary, gray.width, gray.height, false)
                }
                _uiState.value = _uiState.value.copy(
                    previewBitmap = preview,
                    showPreview = true,
                    busy = false
                )
                // 等当前帧画完旧位图再回收
                old?.let { if (it != preview) { delay(150); it.recycle() } }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(busy = false)
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
        if (state.sourceGray == null) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请先选择一张图片"
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

                // 光栅化 + 打印
                val gray = _uiState.value.sourceGray!!
                val result = withContext(Dispatchers.Default) {
                    val binary = ditherToBinary(gray, _uiState.value.ditherMode, _uiState.value.threshold)
                    val raster = packBinaryToRaster(binary, gray.width, gray.height)

                    // 生成缩略图
                    val fullBmp = binaryToPreviewBitmap(binary, gray.width, gray.height, false)
                    val thumbBmp = Bitmap.createScaledBitmap(fullBmp, 200, Math.round(200f * fullBmp.height / fullBmp.width), true)

                    val printResult = withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, _uiState.value.thickness)
                    }

                    // 打印成功后保存历史
                    if (printResult.ok) {
                        try {
                            val payload = org.json.JSONObject().apply {
                                put("imageUri", _uiState.value.imageUri)
                                put("ditherMode", _uiState.value.ditherMode.code)
                                put("threshold", _uiState.value.threshold)
                            }.toString()
                            historyRepo.saveHistory(HIST_TYPE_IMAGE, thumbBmp, payload)
                        } catch (e: Exception) { }
                    }

                    fullBmp.recycle()
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
