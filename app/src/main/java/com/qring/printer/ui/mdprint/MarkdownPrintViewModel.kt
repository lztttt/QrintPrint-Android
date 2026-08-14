package com.qring.printer.ui.mdprint

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.printer.bt.PrintResult
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.data.HistoryPayloadHolder
import com.qring.printer.data.HistoryRepository
import com.qring.printer.model.ConnState
import com.qring.printer.model.HIST_TYPE_MARKDOWN
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.MarkdownOptions
import com.qring.printer.protocol.MdMeasuredLine
import com.qring.printer.protocol.bitmapToRasterStreamed
import com.qring.printer.protocol.layoutMarkdown
import com.qring.printer.protocol.renderMarkdownBitmap
import com.qring.printer.ui.common.FontList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

data class MarkdownPrintState(
    val text: String = "",
    val fontSize: Float = 14f,
    val lineSpacing: Float = 4f,
    val margin: Float = 8f,
    val fontFamilies: List<String> = listOf("sans-serif", "serif", "monospace"),
    val fontFamilyIndex: Int = 0,
    val previewBitmap: Bitmap? = null,
    // 预览因超长被截断（只渲染开头，完整内容以打印为准）
    val previewTruncated: Boolean = false,
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false
)

class MarkdownPrintViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val historyRepo = HistoryRepository(application)

    /** 预览渲染高度上限：超长文档只渲染开头，避免超大 Bitmap / GPU 上传 */
    private val PREVIEW_MAX_HEIGHT = 1600

    /** 单个打印分块的最大高度（点） */
    private val PRINT_CHUNK_HEIGHT = 6000f

    /** 预览任务句柄：防抖 + 取消，只保留最后一次 */
    private var previewJob: Job? = null
    private var previewGeneration = 0

    private val _state = MutableStateFlow(MarkdownPrintState())
    val state: StateFlow<MarkdownPrintState> = _state.asStateFlow()

    val printerStatus = PrinterStatusRepository.state

    init {
        loadFonts()
        restoreFromHistoryPayload()
    }

    /** 从历史记录重打时恢复内容与排版参数 */
    private fun restoreFromHistoryPayload() {
        val (type, payload) = HistoryPayloadHolder.consumePayload() ?: return
        if (type != HIST_TYPE_MARKDOWN) {
            HistoryPayloadHolder.setPayload(type, payload)
            return
        }
        try {
            val obj = JSONObject(payload)
            _state.value = _state.value.copy(
                text = obj.optString("text", ""),
                fontSize = obj.optDouble("fontSize", 14.0).toFloat(),
                lineSpacing = obj.optDouble("lineSpacing", 4.0).toFloat(),
                margin = obj.optDouble("margin", 8.0).toFloat(),
                fontFamilyIndex = obj.optInt("fontIndex", 0)
            )
            updatePreview()
        } catch (e: Exception) {
            Timber.tag("MdPrintVM").w(e, "restoreFromHistoryPayload failed")
        }
    }

    fun loadFonts() {
        val fonts = FontList.getSystemFonts(getApplication())
        _state.value = _state.value.copy(fontFamilies = fonts)
    }

    fun setFontFamilyIndex(index: Int) {
        val families = _state.value.fontFamilies
        if (index in families.indices) {
            _state.value = _state.value.copy(fontFamilyIndex = index)
            updatePreview()
        }
    }

    fun importFont(uri: android.net.Uri): String? {
        val name = FontList.importFont(getApplication(), uri)
        if (name != null) {
            loadFonts()
            val idx = _state.value.fontFamilies.indexOf(name)
            if (idx >= 0) {
                _state.value = _state.value.copy(fontFamilyIndex = idx)
                updatePreview()
            }
        }
        return name
    }

    fun deleteImportedFont(family: String) {
        FontList.deleteImportedFont(family)
        loadFonts()
        if (_state.value.fontFamilyIndex >= _state.value.fontFamilies.size) {
            _state.value = _state.value.copy(fontFamilyIndex = 0)
            updatePreview()
        }
    }

    fun isImportedFont(family: String): Boolean = FontList.isImported(family)

    private fun buildOptions(st: MarkdownPrintState): MarkdownOptions {
        return MarkdownOptions(
            fontSize = st.fontSize,
            lineSpacing = st.lineSpacing,
            margin = st.margin,
            fontFamilyIndex = st.fontFamilyIndex,
            fontFamilies = st.fontFamilies
        )
    }

    fun updateText(text: String) {
        _state.value = _state.value.copy(text = text)
        updatePreview()
    }

    fun setFontSize(size: Float) {
        _state.value = _state.value.copy(fontSize = size)
        updatePreview()
    }

    fun setLineSpacing(spacing: Float) {
        _state.value = _state.value.copy(lineSpacing = spacing)
        updatePreview()
    }

    fun setMargin(margin: Float) {
        _state.value = _state.value.copy(margin = margin)
        updatePreview()
    }

    /** 预览：取消上一次任务 + 超长内容只渲染开头 PREVIEW_MAX_HEIGHT */
    fun updatePreview() {
        val st = _state.value
        if (st.text.isEmpty()) {
            val old = _state.value.previewBitmap
            if (old != null) {
                _state.value = _state.value.copy(previewBitmap = null, previewTruncated = false)
                old.recycle()
            }
            return
        }
        if (st.printing) return

        previewJob?.cancel()
        val holder = AtomicReference<Bitmap?>(null)
        previewJob = viewModelScope.launch {
            val gen = ++previewGeneration
            try {
                val result = withContext(Dispatchers.Default) {
                    val opts = buildOptions(st)
                    val allLines = layoutMarkdown(st.text, opts)
                    // 只保留开头不超过上限高度的行
                    val previewLines = mutableListOf<MdMeasuredLine>()
                    var h = 0f
                    var truncated = false
                    for (line in allLines) {
                        if (previewLines.isNotEmpty() && h + line.lineHeight > PREVIEW_MAX_HEIGHT) {
                            truncated = true
                            break
                        }
                        previewLines.add(line)
                        h += line.lineHeight
                    }
                    val bmp = renderMarkdownBitmap(previewLines, opts)
                    holder.set(bmp)
                    Pair(bmp, truncated)
                }
                if (gen != previewGeneration) {
                    result.first.recycle()
                    holder.set(null)
                    return@launch
                }
                val old = _state.value.previewBitmap
                _state.value = _state.value.copy(previewBitmap = result.first, previewTruncated = result.second)
                holder.set(null)
                old?.let { if (it != result.first) { delay(150); it.recycle() } }
            } catch (e: CancellationException) {
                holder.getAndSet(null)?.recycle()
                throw e
            } catch (e: Exception) {
                holder.getAndSet(null)?.recycle()
                Timber.tag("MdPrintVM").w(e, "updatePreview failed")
            }
        }
    }

    fun print() {
        val st = _state.value
        if (st.printing) return
        if (printerStatus.value.connState != ConnState.CONNECTED) {
            _state.value = _state.value.copy(resultOk = false, resultMessage = "请先在首页连接打印机")
            return
        }
        if (st.text.isEmpty()) {
            _state.value = _state.value.copy(resultOk = false, resultMessage = "请输入要打印的内容")
            return
        }

        _state.value = _state.value.copy(printing = true, resultMessage = "")

        viewModelScope.launch {
            var thumbBitmap: Bitmap? = null
            try {
                val fault = withContext(Dispatchers.IO) { printerConnection.preflightCheck() }
                if (fault != null) {
                    _state.value = _state.value.copy(printing = false, resultOk = false, resultMessage = fault)
                    return@launch
                }

                val result = withContext(Dispatchers.Default) {
                    val opts = buildOptions(st)
                    val lines = layoutMarkdown(st.text, opts)

                    // 按高度分块：长文档分段渲染，避免单次创建超大 Bitmap
                    val chunks = mutableListOf<List<MdMeasuredLine>>()
                    var cur = mutableListOf<MdMeasuredLine>()
                    var curH = 0f
                    for (line in lines) {
                        if (cur.isNotEmpty() && curH + line.lineHeight > PRINT_CHUNK_HEIGHT) {
                            chunks.add(cur)
                            cur = mutableListOf()
                            curH = 0f
                        }
                        cur.add(line)
                        curH += line.lineHeight
                    }
                    if (cur.isNotEmpty()) chunks.add(cur)

                    for (chunk in chunks) {
                        val bmp = renderMarkdownBitmap(chunk, opts)
                        try {
                            if (thumbBitmap == null) {
                                thumbBitmap = Bitmap.createScaledBitmap(
                                    bmp, 200, Math.round(200f * bmp.height / bmp.width), true
                                )
                            }
                            val raster = bitmapToRasterStreamed(bmp, 211)
                            val r = withContext(Dispatchers.IO) {
                                printerConnection.printRaster(raster, 1)
                            }
                            if (!r.ok) {
                                return@withContext PrintResult(false, r.message)
                            }
                        } finally {
                            bmp.recycle()
                        }
                    }
                    PrintResult(true, "打印完成")
                }

                if (result.ok) {
                    try {
                        val payload = JSONObject().apply {
                            put("text", st.text)
                            put("fontSize", st.fontSize.toDouble())
                            put("lineSpacing", st.lineSpacing.toDouble())
                            put("margin", st.margin.toDouble())
                            put("fontIndex", st.fontFamilyIndex)
                        }.toString()
                        historyRepo.saveHistory(HIST_TYPE_MARKDOWN, thumbBitmap!!, payload)
                    } catch (e: Exception) {
                        Timber.tag("MdPrintVM").w(e, "saveHistory failed")
                    }
                }

                _state.value = _state.value.copy(
                    printing = false,
                    resultOk = result.ok,
                    resultMessage = result.message
                )
            } catch (e: Exception) {
                Timber.tag("MdPrintVM").e(e, "print failed")
                _state.value = _state.value.copy(
                    printing = false,
                    resultOk = false,
                    resultMessage = "打印失败：${e.message}"
                )
            } finally {
                try { thumbBitmap?.recycle() } catch (e: Exception) { }
                withContext(NonCancellable) {
                    if (_state.value.printing) {
                        _state.value = _state.value.copy(printing = false)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _state.value.previewBitmap?.recycle()
    }
}
