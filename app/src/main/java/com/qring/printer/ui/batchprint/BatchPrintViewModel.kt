package com.qring.printer.ui.batchprint

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.printer.bt.PrintResult
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.data.HistoryPayloadHolder
import com.qring.printer.data.HistoryRepository
import com.qring.printer.model.ConnState
import com.qring.printer.model.HIST_TYPE_BATCH
import com.qring.printer.model.PrinterStatus
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.DitherMode
import com.qring.printer.protocol.GrayImage
import com.qring.printer.protocol.ImagePrintOptions
import com.qring.printer.protocol.MarkdownOptions
import com.qring.printer.protocol.MdMeasuredLine
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.bitmapToGray
import com.qring.printer.protocol.bitmapToRasterStreamed
import com.qring.printer.protocol.decodeSourceToPrintWidth
import com.qring.printer.protocol.layoutMarkdown
import com.qring.printer.protocol.packBinaryToRaster
import com.qring.printer.protocol.renderMarkdownBitmap
import com.qring.printer.protocol.transformToBinary
import com.qring.printer.ui.common.FontList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.charset.Charset
import java.util.UUID

enum class BatchItemKind { TEXT, IMAGE }

data class BatchItem(
    val id: String,
    val name: String,
    val kind: BatchItemKind,
    val sourcePath: String,       // 内部存储路径
    val textContent: String = "", // TEXT 项内容
    val selected: Boolean = true,
)

data class BatchPrintUiState(
    val items: List<BatchItem> = emptyList(),
    // 文字排版（txt / md 共用 Markdown 渲染）
    val textFontSize: Float = 14f,
    val textLineSpacing: Float = 4f,
    val fontFamilyIndex: Int = 0,
    val fontFamilies: List<String> = listOf("sans-serif", "serif", "monospace"),
    // 图片预设参数
    val imageDitherMode: DitherMode = DitherMode.FLOYD_STEINBERG,
    val imageThreshold: Int = 128,
    val thickness: Int? = null,
    val printing: Boolean = false,
    val progressIndex: Int = 0,
    val progressTotal: Int = 0,
    val progressName: String = "",
    val resultMessage: String = "",
    val resultOk: Boolean = false,
)

class BatchPrintViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val historyRepo = HistoryRepository(application)
    private val app = application

    /** 单个文字分块的最大高度（点） */
    private val PRINT_CHUNK_HEIGHT = 6000f

    private val _uiState = MutableStateFlow(BatchPrintUiState())
    val uiState: StateFlow<BatchPrintUiState> = _uiState.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    init {
        loadFonts()
        restoreFromHistoryPayload()
    }

    private fun loadFonts() {
        val fonts = FontList.getSystemFonts(app)
        _uiState.value = _uiState.value.copy(fontFamilies = fonts)
    }

    /** 从历史记录重打时恢复文件清单与设置 */
    private fun restoreFromHistoryPayload() {
        val (type, payload) = HistoryPayloadHolder.consumePayload() ?: return
        if (type != HIST_TYPE_BATCH) {
            HistoryPayloadHolder.setPayload(type, payload)
            return
        }
        try {
            val obj = JSONObject(payload)
            val arr = obj.optJSONArray("items") ?: return
            val items = mutableListOf<BatchItem>()
            for (i in 0 until arr.length()) {
                val jo = arr.getJSONObject(i)
                val path = jo.optString("path", "")
                if (path.isEmpty() || !File(path).exists()) continue
                val kind = if (jo.optString("kind", "TEXT") == "IMAGE") BatchItemKind.IMAGE else BatchItemKind.TEXT
                val text = if (kind == BatchItemKind.TEXT) {
                    readTextFile(path) ?: ""
                } else ""
                items.add(BatchItem(
                    id = jo.optString("id", UUID.randomUUID().toString()),
                    name = jo.optString("name", "未命名"),
                    kind = kind,
                    sourcePath = path,
                    textContent = text,
                    selected = true
                ))
            }
            _uiState.value = _uiState.value.copy(
                items = items,
                textFontSize = obj.optDouble("textFontSize", 14.0).toFloat(),
                textLineSpacing = obj.optDouble("textLineSpacing", 4.0).toFloat(),
                fontFamilyIndex = obj.optInt("fontIndex", 0),
                imageDitherMode = DitherMode.entries.getOrElse(obj.optInt("ditherMode", 1)) { DitherMode.FLOYD_STEINBERG },
                imageThreshold = obj.optInt("threshold", 128),
                thickness = obj.optInt("thickness", 0).takeIf { it > 0 }
            )
        } catch (e: Exception) {
            Timber.tag("BatchVM").w(e, "restoreFromHistoryPayload failed")
        }
    }

    /** 读取文本文件内容，UTF-8 优先，失败时按 GBK 兜底 */
    private fun readTextFile(path: String): String? {
        return try {
            val bytes = File(path).readBytes()
            val utf8 = Charset.forName("UTF-8")
            val gbk = Charset.forName("GBK")
            val decoded = bytes.toString(utf8)
            // UTF-8 严格校验：解码产生替换符说明不是合法 UTF-8，改用 GBK
            val hasReplacement = decoded.contains('�')
            if (hasReplacement) bytes.toString(gbk) else decoded
        } catch (e: Exception) {
            Timber.tag("BatchVM").w(e, "readTextFile failed: $path")
            null
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) ?: uri.lastPathSegment ?: "文件" else uri.lastPathSegment ?: "文件"
                } else uri.lastPathSegment ?: "文件"
            } ?: uri.lastPathSegment ?: "文件"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "文件"
        }
    }

    /** 添加文字文件（txt / md），拷到内部存储并读取内容 */
    fun addTextFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val name = displayName(app, uri)
                    val path = historyRepo.copyUriToInternal(uri.toString(), "batch_text", "t_")
                        ?: return@mapNotNull null
                    val content = readTextFile(path) ?: return@mapNotNull null
                    BatchItem(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        kind = BatchItemKind.TEXT,
                        sourcePath = path,
                        textContent = content,
                        selected = true
                    )
                }
            }
            _uiState.value = _uiState.value.copy(items = _uiState.value.items + added)
        }
    }

    /** 添加图片文件，拷到内部存储 */
    fun addImageUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val name = displayName(app, uri)
                    val path = historyRepo.copyUriToInternal(uri.toString(), "batch_images", "i_")
                        ?: return@mapNotNull null
                    BatchItem(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        kind = BatchItemKind.IMAGE,
                        sourcePath = path,
                        selected = true
                    )
                }
            }
            _uiState.value = _uiState.value.copy(items = _uiState.value.items + added)
        }
    }

    fun removeItem(id: String) {
        _uiState.value = _uiState.value.copy(items = _uiState.value.items.filterNot { it.id == id })
    }

    fun toggleSelected(id: String) {
        _uiState.value = _uiState.value.copy(
            items = _uiState.value.items.map {
                if (it.id == id) it.copy(selected = !it.selected) else it
            }
        )
    }

    fun setAllSelected(all: Boolean) {
        _uiState.value = _uiState.value.copy(
            items = _uiState.value.items.map { it.copy(selected = all) }
        )
    }

    fun setTextFontSize(v: Float) { _uiState.value = _uiState.value.copy(textFontSize = v) }
    fun setTextLineSpacing(v: Float) { _uiState.value = _uiState.value.copy(textLineSpacing = v) }
    fun setFontFamilyIndex(i: Int) {
        val families = _uiState.value.fontFamilies
        if (i in families.indices) _uiState.value = _uiState.value.copy(fontFamilyIndex = i)
    }
    fun setImageDitherMode(m: DitherMode) { _uiState.value = _uiState.value.copy(imageDitherMode = m) }
    fun setImageThreshold(t: Int) { _uiState.value = _uiState.value.copy(imageThreshold = t) }
    fun setThickness(t: Int?) { _uiState.value = _uiState.value.copy(thickness = t) }

    private fun textOptions(st: BatchPrintUiState): MarkdownOptions {
        return MarkdownOptions(
            fontSize = st.textFontSize,
            lineSpacing = st.textLineSpacing,
            margin = 8f,
            fontFamilyIndex = st.fontFamilyIndex,
            fontFamilies = st.fontFamilies
        )
    }

    private fun imageOptions(st: BatchPrintUiState): ImagePrintOptions {
        return ImagePrintOptions(
            ditherMode = st.imageDitherMode,
            threshold = st.imageThreshold,
            rotation = 0,
            flipH = false,
            flipV = false,
            invert = false
        )
    }

    /** 渲染一个文字项并按高度分块，逐块打印 */
    private suspend fun printTextItem(item: BatchItem, st: BatchPrintUiState): PrintResult {
        val opts = textOptions(st)
        val lines = layoutMarkdown(item.textContent, opts)
        if (lines.isEmpty()) return PrintResult(false, "内容为空")

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
                val raster = bitmapToRasterStreamed(bmp, 211)
                val r = withContext(Dispatchers.IO) {
                    printerConnection.printRaster(raster, 1)
                }
                if (!r.ok) return r
            } finally {
                bmp.recycle()
            }
        }
        return PrintResult(true, "打印完成")
    }

    /** 渲染一个图片项并打印（用预设抖动参数） */
    private suspend fun printImageItem(item: BatchItem, st: BatchPrintUiState): PrintResult {
        return try {
            val gray = withContext(Dispatchers.Default) {
                val bmp = decodeSourceToPrintWidth(app, item.sourcePath)
                val g = bitmapToGray(bmp)
                bmp.recycle()
                g
            }
            val (binary, w, h) = withContext(Dispatchers.Default) {
                transformToBinary(gray, imageOptions(st))
            }
            val raster = withContext(Dispatchers.Default) {
                packBinaryToRaster(binary, w, h)
            }
            withContext(Dispatchers.IO) {
                printerConnection.printRaster(raster, st.thickness)
            }
        } catch (e: Exception) {
            Timber.tag("BatchVM").w(e, "printImageItem failed: ${item.name}")
            PrintResult(false, "图片处理失败：${e.message}")
        }
    }

    /** 逐项打印：单项失败记录并继续，最后汇总 */
    fun print() {
        val st = _uiState.value
        if (st.printing) return
        val targets = st.items.filter { it.selected }
        if (targets.isEmpty()) {
            _uiState.value = _uiState.value.copy(resultOk = false, resultMessage = "请先勾选要打印的项目")
            return
        }
        if (printerStatus.value.connState != ConnState.CONNECTED) {
            _uiState.value = _uiState.value.copy(resultOk = false, resultMessage = "请先在首页连接打印机")
            return
        }

        _uiState.value = _uiState.value.copy(
            printing = true,
            resultMessage = "",
            progressIndex = 0,
            progressTotal = targets.size,
            progressName = ""
        )

        viewModelScope.launch {
            var thumbBitmap: Bitmap? = null
            var okCount = 0
            val failures = mutableListOf<String>()
            try {
                val fault = withContext(Dispatchers.IO) { printerConnection.preflightCheck() }
                if (fault != null) {
                    _uiState.value = _uiState.value.copy(printing = false, resultOk = false, resultMessage = fault)
                    return@launch
                }

                for ((idx, item) in targets.withIndex()) {
                    _uiState.value = _uiState.value.copy(
                        progressIndex = idx + 1,
                        progressName = item.name
                    )
                    val r = when (item.kind) {
                        BatchItemKind.TEXT -> printTextItem(item, _uiState.value)
                        BatchItemKind.IMAGE -> printImageItem(item, _uiState.value)
                    }
                    if (r.ok) {
                        okCount++
                        if (thumbBitmap == null && item.kind == BatchItemKind.IMAGE) {
                            // 首张图片生成缩略图（尽力而为）
                            try {
                                val bmp = withContext(Dispatchers.Default) {
                                    val src = decodeSourceToPrintWidth(app, item.sourcePath)
                                    val g = bitmapToGray(src)
                                    src.recycle()
                                    val (binary, w, h) = transformToBinary(g, imageOptions(_uiState.value))
                                    binaryToPreviewBmp(binary, w, h)
                                }
                                thumbBitmap = Bitmap.createScaledBitmap(
                                    bmp, 200, Math.round(200f * bmp.height / bmp.width), true
                                )
                                bmp.recycle()
                            } catch (e: Exception) { }
                        }
                    } else {
                        failures.add("${item.name}：${r.message}")
                    }
                }

                if (failures.isEmpty()) {
                    // 保存历史
                    try {
                        val arr = JSONArray()
                        targets.forEach { it ->
                            arr.put(JSONObject().apply {
                                put("id", it.id)
                                put("name", it.name)
                                put("kind", if (it.kind == BatchItemKind.IMAGE) "IMAGE" else "TEXT")
                                put("path", it.sourcePath)
                            })
                        }
                        val payload = JSONObject().apply {
                            put("items", arr)
                            put("textFontSize", _uiState.value.textFontSize.toDouble())
                            put("textLineSpacing", _uiState.value.textLineSpacing.toDouble())
                            put("fontIndex", _uiState.value.fontFamilyIndex)
                            put("ditherMode", _uiState.value.imageDitherMode.code)
                            put("threshold", _uiState.value.imageThreshold)
                            put("thickness", _uiState.value.thickness ?: 0)
                        }.toString()
                        historyRepo.saveHistory(HIST_TYPE_BATCH, thumbBitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), payload)
                    } catch (e: Exception) {
                        Timber.tag("BatchVM").w(e, "saveHistory failed")
                    }
                    _uiState.value = _uiState.value.copy(
                        printing = false,
                        progressName = "",
                        resultOk = true,
                        resultMessage = "批量打印完成：成功 $okCount 项"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        printing = false,
                        progressName = "",
                        resultOk = false,
                        resultMessage = "完成 $okCount 项，失败 ${failures.size} 项：${failures.take(3).joinToString("；")}"
                    )
                }
            } catch (e: Exception) {
                Timber.tag("BatchVM").e(e, "print failed")
                _uiState.value = _uiState.value.copy(
                    printing = false,
                    progressName = "",
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

    private fun binaryToPreviewBmp(binary: ByteArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        for (i in 0 until w * h) {
            val black = binary.getOrElse(i) { 0 }.toInt() == 1
            val v = if (black) 0 else 255
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun clearResult() {
        _uiState.value = _uiState.value.copy(resultMessage = "", resultOk = false)
    }
}
