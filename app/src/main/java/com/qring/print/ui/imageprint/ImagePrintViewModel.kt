package com.qring.print.ui.imageprint

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.print.bt.PrinterConnection
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
import com.qring.print.protocol.bitmapToPreviewBitmap
import com.qring.print.protocol.createBinaryCanvas
import com.qring.print.protocol.decodeImageToPrintWidth
import com.qring.print.protocol.ditherToBinary
import com.qring.print.protocol.packBinaryToRaster
import com.qring.print.protocol.scaleGrayArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImagePrintUiState(
    val imageUri: String = "",
    val previewBitmap: Bitmap? = null,
    val showPreview: Boolean = false,
    val ditherMode: DitherMode = DitherMode.FLOYD_STEINBERG,
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

    fun setImageUri(uri: String) {
        _uiState.value = _uiState.value.copy(imageUri = uri)
    }

    fun setDitherMode(mode: DitherMode) {
        _uiState.value = _uiState.value.copy(ditherMode = mode)
    }

    fun setThickness(thickness: Int?) {
        _uiState.value = _uiState.value.copy(thickness = thickness)
    }

    /**
     * 解码图片并生成预览。
     * 解码期直接缩放到 384 点宽，然后跑抖动二值化生成预览位图。
     */
    fun decodeAndPreview() {
        val uri = _uiState.value.imageUri
        if (uri.isEmpty()) return

        _uiState.value = _uiState.value.copy(busy = true, busyHint = "正在解码图片…")

        viewModelScope.launch {
            try {
                val state = _uiState.value
                withContext(Dispatchers.Default) {
                    // 解码并缩放
                    val bitmap = decodeImageToPrintWidth(uri)
                    val gray = bitmapToGray(bitmap)
                    bitmap.recycle()

                    // 二值化
                    val binary = ditherToBinary(gray, state.ditherMode, 128) // THRESHOLD_IMAGE

                    // 生成预览位图
                    val preview = binaryToPreviewBitmap(binary, gray.width, gray.height, false)

                    _uiState.value = _uiState.value.copy(
                        previewBitmap = preview,
                        sourceGray = gray,
                        showPreview = true,
                        busy = false
                    )
                }
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
     * 切换抖动模式后重新生成预览。
     */
    fun reRenderWithDither(mode: DitherMode) {
        val gray = _uiState.value.sourceGray ?: return
        setDitherMode(mode)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            try {
                withContext(Dispatchers.Default) {
                    val binary = ditherToBinary(gray, mode, 128)
                    val preview = binaryToPreviewBitmap(binary, gray.width, gray.height, false)
                    val old = _uiState.value.previewBitmap
                    _uiState.value = _uiState.value.copy(
                        previewBitmap = preview,
                        busy = false
                    )
                    old?.recycle()
                }
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
                    val binary = ditherToBinary(gray, _uiState.value.ditherMode, 128)
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
