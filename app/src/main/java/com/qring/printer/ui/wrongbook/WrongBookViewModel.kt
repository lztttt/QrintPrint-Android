package com.qring.printer.ui.wrongbook

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.data.HistoryRepository
import com.qring.printer.data.HistoryPayloadHolder
import com.qring.printer.model.ConnState
import com.qring.printer.model.HIST_TYPE_WRONGBOOK
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.bitmapToGrayRaw
import com.qring.printer.protocol.binaryToPreviewBitmap
import com.qring.printer.protocol.DocumentEnhancer
import com.qring.printer.protocol.GrayImage
import com.qring.printer.protocol.packBinaryToRaster

import com.qring.printer.protocol.flipBinaryHorizontal
import com.qring.printer.protocol.invertBinary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

enum class WrongBookStep {
    SELECT, CROP, ENHANCE
}

data class WrongBookState(
    val step: WrongBookStep = WrongBookStep.SELECT,
    val originalBitmap: Bitmap? = null,
    val croppedBitmap: Bitmap? = null,
    val enhancedBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    /** Sauvola 窗口（15~35 奇数，越大越平滑） */
    val sauvolaWindow: Int = 15,
    /** Sauvola k 值（0.10~0.40，越大越敏感，笔画越粗） */
    val sauvolaK: Float = 0.2f,
    /** 二值化方式：0 = Sauvola（默认），1 = Wolf-Jolion，2 = Bradley */
    val binarizeMode: Int = 0,
    /** 旋转角度 0/90/180/270 */
    val rotation: Int = 0,
    /** 水平翻转 */
    val flipH: Boolean = false,
    /** 反色 */
    val invert: Boolean = false,
    val processing: Boolean = false,
    val processingHint: String = "",
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false,
    val allTags: List<String> = emptyList(),
    val selectedTags: Set<String> = emptySet(),
    val newTagInput: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false
)

class WrongBookViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val historyRepo = HistoryRepository(application)
    private val _state = MutableStateFlow(WrongBookState())
    val state: StateFlow<WrongBookState> = _state.asStateFlow()

    val printerStatus = PrinterStatusRepository.state

    init { loadTags() }

    /**
     * 从历史记录加载错题（点击历史记录跳转时调用）。
     * payload 中包含 tags/rotation/flipH/invert。
     * 缩略图即为增强后的二值图。
     */
    fun loadFromHistory(thumbnailPath: String, payload: String) {
        viewModelScope.launch {
            try {
                val json = JSONObject(payload)
                val tagsStr = json.optString("tags", "")
                val tags = if (tagsStr.isNotEmpty()) tagsStr.split(",").toSet() else emptySet()
                val rotation = json.optInt("rotation", 0)
                val flipH = json.optBoolean("flipH", false)
                val invert = json.optBoolean("invert", false)
                val sauvolaWindow = json.optInt("sauvolaWindow", 15)
                val sauvolaK = json.optDouble("sauvolaK", 0.2).toFloat()
                val binarizeMode = json.optInt("binarizeMode", 0)
                val fullImagePath = json.optString("fullImagePath", "")

                // 优先加载全分辨率图，没有则用缩略图
                val imgPath = if (fullImagePath.isNotEmpty() && java.io.File(fullImagePath).exists()) fullImagePath else thumbnailPath
                val bmp = withContext(Dispatchers.IO) {
                    android.graphics.BitmapFactory.decodeFile(imgPath)
                } ?: return@launch

                _state.value.originalBitmap?.recycle()
                _state.value.croppedBitmap?.recycle()
                _state.value.enhancedBitmap?.recycle()
                _state.value.previewBitmap?.recycle()

                // 如果加载的是全分辨率图（384宽），直接使用；否则缩放到打印宽度
                val scaled = if (bmp.width == WIDTH_DOTS) bmp else withContext(Dispatchers.Default) {
                    Bitmap.createScaledBitmap(
                        bmp, WIDTH_DOTS,
                        (bmp.height.toFloat() / bmp.width * WIDTH_DOTS).toInt(), true
                    )
                }
                if (scaled != bmp) bmp.recycle()

                val preview = withContext(Dispatchers.Default) {
                    val st = WrongBookState(
                        sauvolaWindow = sauvolaWindow,
                        sauvolaK = sauvolaK,
                        binarizeMode = binarizeMode,
                        rotation = rotation,
                        flipH = flipH,
                        invert = invert
                    )
                    val (binary, w, h) = processToPrint(scaled, st)
                    binaryToPreviewBitmap(binary, w, h, false)
                }

                _state.value = _state.value.copy(
                    enhancedBitmap = scaled,
                    previewBitmap = preview,
                    step = WrongBookStep.ENHANCE,
                    sauvolaWindow = sauvolaWindow,
                    sauvolaK = sauvolaK,
                    binarizeMode = binarizeMode,
                    rotation = rotation,
                    flipH = flipH,
                    invert = invert,
                    selectedTags = tags,
                    resultMessage = ""
                )
            } catch (e: Exception) {
                Timber.e(e, "loadFromHistory failed")
            }
        }
    }

    private fun loadTags() {
        val prefs = getApplication<Application>().getSharedPreferences("wrongbook_tags", android.content.Context.MODE_PRIVATE)
        val tags = prefs.getStringSet("tags", emptySet())?.toList()?.sorted() ?: emptyList()
        _state.value = _state.value.copy(allTags = tags)
    }

    private fun saveTag(tag: String) {
        val prefs = getApplication<Application>().getSharedPreferences("wrongbook_tags", android.content.Context.MODE_PRIVATE)
        val current = prefs.getStringSet("tags", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(tag)
        prefs.edit().putStringSet("tags", current).apply()
        loadTags()
    }

    fun addTag(tag: String) {
        val t = tag.trim()
        if (t.isNotEmpty()) {
            saveTag(t)
            _state.value = _state.value.copy(
                selectedTags = _state.value.selectedTags + t,
                newTagInput = ""
            )
        }
    }

    fun toggleTag(tag: String) {
        _state.value = _state.value.copy(
            selectedTags = if (_state.value.selectedTags.contains(tag))
                _state.value.selectedTags - tag
            else
                _state.value.selectedTags + tag
        )
    }

    fun setNewTagInput(v: String) { _state.value = _state.value.copy(newTagInput = v) }

    fun setOriginalBitmap(bitmap: Bitmap) {
        _state.value.originalBitmap?.recycle()
        _state.value.croppedBitmap?.recycle()
        _state.value.enhancedBitmap?.recycle()
        _state.value.previewBitmap?.recycle()
        _state.value = _state.value.copy(
            originalBitmap = bitmap,
            croppedBitmap = null, enhancedBitmap = null, previewBitmap = null,
            rotation = 0, flipH = false, invert = false,
            step = WrongBookStep.CROP, resultMessage = "", saved = false
        )
    }

    /** 设置 Sauvola 窗口（15~35，取奇数），实时重排预览 */
    fun setSauvolaWindow(window: Int) {
        val w = window.coerceIn(15, 35)
        val odd = if (w % 2 == 0) w + 1 else w
        _state.value = _state.value.copy(sauvolaWindow = odd)
        reRender()
    }

    /** 设置 Sauvola k 值（0.10~0.40），实时重排预览 */
    fun setSauvolaK(k: Float) {
        _state.value = _state.value.copy(sauvolaK = k.coerceIn(0.10f, 0.40f))
        reRender()
    }

    /** 切换二值化方式（0=Sauvola，1=Wolf，2=Bradley） */
    fun setBinarizeMode(mode: Int) {
        if (mode !in 0..2) return
        if (_state.value.binarizeMode == mode) return
        _state.value = _state.value.copy(binarizeMode = mode)
        reRender()
    }

    fun setRotation(degrees: Int) {
        _state.value = _state.value.copy(rotation = ((degrees % 360) + 360) % 360)
        reRender()
    }

    fun toggleFlipH() {
        _state.value = _state.value.copy(flipH = !_state.value.flipH)
        reRender()
    }

    fun toggleInvert() {
        _state.value = _state.value.copy(invert = !_state.value.invert)
        reRender()
    }

    fun setCroppedBitmap(bitmap: Bitmap) {
        _state.value.croppedBitmap?.recycle()
        _state.value.enhancedBitmap?.recycle()
        _state.value.previewBitmap?.recycle()
        _state.value = _state.value.copy(
            croppedBitmap = bitmap, enhancedBitmap = null, previewBitmap = null,
            step = WrongBookStep.ENHANCE, resultMessage = ""
        )
        enhance()
    }

    fun backToSelect() {
        _state.value.originalBitmap?.recycle()
        _state.value.croppedBitmap?.recycle()
        _state.value.enhancedBitmap?.recycle()
        _state.value.previewBitmap?.recycle()
        _state.value = _state.value.copy(
            step = WrongBookStep.SELECT,
            originalBitmap = null, croppedBitmap = null, enhancedBitmap = null, previewBitmap = null,
            resultMessage = ""
        )
    }

    fun backToCrop() { _state.value = _state.value.copy(step = WrongBookStep.CROP) }

    /**
     * 文档增强（本地高分辨率管线）：
     * 源分辨率灰度归一化（光照补偿）→ 缩放后三种自适应二值化（Sauvola/Wolf/Bradley）。
     * 云端增强 API 待接入（选型见产品调研）。
     */
    fun enhance() {
        val cropped = _state.value.croppedBitmap ?: return
        _state.value = _state.value.copy(processing = true, processingHint = "正在增强文档...")

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    // 高分辨率灰度归一化，最后在 384 上按所选方式二值化
                    val enhanced = DocumentEnhancer.enhanceHighResGray(cropped)
                    val (binary, w, h) = processToPrint(enhanced, _state.value)
                    val preview = binaryToPreviewBitmap(binary, w, h, false)
                    Pair(enhanced, preview)
                }
                _state.value.enhancedBitmap?.recycle()
                _state.value.previewBitmap?.recycle()
                _state.value = _state.value.copy(
                    enhancedBitmap = result.first,
                    previewBitmap = result.second,
                    processing = false, step = WrongBookStep.ENHANCE
                )
            } catch (e: Exception) {
                Timber.e(e, "enhance failed")
                _state.value = _state.value.copy(processing = false, resultMessage = "增强失败：${e.message}", resultOk = false)
            }
        }
    }

    /**
     * 统一后处理：
     * Bitmap 域旋转（90° 无损）→ 灰度域缩放到 384（保留渐变）→
     * 按算法二值化（算法1：384 上 Sauvola；算法2：阈值 128）→ 翻转/反色 → 连通域去噪。
     *
     * 关键：**不做形态学闭运算** —— 闭运算会填掉密集小字的笔画间隙，导致文字糊成一坨。
     * 输入 enhancedBitmap 由 state 持有，函数内部不会 recycle 它。
     */
    private fun processToPrint(enhanced: Bitmap, state: WrongBookState): Triple<ByteArray, Int, Int> {
        var bmp = enhanced
        var owned = false

        // 1. 旋转（Bitmap 域，90° 像素级无损）
        if (state.rotation % 360 != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(state.rotation.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            owned = true
        }

        // 2. 灰度域缩放到 384 宽（双线性保留灰度渐变，避免二值图缩放糊边）
        if (bmp.width != WIDTH_DOTS) {
            val th = maxOf(1, Math.round(bmp.height.toFloat() * WIDTH_DOTS / bmp.width))
            val scaled = Bitmap.createScaledBitmap(bmp, WIDTH_DOTS, th, true)
            if (owned) bmp.recycle()
            bmp = scaled
            owned = true
        }

        val w = WIDTH_DOTS
        val h = bmp.height
        // 灰度图 → 384 打印分辨率上自适应二值化（清晰不糊、不锯齿）
        val gray = bitmapToGrayRaw(bmp)
        if (owned) bmp.recycle()
        val binary = DocumentEnhancer.enhanceGray(
            gray,
            windowSize = state.sauvolaWindow,
            k = state.sauvolaK,
            denoise = true,
            mode = state.binarizeMode
        )

        // 3. 翻转 / 反色
        var out = binary
        if (state.flipH) out = flipBinaryHorizontal(out, w, h)
        if (state.invert) out = invertBinary(out, w, h)

        // 4. 连通域去噪（只去小团噪点，不伤笔画）
        DocumentEnhancer.removeSmallComponents(out, w, h, 4)

        return Triple(out, w, h)
    }

    /**
     * 根据当前 state 重新生成预览
     */
    fun reRender() {
        val enhanced = _state.value.enhancedBitmap ?: return
        val state = _state.value
        viewModelScope.launch {
            try {
                val oldPreview = _state.value.previewBitmap
                val preview = withContext(Dispatchers.Default) {
                    val (binary, w, h) = processToPrint(enhanced, state)
                    binaryToPreviewBitmap(binary, w, h, false)
                }
                _state.value = _state.value.copy(previewBitmap = preview)
                oldPreview?.let { if (it != preview) it.recycle() }
            } catch (e: Exception) { }
        }
    }

    /**
     * 打印
     */
    fun print() {
        val enhanced = _state.value.enhancedBitmap ?: return
        if (_state.value.printing) return

        if (printerStatus.value.connState != ConnState.CONNECTED) {
            _state.value = _state.value.copy(resultOk = false, resultMessage = "请先在首页连接打印机")
            return
        }

        _state.value = _state.value.copy(printing = true, resultMessage = "")

        viewModelScope.launch {
            try {
                val fault = withContext(Dispatchers.IO) { printerConnection.preflightCheck() }
                if (fault != null) {
                    _state.value = _state.value.copy(printing = false, resultOk = false, resultMessage = fault)
                    return@launch
                }

                val state = _state.value
                val result = withContext(Dispatchers.Default) {
                    // 统一后处理（旋转/抗锯齿缩放/形态学/去噪），与预览一致
                    val (binary, w, h) = processToPrint(enhanced, state)
                    val raster = packBinaryToRaster(binary, w, h)

                    val fullBmp = binaryToPreviewBitmap(binary, w, h, false)
                    val thumbBmp = Bitmap.createScaledBitmap(fullBmp, 200, Math.round(200f * fullBmp.height / fullBmp.width), true)

                    val printResult = withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, 1)
                    }

                    if (printResult.ok) {
                        try {
                            val payload = JSONObject().apply {
                                put("tags", state.selectedTags.joinToString(","))
                                put("sauvolaWindow", state.sauvolaWindow)
                                put("sauvolaK", state.sauvolaK.toDouble())
                                put("binarizeMode", state.binarizeMode)
                                put("rotation", state.rotation)
                                put("flipH", state.flipH)
                                put("invert", state.invert)
                            }.toString()
                            historyRepo.saveHistory(HIST_TYPE_WRONGBOOK, thumbBmp, payload)
                        } catch (e: Exception) { }
                    }

                    fullBmp.recycle()
                    thumbBmp.recycle()
                    printResult
                }

                _state.value = _state.value.copy(printing = false, resultOk = result.ok, resultMessage = result.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(printing = false, resultOk = false, resultMessage = "打印失败：${e.message}")
            }
        }
    }

    /**
     * 保存到错题本
     */
    fun saveToWrongBook() {
        val enhanced = _state.value.enhancedBitmap ?: return
        if (_state.value.saving) return

        _state.value = _state.value.copy(saving = true)

        viewModelScope.launch {
            try {
                val state = _state.value
                withContext(Dispatchers.Default) {
                    val thumbBmp = Bitmap.createScaledBitmap(
                        enhanced, 200, Math.round(200f * enhanced.height / enhanced.width), true
                    )
                    // 保存全分辨率增强图到文件
                    val fullFile = java.io.File(
                        getApplication<Application>().getDir("wrongbook_images", android.content.Context.MODE_PRIVATE),
                        "wb_${System.currentTimeMillis()}.png"
                    )
                    fullFile.outputStream().use { enhanced.compress(Bitmap.CompressFormat.PNG, 100, it) }

                    val payload = JSONObject().apply {
                        put("tags", state.selectedTags.joinToString(","))
                        put("rotation", state.rotation)
                        put("flipH", state.flipH)
                        put("invert", state.invert)
                        put("fullImagePath", fullFile.absolutePath)
                    }.toString()
                    historyRepo.saveHistory(HIST_TYPE_WRONGBOOK, thumbBmp, payload)
                    thumbBmp.recycle()
                }
                _state.value = _state.value.copy(saving = false, saved = true, resultOk = true, resultMessage = "已保存到错题本")
            } catch (e: Exception) {
                _state.value = _state.value.copy(saving = false, resultOk = false, resultMessage = "保存失败：${e.message}")
            }
        }
    }

    fun clearResult() { _state.value = _state.value.copy(resultMessage = "", resultOk = false, saved = false) }

    override fun onCleared() {
        super.onCleared()
        _state.value.originalBitmap?.recycle()
        _state.value.croppedBitmap?.recycle()
        _state.value.enhancedBitmap?.recycle()
        _state.value.previewBitmap?.recycle()
    }
}
