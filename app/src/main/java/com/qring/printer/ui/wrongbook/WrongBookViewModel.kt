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
import com.qring.printer.protocol.bitmapToGray
import com.qring.printer.protocol.binaryToPreviewBitmap
import com.qring.printer.protocol.DocumentEnhancer
import com.qring.printer.protocol.GrayImage
import com.qring.printer.protocol.packBinaryToRaster
import com.qring.printer.protocol.rotateBinary
import com.qring.printer.protocol.scaleBinaryToWidth
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
                    generatePreview(scaled, WrongBookState(rotation = rotation, flipH = flipH, invert = invert))
                }

                _state.value = _state.value.copy(
                    enhancedBitmap = scaled,
                    previewBitmap = preview,
                    step = WrongBookStep.ENHANCE,
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
     * 文档增强 (Sauvola 自适应二值化)
     */
    fun enhance() {
        val cropped = _state.value.croppedBitmap ?: return
        _state.value = _state.value.copy(processing = true, processingHint = "正在增强文档...")

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val scaled = Bitmap.createScaledBitmap(
                        cropped, WIDTH_DOTS,
                        (cropped.height.toFloat() / cropped.width * WIDTH_DOTS).toInt(), true
                    )
                    val enhanced = DocumentEnhancer.enhance(scaled, windowSize = 25, k = 0.2f, denoise = true)
                    val preview = generatePreview(enhanced, _state.value)
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
     * 根据当前 state 重新生成预览
     */
    fun reRender() {
        val enhanced = _state.value.enhancedBitmap ?: return
        val state = _state.value
        viewModelScope.launch {
            try {
                val oldPreview = _state.value.previewBitmap
                val preview = withContext(Dispatchers.Default) {
                    generatePreview(enhanced, state)
                }
                _state.value = _state.value.copy(previewBitmap = preview)
                oldPreview?.let { if (it != preview) it.recycle() }
            } catch (e: Exception) { }
        }
    }

    private fun generatePreview(enhanced: Bitmap, state: WrongBookState): Bitmap {
        // enhanced 已经是二值图，直接转 binary 数组处理
        val gray = bitmapToGray(enhanced)
        var binary = ByteArray(gray.width * gray.height)
        for (i in binary.indices) {
            binary[i] = if (gray.data[i] < 128) 1 else 0
        }
        var w = gray.width
        var h = gray.height

        if (state.rotation % 360 != 0) {
            val (rot, nw, nh) = rotateBinary(binary, w, h, state.rotation)
            binary = rot; w = nw; h = nh
            // 旋转后宽度可能不是 384，等比缩放回 384
            if (w != WIDTH_DOTS) {
                val (sb, sw, sh) = scaleBinaryToWidth(binary, w, h, WIDTH_DOTS)
                binary = sb; w = sw; h = sh
            }
        }
        if (state.flipH) binary = flipBinaryHorizontal(binary, w, h)
        if (state.invert) binary = invertBinary(binary, w, h)

        return binaryToPreviewBitmap(binary, w, h, false)
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
                    val gray = bitmapToGray(enhanced)
                    var binary = ByteArray(gray.width * gray.height)
                    for (i in binary.indices) binary[i] = if (gray.data[i] < 128) 1 else 0
                    var w = gray.width
                    var h = gray.height

                    if (state.rotation % 360 != 0) {
                        val (rot, nw, nh) = rotateBinary(binary, w, h, state.rotation)
                        binary = rot; w = nw; h = nh
                        if (w != WIDTH_DOTS) {
                            val (sb, sw, sh) = scaleBinaryToWidth(binary, w, h, WIDTH_DOTS)
                            binary = sb; w = sw; h = sh
                        }
                    }
                    if (state.flipH) binary = flipBinaryHorizontal(binary, w, h)
                    if (state.invert) binary = invertBinary(binary, w, h)

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
