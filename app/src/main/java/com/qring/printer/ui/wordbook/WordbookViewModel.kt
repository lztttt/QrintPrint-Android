﻿package com.qring.printer.ui.wordbook

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.data.HistoryRepository
import com.qring.printer.data.WordbookRepository
import com.qring.printer.data.VocabWord
import com.qring.printer.model.ConnState
import com.qring.printer.model.HIST_TYPE_TEXT
import com.qring.printer.model.PrinterStatus
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.bitmapToRaster
import com.qring.printer.ui.common.FontList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber

data class WordbookUiState(
    val books: List<WordbookBookUi> = emptyList(),
    val selectedBookId: String = "",
    val downloading: Boolean = false,
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

    private val _uiState = MutableStateFlow(WordbookUiState())
    val uiState: StateFlow<WordbookUiState> = _uiState.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    init {
        refreshBooks()
        val fonts = FontList.getSystemFonts(getApplication())
        _uiState.value = _uiState.value.copy(
            fontFamilies = fonts
        )
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
        _uiState.value = _uiState.value.copy(downloading = true, downloadProgress = 0f)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                repo.download(book) { progress ->
                    _uiState.value = _uiState.value.copy(downloadProgress = progress)
                }
            }
            _uiState.value = _uiState.value.copy(downloading = false, downloadProgress = 0f)
            if (ok) {
                refreshBooks()
                selectBook(bookId)
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
        repo.delete(bookId)
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

    /** 生成要打印的单词列表 */
    private fun loadWordsForPrint(): List<VocabWord> {
        val state = _uiState.value
        val bookId = state.selectedBookId
        if (bookId.isEmpty() || !repo.isDownloaded(bookId)) return emptyList()
        return repo.loadWords(bookId, state.currentProgress, state.wordCount)
    }

    /** 生成预览 */
    fun updatePreview() {
        val state = _uiState.value
        if (state.selectedBookId.isEmpty() || !repo.isDownloaded(state.selectedBookId)) return
        // 打印中不更新预览，避免并发创建大 Bitmap 导致 OOM
        if (state.printing) return

        viewModelScope.launch {
            try {
                val words = withContext(Dispatchers.Default) { loadWordsForPrint() }
                _uiState.value = _uiState.value.copy(wordsToPrint = words)

                val bitmap = withContext(Dispatchers.Default) {
                    renderWordbookBitmap(words, state)
                }
                val old = _uiState.value.previewBitmap
                _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
                old?.let { if (it != bitmap) { delay(150); it.recycle() } }
            } catch (e: Exception) {
                Timber.tag("WordbookVM").w(e, "updatePreview failed")
            }
        }
    }

    /** 把单词列表渲染成可打印的 Bitmap */
    private fun renderWordbookBitmap(words: List<VocabWord>, state: WordbookUiState): Bitmap {
        val margin = state.leftMargin
        val usable = WIDTH_DOTS - margin * 2

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

        // 行距：用户可调的额外行距 + 字号本身的高度
        val lineSpacing = state.fontSize + state.lineSpacing
        val wordGap = state.fontSize * 0.5f  // 单词和释义之间的间距

        // 每个单词占的行数：单词行 + (释义行 or 空行)
        val linesPerWord = if (state.hideMode) 1 else if (state.showChinese) 2 else 1
        if (state.showPhrases) {
            // 额外的短语行
        }

        // 计算总高度
        var totalHeight = margin.toInt() * 2
        for (word in words) {
            totalHeight += lineSpacing.toInt() // 单词行
            if (!state.hideMode && state.showChinese) {
                totalHeight += lineSpacing.toInt() // 释义行
            }
            if (state.hideMode) {
                totalHeight += lineSpacing.toInt() // 默写空行
            }
            totalHeight += (lineSpacing * 0.5f).toInt() // 词间间隔
        }

        totalHeight = maxOf(totalHeight, 100)
        val width = WIDTH_DOTS

        val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        var y = margin
        var index = 1

        for (word in words) {
            // 序号 + 单词
            val numStr = "$index. "
            val wordStr = word.word

            // 词性
            val posStr = if (state.showPos && word.translations.isNotEmpty()) {
                " ${word.translations[0].first}."
            } else ""

            canvas.drawText(numStr + wordStr + posStr, margin, y, wordPaint)
            y += lineSpacing

            if (state.hideMode) {
                // 默写模式：画一条下划线供填写
                val underlineY = y - lineSpacing * 0.3f
                val linePaint = Paint().apply {
                    color = android.graphics.Color.argb(80, 0, 0, 0)
                    strokeWidth = 1f
                }
                canvas.drawLine(margin, underlineY, width - margin, underlineY, linePaint)
                y += lineSpacing
            } else if (state.showChinese) {
                // 中文释义
                val translations = word.translations.joinToString("；") { it.second }
                // 如果太长，换行
                val cnText = translations
                val cnWidth = cnPaint.measureText(cnText)
                if (cnWidth > usable) {
                    // 简单截断
                    val truncated = truncateText(cnText, cnPaint, usable)
                    canvas.drawText(truncated, margin + 12, y, cnPaint)
                } else {
                    canvas.drawText(cnText, margin + 12, y, cnPaint)
                }
                y += lineSpacing
            }

            // 短语
            if (state.showPhrases && !state.hideMode) {
                for (phrase in word.phrases.take(2)) {
                    val phraseText = "  ${phrase.first} ${phrase.second}"
                    val pw = cnPaint.measureText(phraseText)
                    val txt = if (pw > usable) truncateText(phraseText, cnPaint, usable) else phraseText
                    canvas.drawText(txt, margin + 12, y, cnPaint)
                    y += lineSpacing * 0.8f
                }
            }

            // 词间分隔线
            val sepPaint = Paint().apply {
                color = android.graphics.Color.argb(30, 0, 0, 0)
                strokeWidth = 0.5f
            }
            canvas.drawLine(margin, y - lineSpacing * 0.25f, width - margin, y - lineSpacing * 0.25f, sepPaint)
            y += lineSpacing * 0.5f

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
        if (state.wordsToPrint.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请先选择单词本和要打印的词数"
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
            var printBitmap: Bitmap? = null
            var thumbBitmap: Bitmap? = null
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
                    printBitmap = renderWordbookBitmap(state.wordsToPrint, state)
                    val raster = bitmapToRaster(printBitmap!!, 212)

                    thumbBitmap = Bitmap.createScaledBitmap(
                        printBitmap!!, 200, Math.round(200f * printBitmap!!.height / printBitmap!!.width), true
                    )

                    val printResult = withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, state.thickness.takeIf { it > 0 })
                    }

                    // 保存历史
                    if (printResult.ok) {
                        try {
                            val payload = org.json.JSONObject().apply {
                                put("type", "wordbook")
                                put("bookId", state.selectedBookId)
                                put("wordCount", state.wordCount)
                                put("progress", state.currentProgress)
                            }.toString()
                            historyRepo.saveHistory(HIST_TYPE_TEXT, thumbBitmap!!, payload)
                        } catch (e: Exception) {
                            Timber.tag("WordbookVM").w(e, "saveHistory failed")
                        }
                    }

                    printResult
                }

                // 更新进度
                if (result.ok) {
                    val newProgress = state.currentProgress + state.wordsToPrint.size
                    repo.saveProgress(state.selectedBookId, newProgress)
                    _uiState.value = _uiState.value.copy(
                        currentProgress = newProgress,
                        printing = false,
                        resultOk = true,
                        resultMessage = "打印完成，进度已保存到 ${newProgress}/${state.totalWords}"
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
                try { printBitmap?.recycle() } catch (e: Exception) { }
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
