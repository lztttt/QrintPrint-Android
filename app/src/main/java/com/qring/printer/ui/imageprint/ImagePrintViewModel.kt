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
import com.qring.printer.protocol.DitherMode
import com.qring.printer.protocol.GrayImage
import com.qring.printer.protocol.ImagePrintOptions
import com.qring.printer.protocol.adjustGrayImage
import com.qring.printer.protocol.bitmapToGray
import com.qring.printer.protocol.binaryToPreviewBitmap
import com.qring.printer.protocol.decodeSourceToPrintWidth
import com.qring.printer.protocol.packBinaryToRaster
import com.qring.printer.protocol.transformToBinary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

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
    // 原始图片尺寸（旋转前，用于自动识别横版）
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    // 横版推荐弹窗
    val showLandscapeSuggestion: Boolean = false,
    val landscapeSuggestionText: String = "",
    val landscapeSuggestedRotation: Int = 0,
)

class ImagePrintViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val app = application
    private val historyRepo = HistoryRepository(application)

    /** 调整类滑杆（对比度/亮度/锐度）的防抖任务：拖动时只执行最后一次 */
    private var adjustDebounce: Job? = null

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
            val restoredThickness = obj.optInt("thickness", 0).takeIf { it > 0 }
            _uiState.value = _uiState.value.copy(
                imageUri = uri,
                ditherMode = DitherMode.entries.getOrElse(ditherCode) { DitherMode.FLOYD_STEINBERG },
                threshold = obj.optInt("threshold", 128),
                rotation = obj.optInt("rotation", 0),
                flipH = obj.optBoolean("flipH", false),
                flipV = obj.optBoolean("flipV", false),
                invert = obj.optBoolean("invert", false),
                contrast = obj.optInt("contrast", 0),
                brightness = obj.optInt("brightness", 0),
                sharpness = obj.optInt("sharpness", 0),
                thickness = restoredThickness
            )
            if (uri.isNotEmpty()) {
                // 历史重打不弹横版推荐
                decodeAndPreview(suggestLandscape = false)
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
        scheduleAdjustments()
    }

    fun setBrightness(value: Int) {
        _uiState.value = _uiState.value.copy(brightness = value)
        scheduleAdjustments()
    }

    fun setSharpness(value: Int) {
        _uiState.value = _uiState.value.copy(sharpness = value)
        scheduleAdjustments()
    }

    /** 对比度/亮度/锐度滑杆防抖：只对最后一次变化执行全量重算 */
    private fun scheduleAdjustments() {
        adjustDebounce?.cancel()
        adjustDebounce = viewModelScope.launch {
            delay(250)
            applyAdjustments()
        }
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
                val result = withContext(Dispatchers.Default) {
                    val adjusted = adjustGrayImage(orig, state.contrast, state.brightness, state.sharpness)
                    val (binary, w, h) = transformToBinary(adjusted, buildOptions(state))
                    val preview = binaryToPreviewBitmap(binary, w, h, false)
                    Pair(adjusted, preview)
                }
                _uiState.value = _uiState.value.copy(
                    sourceGray = result.first,
                    previewBitmap = result.second,
                    busy = false
                )
                oldPreview?.let { if (it != result.second) { delay(150); it.recycle() } }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(busy = false)
            }
        }
    }

    /** 从当前状态构造共享打印选项（图片/PDF/批量打印共用同一变换链） */
    private fun buildOptions(state: ImagePrintUiState): ImagePrintOptions {
        return ImagePrintOptions(
            ditherMode = state.ditherMode,
            threshold = state.threshold,
            rotation = state.rotation,
            flipH = state.flipH,
            flipV = state.flipV,
            invert = state.invert,
            contrast = state.contrast,
            brightness = state.brightness,
            sharpness = state.sharpness
        )
    }

    fun setRotation(degrees: Int) {
        val norm = ((degrees % 360) + 360) % 360
        _uiState.value = _uiState.value.copy(rotation = norm)
        maybeSuggestLandscape()
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
     * 横版图片（宽>高）且尚未旋转成竖版长条（rotation 为 0/180）时，
     * 提示旋转 90° 打印：长边沿出纸方向，打印分辨率/尺寸最佳。
     */
    private fun maybeSuggestLandscape() {
        val st = _uiState.value
        if (st.sourceWidth <= 0 || st.sourceHeight <= 0) return
        if (st.sourceWidth <= st.sourceHeight) return // 非横版不提示
        if (st.rotation % 180 != 0) return            // 已旋转成竖版长条（90/270），已是最佳分辨率
        val ratio = ratioText(st.sourceWidth, st.sourceHeight)
        val directH = Math.round(st.sourceHeight.toFloat() * 384f / st.sourceWidth)
        val rotatedH = Math.round(st.sourceWidth.toFloat() * 384f / st.sourceHeight)
        _uiState.value = _uiState.value.copy(
            showLandscapeSuggestion = true,
            landscapeSuggestedRotation = 90,
            landscapeSuggestionText = "检测到横版图片（宽:高 ≈ $ratio）。\n" +
                "直接打印是矮横条（384×$directH 点），细节大量丢失。\n\n" +
                "建议旋转 90° 打印：长边沿出纸方向（384×$rotatedH 点），分辨率最佳，内容更清晰。"
        )
    }

    /** 关闭横版推荐弹窗 */
    fun dismissLandscapeSuggestion() {
        _uiState.value = _uiState.value.copy(showLandscapeSuggestion = false)
    }

    /** 应用横版推荐（当前设计推荐 0°，即恢复横版方向） */
    fun applyLandscapeSuggestion() {
        val wasShowing = _uiState.value.showLandscapeSuggestion
        val target = _uiState.value.landscapeSuggestedRotation
        _uiState.value = _uiState.value.copy(showLandscapeSuggestion = false)
        if (wasShowing && target != _uiState.value.rotation) {
            setRotation(target)
        }
    }

    /** 宽:高 简化比例文本，如 16:9 */
    private fun ratioText(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return "--"
        var a = w
        var b = h
        while (b != 0) {
            val t = a % b
            a = b
            b = t
        }
        val g = a.coerceAtLeast(1)
        return "${w / g}:${h / g}"
    }

    /**
     * 解码图片并生成灰度缓存 + 实时预览。
     * @param suggestLandscape 是否在检测到横版图片时弹出横版推荐（历史重打时传 false）
     */
    fun decodeAndPreview(suggestLandscape: Boolean = true) {
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

                    val state = _uiState.value
                    val (binary, w, h) = transformToBinary(gray, buildOptions(state))
                    val preview = binaryToPreviewBitmap(binary, w, h, false)
                    Pair(preview, gray)
                }
                val (preview, gray) = result
                _uiState.value = _uiState.value.copy(
                    previewBitmap = preview,
                    sourceGray = gray,
                    originalGray = gray,
                    sourceWidth = gray.width,
                    sourceHeight = gray.height,
                    showPreview = true,
                    busy = false
                )
                // 等当前帧画完旧位图再回收，避免画已回收位图崩溃
                old?.let { if (it != preview) { delay(150); it.recycle() } }

                // 自动识别横版图片（宽>高），推荐旋转 90° 打印（长边沿出纸方向，分辨率最佳）
                if (suggestLandscape && gray.width > gray.height) {
                    val directH = Math.round(gray.height.toFloat() * 384f / gray.width)
                    val rotatedH = Math.round(gray.width.toFloat() * 384f / gray.height)
                    _uiState.value = _uiState.value.copy(
                        showLandscapeSuggestion = true,
                        landscapeSuggestedRotation = 90,
                        landscapeSuggestionText = "检测到横版图片（宽:高 ≈ ${ratioText(gray.width, gray.height)}）。\n" +
                            "直接打印是矮横条（384×$directH 点），细节大量丢失。\n\n" +
                            "建议旋转 90° 打印：长边沿出纸方向（384×$rotatedH 点），分辨率最佳，内容更清晰。"
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
                    val (binary, w, h) = transformToBinary(gray, buildOptions(state))
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
                    val (binary, w, h) = transformToBinary(gray, buildOptions(state))
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
                                put("contrast", _uiState.value.contrast)
                                put("brightness", _uiState.value.brightness)
                                put("sharpness", _uiState.value.sharpness)
                                put("thickness", _uiState.value.thickness ?: 0)
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
