package com.qring.printer.ui.imageprint

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.data.HistoryPayloadHolder
import com.qring.printer.data.HistoryRepository
import com.qring.printer.model.ConnState
import com.qring.printer.model.HIST_TYPE_IMAGE
import com.qring.printer.model.PrinterStatus
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.DITHER_OPTIONS
import com.qring.printer.protocol.DitherMode
import com.qring.printer.protocol.GrayImage
import com.qring.printer.protocol.RasterData
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.bitmapToGray
import com.qring.printer.protocol.binaryToPreviewBitmap
import com.qring.printer.protocol.createBinaryCanvas
import com.qring.printer.protocol.decodeSourceToPrintWidth
import com.qring.printer.protocol.ditherToBinary
import com.qring.printer.protocol.packBinaryToRaster
import com.qring.printer.protocol.rotateBinary
import com.qring.printer.protocol.scaleBinaryToWidth
import com.qring.printer.protocol.flipBinaryHorizontal
import com.qring.printer.protocol.flipBinaryVertical
import com.qring.printer.protocol.invertBinary
import com.qring.printer.protocol.scaleGrayArea
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
    // 原始灰度图（未经调整），用于对比度/亮度/锐度重新计算
    val originalGray: GrayImage? = null,
    // 图像预调整：对比度 -100~100，亮度 -100~100，锐度 0~100
    val contrast: Int = 0,
    val brightness: Int = 0,
    val sharpness: Int = 0,
    // 旋转角度 0/90/180/270
    val rotation: Int = 0,
    // 水平翻转
    val flipH: Boolean = false,
    // 垂直翻转
    val flipV: Boolean = false,
    // 反色（黑变白、白变黑）
    val invert: Boolean = false,
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

    fun setContrast(value: Int) {
        _uiState.value = _uiState.value.copy(contrast = value)
        applyAdjustments()
    }

    fun setBrightness(value: Int) {
        _uiState.value = _uiState.value.copy(brightness = value)
        applyAdjustments()
    }

    fun setSharpness(value: Int) {
        _uiState.value = _uiState.value.copy(sharpness = value)
        applyAdjustments()
    }

    /**
     * 对比度/亮度/锐度变化后，从原始灰度图重新计算 sourceGray，再重新渲染预览。
     */
    private fun applyAdjustments() {
        val orig = _uiState.value.originalGray ?: return
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            try {
                val oldPreview = _uiState.value.previewBitmap
                val oldGray = _uiState.value.sourceGray
                val result = withContext(Dispatchers.Default) {
                    val adjusted = adjustGrayImage(orig, state.contrast, state.brightness, state.sharpness)
                    val binary = ditherToBinary(adjusted, state.ditherMode, state.threshold)
                    var w = adjusted.width
                    var h = adjusted.height
                    var bin = binary
                    if (state.rotation % 360 != 0) {
                        val (rot, nw, nh) = rotateBinary(bin, w, h, state.rotation)
                        bin = rot; w = nw; h = nh
                        // 旋转后宽度可能不再是 384，需要等比缩放回 384
                        if (w != WIDTH_DOTS) {
                            val (sb, sw, sh) = scaleBinaryToWidth(bin, w, h, WIDTH_DOTS)
                            bin = sb; w = sw; h = sh
                        }
                    }
                    if (state.flipH) bin = flipBinaryHorizontal(bin, w, h)
                    if (state.flipV) bin = flipBinaryVertical(bin, w, h)
                    if (state.invert) bin = invertBinary(bin, w, h)
                    val preview = binaryToPreviewBitmap(bin, w, h, false)
                    Pair(adjusted, preview)
                }
                _uiState.value = _uiState.value.copy(
                    sourceGray = result.first,
                    previewBitmap = result.second,
                    busy = false
                )
                oldGray?.let { /* GrayImage 不需要 recycle */ }
                oldPreview?.let { if (it != result.second) { delay(150); it.recycle() } }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(busy = false)
            }
        }
    }

    /**
     * 对灰度图应用对比度、亮度、锐度调整，返回新的 GrayImage。
     *
     * - contrast: -100~100，正值增加对比度，负值降低
     * - brightness: -100~100，正值变亮，负值变暗
     * - sharpness: 0~100，0 为不锐化，值越大锐化越强
     */
    private fun adjustGrayImage(src: GrayImage, contrast: Int, brightness: Int, sharpness: Int): GrayImage {
        val w = src.width
        val h = src.height
        val data = src.data.copyOf()

        // 1. 对比度 + 亮度
        if (contrast != 0 || brightness != 0) {
            // contrast factor: -100→0.0, 0→1.0, 100→∞ (clip at 3.0)
            val cFactor = if (contrast >= 0) {
                1f + contrast / 100f * 2f
            } else {
                1f + contrast / 100f * 0.9f
            }.coerceIn(0.1f, 3f)
            val bOffset = brightness * 2.55f
            for (i in data.indices) {
                val v = (data[i] - 128) * cFactor + 128 + bOffset
                data[i] = v.toInt().coerceIn(0, 255)
            }
        }

        // 2. 锐化（简单的 3x3 卷积）
        if (sharpness > 0) {
            val srcCopy = data.copyOf()
            val amount = sharpness / 100f
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val idx = y * w + x
                    val center = srcCopy[idx]
                    val up = srcCopy[idx - w]
                    val down = srcCopy[idx + w]
                    val left = srcCopy[idx - 1]
                    val right = srcCopy[idx + 1]
                    val blurred = (up + down + left + right) / 4f
                    val sharpened = center + (center - blurred) * amount
                    data[idx] = sharpened.toInt().coerceIn(0, 255)
                }
            }
        }

        return GrayImage(data, w, h)
    }

    fun setRotation(degrees: Int) {
        _uiState.value = _uiState.value.copy(rotation = ((degrees % 360) + 360) % 360)
        reRender()
    }

    fun toggleFlipH() {
        _uiState.value = _uiState.value.copy(flipH = !_uiState.value.flipH)
        reRender()
    }

    fun toggleFlipV() {
        _uiState.value = _uiState.value.copy(flipV = !_uiState.value.flipV)
        reRender()
    }

    fun toggleInvert() {
        _uiState.value = _uiState.value.copy(invert = !_uiState.value.invert)
        reRender()
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
                    originalGray = gray,
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
     * 切换抖动模式 / 阈值 / 旋转 / 翻转后重新生成实时预览。
     */
    fun reRender() {
        val gray = _uiState.value.sourceGray ?: return
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            try {
                val old = _uiState.value.previewBitmap
                val preview = withContext(Dispatchers.Default) {
                    var binary = ditherToBinary(gray, state.ditherMode, state.threshold)
                    var w = gray.width
                    var h = gray.height
                    if (state.rotation % 360 != 0) {
                        val (rot, nw, nh) = rotateBinary(binary, w, h, state.rotation)
                        binary = rot; w = nw; h = nh
                        // 旋转后宽度可能不再是 384，需要等比缩放回 384
                        if (w != WIDTH_DOTS) {
                            val (sb, sw, sh) = scaleBinaryToWidth(binary, w, h, WIDTH_DOTS)
                            binary = sb; w = sw; h = sh
                        }
                    }
                    if (state.flipH) {
                        binary = flipBinaryHorizontal(binary, w, h)
                    }
                    if (state.flipV) {
                        binary = flipBinaryVertical(binary, w, h)
                    }
                    if (state.invert) {
                        binary = invertBinary(binary, w, h)
                    }
                    binaryToPreviewBitmap(binary, w, h, false)
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
                val state = _uiState.value
                val result = withContext(Dispatchers.Default) {
                    var binary = ditherToBinary(gray, state.ditherMode, state.threshold)
                    var w = gray.width
                    var h = gray.height
                    if (state.rotation % 360 != 0) {
                        val (rot, nw, nh) = rotateBinary(binary, w, h, state.rotation)
                        binary = rot; w = nw; h = nh
                        // 旋转后宽度可能不再是 384，需要等比缩放回 384
                        if (w != WIDTH_DOTS) {
                            val (sb, sw, sh) = scaleBinaryToWidth(binary, w, h, WIDTH_DOTS)
                            binary = sb; w = sw; h = sh
                        }
                    }
                    if (state.flipH) {
                        binary = flipBinaryHorizontal(binary, w, h)
                    }
                    if (state.flipV) {
                        binary = flipBinaryVertical(binary, w, h)
                    }
                    if (state.invert) {
                        binary = invertBinary(binary, w, h)
                    }
                    val raster = packBinaryToRaster(binary, w, h)

                    // 生成缩略图
                    val fullBmp = binaryToPreviewBitmap(binary, w, h, false)
                    val thumbBmp = Bitmap.createScaledBitmap(fullBmp, 200, Math.round(200f * fullBmp.height / fullBmp.width), true)

                    val printResult = withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, _uiState.value.thickness)
                    }

                    // 打印成功后保存历史
                    if (printResult.ok) {
                        try {
                            // 将图片复制到内部存储，避免权限过期
                            val savedImagePath = if (_uiState.value.imageUri.startsWith("content://")) {
                                historyRepo.saveImageToInternalStorage(_uiState.value.imageUri)
                            } else {
                                null
                            }
                            
                            val payload = org.json.JSONObject().apply {
                                put("imageUri", savedImagePath ?: _uiState.value.imageUri)
                                put("ditherMode", _uiState.value.ditherMode.code)
                                put("threshold", _uiState.value.threshold)
                                put("rotation", _uiState.value.rotation)
                                put("flipH", _uiState.value.flipH)
                                put("flipV", _uiState.value.flipV)
                                put("invert", _uiState.value.invert)
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
