package com.qring.printer.ui.wordbook

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.printer.bt.PrintResult
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.data.HistoryPayloadHolder
import com.qring.printer.data.HistoryRepository
import com.qring.printer.data.VocabWord
import com.qring.printer.data.WordbookRepository
import com.qring.printer.model.ConnState
import com.qring.printer.model.HIST_TYPE_WORDBOOK
import com.qring.printer.model.PrinterStatus
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.bitmapToRasterStreamed
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
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

data class WordbookUiState(
    val books: List<WordbookBookUi> = emptyList(),
    val selectedBookId: String = "",
    val downloadingBookId: String? = null, // 正在下载的书（每本书各自显示进度）
    val downloadProgress: Float = 0f,
    val wordsToPrint: List<VocabWord> = emptyList(),
    val previewBitmap: Bitmap? = null,
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false,
    // 打印选项
    val wordCount: Int = 20,          // 每次打印词数
    val currentProgress: Int = 0,     // 当前进度
    val totalWords: Int = 0,          // 总词数
    val showChinese: Boolean = true,  // 打印中文释义
    val showPos: Boolean = true,      // 打印词性
    val showPhrases: Boolean = false, // 打印短语
    val hideMode: Boolean = false,    // 默写模式：隐藏中文，留空行
    val fontSize: Float = 18f,
    val lineSpacing: Float = 14f,     // 行距（点）
    val fontFamilyIndex: Int = 0,
    val fontFamilies: List<String> = listOf("sans-serif", "serif", "monospace"),
    val thickness: Int = 1,
    val leftMargin: Float = 8f,      // 左边距（点）
)

data class WordbookBookUi(
    val id: String,
    val name: String,
    val downloaded: Boolean,
    val totalWords: Int,
    val progress: Int,
)

class WordbookViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = WordbookRepository(application)
    private val printerConnection = PrinterConnection.getInstance()
    private val historyRepo = HistoryRepository(application)

    /** 预览超长时缩到的最大高度（点），降低 GPU 上传与内存 */
    private val PREVIEW_MAX_HEIGHT = 1600

    /** 单个打印分块的最大高度（点）。超出会分成多段打印，避免单次渲染大位图 OOM */
    private val PRINT_CHUNK_HEIGHT = 6000f

    /** 预览任务句柄：每次更新先取消上一次，滑杆拖动时只保留最后一次渲染 */
    private var previewJob: Job? = null

    /** 预览任务代际号：被更新的任务取代时，自己把位图回收掉 */
    private var previewGeneration = 0

    private val _uiState = MutableStateFlow(WordbookUiState())
    val uiState: StateFlow<WordbookUiState> = _uiState.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    init {
        refreshBooks()
        val fonts = FontList.getSystemFonts(getApplication())
        _uiState.value = _uiState.value.copy(
            fontFamilies = fonts
        )
        restoreFromHistoryPayload()
    }

    /** 从历史记录重打时恢复单词本 + 打印选项 */
    private fun restoreFromHistoryPayload() {
        val (type, payload) = HistoryPayloadHolder.consumePayload() ?: return
        if (type != HIST_TYPE_WORDBOOK) {
            // 不是自己的类型，放回给其他页面消费
            HistoryPayloadHolder.setPayload(type, payload)
            return
        }
        try {
            val obj = org.json.JSONObject(payload)
            val bookId = obj.optString("bookId", "")
            if (bookId.isEmpty() || !repo.isDownloaded(bookId)) return
            val progress = obj.optInt("progress", 0).coerceIn(0, repo.getWordCount(bookId))
            _uiState.value = _uiState.value.copy(
                selectedBookId = bookId,
                totalWords = repo.getWordCount(bookId),
                currentProgress = progress,
                wordCount = obj.optInt("wordCount", 20),
                fontSize = obj.optDouble("fontSize", 18.0).toFloat(),
                lineSpacing = obj.optDouble("lineSpacing", 14.0).toFloat(),
                leftMargin = obj.optDouble("leftMargin", 8.0).toFloat(),
                fontFamilyIndex = obj.optInt("fontFamilyIndex", 0),
                showChinese = obj.optBoolean("showChinese", true),
                showPos = obj.optBoolean("showPos", true),
                showPhrases = obj.optBoolean("showPhrases", false),
                hideMode = obj.optBoolean("hideMode", false)
            )
            updatePreview()
        } catch (e: Exception) {
            Timber.tag("WordbookVM").w(e, "restoreFromHistoryPayload failed")
        }
    }

    fun refreshBooks() {
        val books = repo.availableBooks.map { book ->
            val downloaded = repo.isDownloaded(book.id)
            val total = if (downloaded) repo.getWordCount(book.id) else 0
            val progress = repo.getProgress(book.id)
            WordbookBookUi(book.id, book.name, downloaded, total, progress)
        }
        _uiState.value = _uiState.value.copy(books = books)
    }

    fun selectBook(bookId: String) {
        val total = if (repo.isDownloaded(bookId)) repo.getWordCount(bookId) else 0
        val progress = repo.getProgress(bookId)
        _uiState.value = _uiState.value.copy(
            selectedBookId = bookId,
            totalWords = total,
            currentProgress = progress
        )
        updatePreview()
    }

    fun downloadBook(bookId: String) {
        val book = repo.availableBooks.find { it.id == bookId } ?: return
        if (_uiState.value.downloadingBookId != null) return // 同一时间只允许一个下载
        _uiState.value = _uiState.value.copy(downloadingBookId = bookId, downloadProgress = 0f)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                repo.download(book) { progress ->
                    _uiState.value = _uiState.value.copy(downloadProgress = progress)
                }
            }
            _uiState.value = _uiState.value.copy(downloadingBookId = null, downloadProgress = 0f)
            if (ok) {
                refreshBooks()
                // 下载期间用户可能切走了：只在仍停留在该书时刷新预览
                if (_uiState.value.selectedBookId == bookId) updatePreview()
                _uiState.value = _uiState.value.copy(
                    resultOk = true,
                    resultMessage = "下载完成，共 ${repo.getWordCount(bookId)} 词"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    resultOk = false,
                    resultMessage = "下载失败，请检查网络"
                )
            }
        }
    }

    fun deleteBook(bookId: String) {
        val wasSelected = _uiState.value.selectedBookId == bookId
        repo.delete(bookId)
        if (wasSelected) {
            // 删掉正在选中的书：清空选择与待打印词，避免拿旧数据打印
            val old = _uiState.value.previewBitmap
            _uiState.value = _uiState.value.copy(
                selectedBookId = "",
                totalWords = 0,
                currentProgress = 0,
                wordsToPrint = emptyList(),
                previewBitmap = null
            )
            old?.recycle()
        }
        refreshBooks()
    }

    fun setWordCount(count: Int) {
        _uiState.value = _uiState.value.copy(wordCount = count)
        updatePreview()
    }

    fun toggleShowChinese() {
        _uiState.value = _uiState.value.copy(showChinese = !_uiState.value.showChinese)
        updatePreview()
    }

    fun toggleShowPos() {
        _uiState.value = _uiState.value.copy(showPos = !_uiState.value.showPos)
        updatePreview()
    }

    fun toggleShowPhrases() {
        _uiState.value = _uiState.value.copy(showPhrases = !_uiState.value.showPhrases)
        updatePreview()
    }

    fun toggleHideMode() {
        _uiState.value = _uiState.value.copy(hideMode = !_uiState.value.hideMode)
        updatePreview()
    }

    fun setFontSize(size: Float) {
        _uiState.value = _uiState.value.copy(fontSize = size)
        updatePreview()
    }

    fun setFontFamilyIndex(index: Int) {
        _uiState.value = _uiState.value.copy(fontFamilyIndex = index)
        updatePreview()
    }

    fun setLeftMargin(margin: Float) {
        _uiState.value = _uiState.value.copy(leftMargin = margin)
        updatePreview()
    }

    fun setLineSpacing(spacing: Float) {
        _uiState.value = _uiState.value.copy(lineSpacing = spacing)
        updatePreview()
    }

    fun resetProgress() {
        val bookId = _uiState.value.selectedBookId
        if (bookId.isEmpty()) return
        repo.resetProgress(bookId)
        _uiState.value = _uiState.value.copy(currentProgress = 0)
        updatePreview()
    }

    fun setProgress(progress: Int) {
        val bookId = _uiState.value.selectedBookId
        if (bookId.isNotEmpty()) {
            repo.saveProgress(bookId, progress)
        }
        _uiState.value = _uiState.value.copy(currentProgress = progress)
        updatePreview()
    }

    /** 生成要打印的单词列表（预览用） */
    private fun loadWordsForPrint(): List<VocabWord> {
        val state = _uiState.value
        val bookId = state.selectedBookId
        if (bookId.isEmpty() || !repo.isDownloaded(bookId)) return emptyList()
        return repo.loadWords(bookId, state.currentProgress, state.wordCount)
    }

    /** 单个单词块占用的行高（点）。高度预估与实际绘制共用，避免两者不一致导致内容被裁 */
    private fun wordBlockHeight(state: WordbookUiState, word: VocabWord): Float {
        val lineH = state.fontSize + state.lineSpacing
        var h = lineH // 单词行
        // 释义行（默写模式画空行，普通模式画中文释义，二选一）
        h += if (state.hideMode || state.showChinese) lineH else 0f
        // 短语行（最多 2 行，每行 0.8 倍行高）
        if (state.showPhrases && !state.hideMode) {
            h += minOf(2, word.phrases.size) * lineH * 0.8f
        }
        h += lineH * 0.5f // 词间分隔空隙
        return h
    }

    /** 生成预览 */
    fun updatePreview() {
        val state = _uiState.value
        if (state.selectedBookId.isEmpty() || !repo.isDownloaded(state.selectedBookId)) return
        // 打印中不更新预览，避免并发创建大 Bitmap 导致 OOM
        if (state.printing) return

        // 取消上一次预览任务：滑杆拖动只保留最后一次渲染，不排队
        previewJob?.cancel()

        // holder 保证任何退出路径（成功 / 被取代 / 取消 / 异常）都不会泄漏位图
        val holder = AtomicReference<Bitmap?>(null)
        previewJob = viewModelScope.launch {
            val gen = ++previewGeneration
            try {
                val result = withContext(Dispatchers.Default) {
                    val ws = loadWordsForPrint()
                    val bmp = renderWordbookBitmap(ws, state)
                    if (bmp.height > PREVIEW_MAX_HEIGHT) {
                        // 预览只需展示排版效果，超长内容缩到有限高度，降低 GPU 上传与内存
                        val nh = PREVIEW_MAX_HEIGHT
                        val nw = maxOf(1, Math.round(bmp.width.toFloat() * nh / bmp.height))
                        val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
                        bmp.recycle() // 原图由本分支回收
                        holder.set(scaled)
                        ws to scaled
                    } else {
                        holder.set(bmp)
                        ws to bmp
                    }
                }
                if (gen != previewGeneration) {
                    // 已被更新的任务取代，自己把结果回收掉
                    result.second.recycle()
                    holder.set(null)
                    return@launch
                }
                val old = _uiState.value.previewBitmap
                _uiState.value = _uiState.value.copy(wordsToPrint = result.first, previewBitmap = result.second)
                holder.set(null)
                old?.let { if (it != result.second) { delay(150); it.recycle() } }
            } catch (e: CancellationException) {
                holder.getAndSet(null)?.recycle()
                throw e
            } catch (e: Exception) {
                holder.getAndSet(null)?.recycle()
                Timber.tag("WordbookVM").w(e, "updatePreview failed")
            }
        }
    }

    /** 把单词列表渲染成可打印的 Bitmap */
    private fun renderWordbookBitmap(words: List<VocabWord>, state: WordbookUiState): Bitmap {
        val margin = state.leftMargin
        val usable = WIDTH_DOTS - margin * 2
        // 释义行有 12 点缩进，可用宽度按绘制起点重新计算，避免右缘溢出被裁
        val cnLeft = margin + 12f
        val cnUsable = WIDTH_DOTS - cnLeft - margin
        val lineH = state.fontSize + state.lineSpacing

        val family = state.fontFamilies.getOrElse(state.fontFamilyIndex) { "sans-serif" }
        val baseTypeface = FontList.typefaceFor(family, false, false)

        // 用两套 Paint：英文和中文
        val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = state.fontSize
            color = android.graphics.Color.BLACK
            typeface = Typeface.create(baseTypeface, Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }
        val cnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (state.fontSize * 0.8f)
            color = android.graphics.Color.BLACK
            typeface = baseTypeface
            textAlign = Paint.Align.LEFT
        }

        // 首行基线要按 ascent 修正，否则字形顶部被位图上边界裁掉；
        // 底部留出 descent，最后一行内容不会被裁。
        val fm = wordPaint.fontMetrics
        val topPad = margin - fm.ascent
        val bottomPad = margin + fm.descent

        // 计算总高度：高度预估与绘制循环共用 wordBlockHeight，保证一致
        var hFloat = topPad + bottomPad
        for (word in words) {
            hFloat += wordBlockHeight(state, word)
        }
        val totalHeight = maxOf(100, Math.round(hFloat))
        val width = WIDTH_DOTS

        val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        var y = topPad
        var index = 1

        for (word in words) {
            // 序号 + 单词
            val numStr = "$index. "
            val wordStr = word.word

            // 词性：跳过空词性，避免打出孤立的 " ."
            val posStr = if (state.showPos) {
                word.translations.firstOrNull { it.first.isNotEmpty() }?.first?.let { " $it." } ?: ""
            } else ""

            // 单词行超宽时截断，不静默裁掉
            val wordLine = numStr + wordStr + posStr
            if (wordPaint.measureText(wordLine) > usable) {
                canvas.drawText(truncateText(wordLine, wordPaint, usable), margin, y, wordPaint)
            } else {
                canvas.drawText(wordLine, margin, y, wordPaint)
            }
            y += lineH

            if (state.hideMode) {
                // 默写模式：画一条下划线供填写
                val underlineY = y - lineH * 0.3f
                val linePaint = Paint().apply {
                    color = android.graphics.Color.argb(80, 0, 0, 0)
                    strokeWidth = 1f
                }
                canvas.drawLine(margin, underlineY, width - margin, underlineY, linePaint)
                y += lineH
            } else if (state.showChinese) {
                // 中文释义（按缩进后的可用宽度截断，避免右缘溢出）
                val translations = word.translations.joinToString("；") { it.second }
                if (cnPaint.measureText(translations) > cnUsable) {
                    canvas.drawText(truncateText(translations, cnPaint, cnUsable), cnLeft, y, cnPaint)
                } else {
                    canvas.drawText(translations, cnLeft, y, cnPaint)
                }
                y += lineH
            }

            // 短语
            if (state.showPhrases && !state.hideMode) {
                for (phrase in word.phrases.take(2)) {
                    val phraseText = "  ${phrase.first} ${phrase.second}"
                    val txt = if (cnPaint.measureText(phraseText) > cnUsable) {
                        truncateText(phraseText, cnPaint, cnUsable)
                    } else phraseText
                    canvas.drawText(txt, cnLeft, y, cnPaint)
                    y += lineH * 0.8f
                }
            }

            // 词间分隔线
            val sepPaint = Paint().apply {
                color = android.graphics.Color.argb(30, 0, 0, 0)
                strokeWidth = 0.5f
            }
            canvas.drawLine(margin, y - lineH * 0.25f, width - margin, y - lineH * 0.25f, sepPaint)
            y += lineH * 0.5f

            index++
        }

        return bitmap
    }

    /** 截断文字到指定宽度 */
    private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }

    fun print() {
        val state = _uiState.value
        if (state.printing) return
        if (printerStatus.value.connState != ConnState.CONNECTED) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请先在首页连接打印机"
            )
            return
        }
        if (state.selectedBookId.isEmpty() || !repo.isDownloaded(state.selectedBookId)) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请先选择单词本"
            )
            return
        }

        _uiState.value = _uiState.value.copy(printing = true, resultMessage = "")

        // 打印前释放预览 Bitmap，降低内存峰值
        val previewBmp = _uiState.value.previewBitmap
        if (previewBmp != null) {
            _uiState.value = _uiState.value.copy(previewBitmap = null)
            previewBmp.recycle()
        }

        viewModelScope.launch {
            var thumbBitmap: Bitmap? = null
            var printedWords = 0
            try {
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

                val result = withContext(Dispatchers.Default) {
                    // 打印前重新加载单词，而不是用可能过期的 wordsToPrint，
                    // 避免「预览未完成/切换书本后立即打印」导致内容和进度错位
                    val words = repo.loadWords(state.selectedBookId, state.currentProgress, state.wordCount)
                    if (words.isEmpty()) {
                        return@withContext PrintResult(false, "当前进度下没有可打印的单词")
                    }

                    // 按高度分块：控制单次渲染/发送的内存与单任务时长
                    val chunks = mutableListOf<List<VocabWord>>()
                    var cur = mutableListOf<VocabWord>()
                    var curH = 0f
                    for (w in words) {
                        val h = wordBlockHeight(state, w)
                        if (cur.isNotEmpty() && curH + h > PRINT_CHUNK_HEIGHT) {
                            chunks.add(cur)
                            cur = mutableListOf()
                            curH = 0f
                        }
                        cur.add(w)
                        curH += h
                    }
                    if (cur.isNotEmpty()) chunks.add(cur)

                    for (chunk in chunks) {
                        val bmp = renderWordbookBitmap(chunk, state)
                        try {
                            if (thumbBitmap == null) {
                                thumbBitmap = Bitmap.createScaledBitmap(
                                    bmp, 200, Math.round(200f * bmp.height / bmp.width), true
                                )
                            }
                            val raster = bitmapToRasterStreamed(bmp, 212)
                            val r = withContext(Dispatchers.IO) {
                                printerConnection.printRaster(raster, state.thickness.takeIf { it > 0 })
                            }
                            if (!r.ok) {
                                return@withContext PrintResult(false, r.message)
                            }
                            printedWords += chunk.size
                        } finally {
                            bmp.recycle()
                        }
                    }
                    PrintResult(true, "打印完成")
                }

                // 更新进度（部分分块失败时也保存已打印的数量，避免重复打印）
                if (result.ok || printedWords > 0) {
                    val newProgress = state.currentProgress + printedWords
                    repo.saveProgress(state.selectedBookId, newProgress)
                    _uiState.value = _uiState.value.copy(currentProgress = newProgress)
                }

                if (result.ok) {
                    // 保存历史（含打印选项，历史页可完整还原）
                    try {
                        val payload = org.json.JSONObject().apply {
                            put("type", "wordbook")
                            put("bookId", state.selectedBookId)
                            put("wordCount", state.wordCount)
                            put("progress", state.currentProgress)
                            put("fontSize", state.fontSize)
                            put("lineSpacing", state.lineSpacing)
                            put("leftMargin", state.leftMargin)
                            put("fontFamilyIndex", state.fontFamilyIndex)
                            put("showChinese", state.showChinese)
                            put("showPos", state.showPos)
                            put("showPhrases", state.showPhrases)
                            put("hideMode", state.hideMode)
                        }.toString()
                        historyRepo.saveHistory(HIST_TYPE_WORDBOOK, thumbBitmap!!, payload)
                    } catch (e: Exception) {
                        Timber.tag("WordbookVM").w(e, "saveHistory failed")
                    }
                    _uiState.value = _uiState.value.copy(
                        printing = false,
                        resultOk = true,
                        resultMessage = "打印完成，进度已保存到 ${_uiState.value.currentProgress}/${state.totalWords}"
                    )
                    refreshBooks()
                    updatePreview()
                } else {
                    _uiState.value = _uiState.value.copy(
                        printing = false,
                        resultOk = false,
                        resultMessage = result.message
                    )
                }
            } catch (e: Exception) {
                Timber.tag("WordbookVM").e(e, "print failed")
                _uiState.value = _uiState.value.copy(
                    printing = false,
                    resultOk = false,
                    resultMessage = "打印失败：${e.message}"
                )
            } finally {
                // 确保 Bitmap 被回收，避免内存泄漏
                try { thumbBitmap?.recycle() } catch (e: Exception) { }
                // 确保 printing 状态重置
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
    }
}
