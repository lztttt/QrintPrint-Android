package com.qring.printer.ui.pdfprint

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.printer.bt.PrintResult
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.data.HistoryPayloadHolder
import com.qring.printer.data.HistoryRepository
import com.qring.printer.model.ConnState
import com.qring.printer.model.HIST_TYPE_PDF
import com.qring.printer.model.PrinterStatus
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.DitherMode
import com.qring.printer.protocol.GrayImage
import com.qring.printer.protocol.ImagePrintOptions
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.adjustGrayImage
import com.qring.printer.protocol.bitmapToGray
import com.qring.printer.protocol.binaryToPreviewBitmap
import com.qring.printer.protocol.enhanceToBinary
import com.qring.printer.protocol.packBinaryToRaster
import com.qring.printer.protocol.transformToBinary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** PDF 页面元信息（缩略图 + 按打印分辨率计算的尺寸） */
data class PdfPageUi(
    val index: Int,          // 1-based 页码
    val thumb: Bitmap?,
    val dotsWidth: Int,
    val dotsHeight: Int,
)

data class PdfPrintUiState(
    val pdfPath: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 1,          // 1-based
    val pages: List<PdfPageUi> = emptyList(),
    val printAll: Boolean = true,      // true = 全部页，false = 仅当前页
    // true = 文档增强（Sauvola 自适应二值化，适合文字/表格文档）
    // false = 普通阈值抖动（适合照片型 PDF）
    val enhanceMode: Boolean = true,
    val ditherMode: DitherMode = DitherMode.FLOYD_STEINBERG,
    val threshold: Int = 128,
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val invert: Boolean = false,
    val contrast: Int = 0,
    val brightness: Int = 0,
    val sharpness: Int = 0,
    val thickness: Int? = null,
    val previewBitmap: Bitmap? = null,
    val busy: Boolean = false,
    val printing: Boolean = false,
    val progressText: String = "",
    val resultMessage: String = "",
    val resultOk: Boolean = false,
    // 横向页面推荐（同图片打印）：宽>高 且未旋转时，推荐旋转 90° 打印
    val showLandscapeSuggestion: Boolean = false,
    val landscapeSuggestionText: String = "",
    val landscapeSuggestedRotation: Int = 0,
)

class PdfPrintViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val historyRepo = HistoryRepository(application)

    /** PdfRenderer 非线程安全，所有访问统一加锁（预览/打印/关闭） */
    private val rendererMutex = Mutex()
    private var renderer: PdfRenderer? = null

    private var previewJob: Job? = null
    private var previewGeneration = 0

    private val _uiState = MutableStateFlow(PdfPrintUiState())
    val uiState: StateFlow<PdfPrintUiState> = _uiState.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    init {
        restoreFromHistoryPayload()
    }

    /** 从历史记录重打时恢复 PDF + 页码 + 选项 */
    private fun restoreFromHistoryPayload() {
        val (type, payload) = HistoryPayloadHolder.consumePayload() ?: return
        if (type != HIST_TYPE_PDF) {
            HistoryPayloadHolder.setPayload(type, payload)
            return
        }
        try {
            val obj = JSONObject(payload)
            val path = obj.optString("path", "")
            if (path.isEmpty() || !File(path).exists()) return
            _uiState.value = _uiState.value.copy(
                pdfPath = path,
                enhanceMode = obj.optBoolean("enhanceMode", true),
                ditherMode = DitherMode.entries.getOrElse(obj.optInt("ditherMode", 1)) { DitherMode.FLOYD_STEINBERG },
                threshold = obj.optInt("threshold", 128),
                rotation = obj.optInt("rotation", 0),
                flipH = obj.optBoolean("flipH", false),
                flipV = obj.optBoolean("flipV", false),
                invert = obj.optBoolean("invert", false),
                contrast = obj.optInt("contrast", 0),
                brightness = obj.optInt("brightness", 0),
                sharpness = obj.optInt("sharpness", 0),
                thickness = obj.optInt("thickness", 0).takeIf { it > 0 },
                printAll = obj.optBoolean("printAll", true),
                currentPage = obj.optInt("currentPage", 1)
            )
            openInternal(path, selectPage = obj.optInt("currentPage", 1), suggestLandscape = false)
        } catch (e: Exception) {
            Timber.tag("PdfVM").w(e, "restoreFromHistoryPayload failed")
        }
    }

    /** 选择 PDF：先拷贝到内部存储（防权限过期），再打开 */
    fun openPdf(uriString: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, progressText = "正在拷贝 PDF…")
            val path = withContext(Dispatchers.IO) {
                historyRepo.savePdfToInternalStorage(uriString)
            }
            if (path == null) {
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    resultOk = false,
                    resultMessage = "PDF 拷贝失败，请重试"
                )
                return@launch
            }
            openInternal(path, selectPage = 1, suggestLandscape = true)
        }
    }

    /** 打开 PDF 并生成页面列表（缩略图） */
    private fun openInternal(path: String, selectPage: Int, suggestLandscape: Boolean = true) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    rendererMutex.withLock {
                        closeRendererLocked()
                        val fd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                        val r = PdfRenderer(fd)
                        renderer = r
                        val n = r.pageCount
                        val pages = mutableListOf<PdfPageUi>()
                        for (i in 0 until n) {
                            val page = r.openPage(i)
                            try {
                                val pw = page.width
                                val ph = page.height
                                val tw = 96
                                val th = maxOf(1, Math.round(ph.toFloat() * tw / pw))
                                val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val dotsH = maxOf(1, Math.round(ph.toFloat() * WIDTH_DOTS / pw))
                                pages.add(PdfPageUi(i + 1, bmp, WIDTH_DOTS, dotsH))
                            } finally {
                                page.close()
                            }
                        }
                        Triple(n, pages, r)
                    }
                }
                val (n, pages, r) = result
                val pageIdx = selectPage.coerceIn(1, n)
                _uiState.value = _uiState.value.copy(
                    pdfPath = path,
                    pageCount = n,
                    pages = pages,
                    currentPage = pageIdx,
                    busy = false,
                    progressText = ""
                )
                renderCurrentPage()
                if (suggestLandscape) maybeSuggestLandscape()
            } catch (e: Exception) {
                Timber.tag("PdfVM").e(e, "open pdf failed")
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    resultOk = false,
                    resultMessage = "PDF 打开失败：${e.message}"
                )
            }
        }
    }

    private fun closeRendererLocked() {
        try { renderer?.close() } catch (e: Exception) { }
        renderer = null
    }

    fun selectPage(page: Int) {
        val n = _uiState.value.pageCount
        if (n == 0) return
        val clamped = page.coerceIn(1, n)
        if (clamped == _uiState.value.currentPage) return
        _uiState.value = _uiState.value.copy(currentPage = clamped)
        renderCurrentPage()
        maybeSuggestLandscape()
    }

    fun setPrintAll(all: Boolean) {
        _uiState.value = _uiState.value.copy(printAll = all)
    }

    /** 切换文档增强模式（Sauvola 自适应二值化 vs 普通阈值抖动） */
    fun setEnhanceMode(mode: Boolean) {
        _uiState.value = _uiState.value.copy(enhanceMode = mode)
        reRender()
    }

    /**
     * 横向页面（宽>高）且尚未旋转成竖版长条（rotation 为 0/180）时，
     * 提示旋转 90° 打印：长边沿出纸方向，打印分辨率/尺寸最佳。
     */
    private fun maybeSuggestLandscape() {
        val st = _uiState.value
        val page = st.pages.getOrNull(st.currentPage - 1) ?: return
        // dotsWidth 恒为 384（渲染宽度）；横向页面渲染后高度小于 384
        if (page.dotsHeight >= page.dotsWidth) return
        if (st.rotation % 180 != 0) return
        // 由页面原始宽高（dotsWidth/dotsHeight 反推比例）
        val ratioW = page.dotsWidth
        val ratioH = page.dotsHeight
        val directH = ratioH
        val rotatedH = Math.round(ratioW.toFloat() * WIDTH_DOTS / maxOf(1, ratioH))
        _uiState.value = _uiState.value.copy(
            showLandscapeSuggestion = true,
            landscapeSuggestedRotation = 90,
            landscapeSuggestionText = "检测到横向页面（宽:高 ≈ ${ratioText(ratioW, ratioH)}）。\n" +
                "直接打印是矮横条（384×$directH 点），细节大量丢失。\n\n" +
                "建议旋转 90° 打印：长边沿出纸方向（384×$rotatedH 点），分辨率最佳，内容更清晰。"
        )
    }

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

    /** 关闭横向页面推荐弹窗 */
    fun dismissLandscapeSuggestion() {
        _uiState.value = _uiState.value.copy(showLandscapeSuggestion = false)
    }

    /** 应用推荐：旋转到建议角度 */
    fun applyLandscapeSuggestion() {
        val wasShowing = _uiState.value.showLandscapeSuggestion
        val target = _uiState.value.landscapeSuggestedRotation
        _uiState.value = _uiState.value.copy(showLandscapeSuggestion = false)
        if (wasShowing && target != _uiState.value.rotation) {
            setRotation(target)
        }
    }

    fun setDitherMode(mode: DitherMode) { _uiState.value = _uiState.value.copy(ditherMode = mode); reRender() }
    fun setThreshold(t: Int) { _uiState.value = _uiState.value.copy(threshold = t); reRender() }
    fun setThickness(t: Int?) { _uiState.value = _uiState.value.copy(thickness = t) }
    fun setRotation(d: Int) {
        val norm = ((d % 360) + 360) % 360
        _uiState.value = _uiState.value.copy(rotation = norm)
        reRender()
    }
    fun toggleFlipH() { _uiState.value = _uiState.value.copy(flipH = !_uiState.value.flipH); reRender() }
    fun toggleFlipV() { _uiState.value = _uiState.value.copy(flipV = !_uiState.value.flipV); reRender() }
    fun toggleInvert() { _uiState.value = _uiState.value.copy(invert = !_uiState.value.invert); reRender() }
    fun setContrast(v: Int) { _uiState.value = _uiState.value.copy(contrast = v); reRender() }
    fun setBrightness(v: Int) { _uiState.value = _uiState.value.copy(brightness = v); reRender() }
    fun setSharpness(v: Int) { _uiState.value = _uiState.value.copy(sharpness = v); reRender() }

    private fun buildOptions(state: PdfPrintUiState): ImagePrintOptions {
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

    /** 渲染当前页到灰度（可加调整），供预览/打印复用 */
    private suspend fun renderPageGrayLocked(pageIndex: Int, applyAdjust: Boolean): GrayImage? {
        val r = renderer ?: return null
        return withContext(Dispatchers.Default) {
            val page = r.openPage(pageIndex)
            try {
                val pw = page.width
                val ph = page.height
                val th = maxOf(1, Math.round(ph.toFloat() * WIDTH_DOTS / pw))
                val bmp = Bitmap.createBitmap(WIDTH_DOTS, th, Bitmap.Config.ARGB_8888)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val gray = bitmapToGray(bmp)
                bmp.recycle()
                if (applyAdjust) {
                    val st = _uiState.value
                    adjustGrayImage(gray, st.contrast, st.brightness, st.sharpness)
                } else {
                    gray
                }
            } finally {
                page.close()
            }
        }
    }

    /** 渲染当前页预览 */
    fun renderCurrentPage() {
        val st = _uiState.value
        if (st.pageCount == 0) return
        previewJob?.cancel()
        val holder = AtomicReference<Bitmap?>(null)
        previewJob = viewModelScope.launch {
            val gen = ++previewGeneration
            try {
                val preview = rendererMutex.withLock {
                    renderPageGrayLocked(st.currentPage - 1, applyAdjust = true)?.let { gray ->
                        val opts = buildOptions(_uiState.value)
                        val (binary, w, h) = if (_uiState.value.enhanceMode) {
                            enhanceToBinary(gray, opts)
                        } else {
                            transformToBinary(gray, opts)
                        }
                        binaryToPreviewBitmap(binary, w, h, false).also { holder.set(it) }
                    }
                }
                if (preview == null) return@launch
                if (gen != previewGeneration) {
                    preview.recycle()
                    holder.set(null)
                    return@launch
                }
                val old = _uiState.value.previewBitmap
                _uiState.value = _uiState.value.copy(previewBitmap = preview, busy = false)
                holder.set(null)
                old?.let { if (it != preview) { delay(150); it.recycle() } }
            } catch (e: CancellationException) {
                holder.getAndSet(null)?.recycle()
                throw e
            } catch (e: Exception) {
                holder.getAndSet(null)?.recycle()
                Timber.tag("PdfVM").w(e, "renderCurrentPage failed")
            }
        }
    }

    fun reRender() = renderCurrentPage()

    fun print() {
        val st = _uiState.value
        if (st.printing || st.busy) return
        if (st.pageCount == 0) {
            _uiState.value = _uiState.value.copy(resultOk = false, resultMessage = "请先选择一个 PDF 文件")
            return
        }
        if (printerStatus.value.connState != ConnState.CONNECTED) {
            _uiState.value = _uiState.value.copy(resultOk = false, resultMessage = "请先在首页连接打印机")
            return
        }

        _uiState.value = _uiState.value.copy(printing = true, resultMessage = "")
        val pagesToPrint = if (st.printAll) (1..st.pageCount).toList() else listOf(st.currentPage)

        viewModelScope.launch {
            var thumbBitmap: Bitmap? = null
            var okCount = 0
            try {
                val fault = withContext(Dispatchers.IO) { printerConnection.preflightCheck() }
                if (fault != null) {
                    _uiState.value = _uiState.value.copy(printing = false, resultOk = false, resultMessage = fault)
                    return@launch
                }

                val failed = mutableListOf<String>()
                for ((idx, page) in pagesToPrint.withIndex()) {
                    _uiState.value = _uiState.value.copy(
                        progressText = "正在打印第 ${idx + 1}/${pagesToPrint.size} 页…"
                    )
                    val r = rendererMutex.withLock {
                        val gray = renderPageGrayLocked(page - 1, applyAdjust = true)
                            ?: return@withLock PrintResult(false, "页面渲染失败")
                        withContext(Dispatchers.Default) {
                            val opts = buildOptions(_uiState.value)
                            val (binary, w, h) = if (_uiState.value.enhanceMode) {
                                enhanceToBinary(gray, opts)
                            } else {
                                transformToBinary(gray, opts)
                            }
                            val raster = packBinaryToRaster(binary, w, h)
                            if (thumbBitmap == null) {
                                val bmp = binaryToPreviewBitmap(binary, w, h, false)
                                thumbBitmap = Bitmap.createScaledBitmap(
                                    bmp, 200, Math.round(200f * bmp.height / bmp.width), true
                                )
                                bmp.recycle()
                            }
                            withContext(Dispatchers.IO) {
                                printerConnection.printRaster(raster, _uiState.value.thickness)
                            }
                        }
                    }
                    if (r.ok) {
                        okCount++
                    } else {
                        failed.add("第 $page 页：${r.message}")
                    }
                }

                val okAll = failed.isEmpty()
                if (okAll) {
                    // 保存历史
                    try {
                        val payload = JSONObject().apply {
                            put("path", _uiState.value.pdfPath)
                            put("enhanceMode", _uiState.value.enhanceMode)
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
                            put("printAll", _uiState.value.printAll)
                            put("currentPage", _uiState.value.currentPage)
                        }.toString()
                        historyRepo.saveHistory(HIST_TYPE_PDF, thumbBitmap!!, payload)
                    } catch (e: Exception) {
                        Timber.tag("PdfVM").w(e, "saveHistory failed")
                    }
                    _uiState.value = _uiState.value.copy(
                        printing = false,
                        progressText = "",
                        resultOk = true,
                        resultMessage = "打印完成，共 $okCount 页"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        printing = false,
                        progressText = "",
                        resultOk = false,
                        resultMessage = "打印中断：${failed.joinToString("；")}"
                    )
                }
            } catch (e: Exception) {
                Timber.tag("PdfVM").e(e, "print failed")
                _uiState.value = _uiState.value.copy(
                    printing = false,
                    progressText = "",
                    resultOk = false,
                    resultMessage = "打印失败：${e.message}"
                )
            } finally {
                try { thumbBitmap?.recycle() } catch (e: Exception) { }
                withContext(NonCancellable) {
                    if (_uiState.value.printing) {
                        _uiState.value = _uiState.value.copy(printing = false)
                    }
                }
            }
        }
    }

    fun clearResult() {
        _uiState.value = _uiState.value.copy(resultMessage = "", resultOk = false)
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.previewBitmap?.recycle()
        viewModelScope.launch {
            rendererMutex.withLock { closeRendererLocked() }
        }
    }
}
