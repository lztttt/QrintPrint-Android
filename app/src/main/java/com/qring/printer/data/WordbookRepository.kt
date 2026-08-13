package com.qring.printer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI

/**
 * 单词条目
 */
data class VocabWord(
    val word: String,
    val translations: List<Pair<String, String>>, // (词性, 释义)
    val phrases: List<Pair<String, String>>       // (短语, 释义)
)

/**
 * 单词本元信息
 */
data class WordbookInfo(
    val id: String,           // 文件名（不含后缀）
    val name: String,         // 显示名称
    val url: String,          // 下载 URL
    val totalWords: Int = 0,  // 总词数（下载后填充）
    val localPath: String? = null  // 本地路径（已下载时填充）
)

/**
 * 单词本仓库。
 *
 * 从 GitHub 下载 JSON 单词本，保存到内部存储，并跟踪每个单词本的打印进度。
 *
 * JSON 格式（来自 KyleBing/english-vocabulary）：
 * [{ "word": "ability", "translations": [{"translation":"能力","type":"n"}], "phrases": [...] }, ...]
 */
class WordbookRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("qringprint_wordbook", Context.MODE_PRIVATE)
    private val wordbooksDir = File(context.filesDir, "wordbooks").apply { mkdirs() }

    /** 词库下载服务器 */
    private val serverBase = "https://download.116384.xyz/json"

    /** 可用单词本列表（预置） — 顺序 + 乱序 */
    val availableBooks: List<WordbookInfo> = listOf(
        WordbookInfo("1-初中-顺序", "初中 (顺序)", "$serverBase/1-初中-顺序.json"),
        WordbookInfo("1-初中-乱序", "初中 (乱序)", "$serverBase/1-初中-乱序.json"),
        WordbookInfo("2-高中-顺序", "高中 (顺序)", "$serverBase/2-高中-顺序.json"),
        WordbookInfo("2-高中-乱序", "高中 (乱序)", "$serverBase/2-高中-乱序.json"),
        WordbookInfo("3-CET4-顺序", "四级 (顺序)", "$serverBase/3-CET4-顺序.json"),
        WordbookInfo("3-CET4-乱序", "四级 (乱序)", "$serverBase/3-CET4-乱序.json"),
        WordbookInfo("4-CET6-顺序", "六级 (顺序)", "$serverBase/4-CET6-顺序.json"),
        WordbookInfo("4-CET6-乱序", "六级 (乱序)", "$serverBase/4-CET6-乱序.json"),
        WordbookInfo("5-考研-顺序", "考研 (顺序)", "$serverBase/5-考研-顺序.json"),
        WordbookInfo("5-考研-乱序", "考研 (乱序)", "$serverBase/5-考研-乱序.json"),
        WordbookInfo("6-托福-顺序", "托福 (顺序)", "$serverBase/6-托福-顺序.json"),
        WordbookInfo("6-托福-乱序", "托福 (乱序)", "$serverBase/6-托福-乱序.json"),
        WordbookInfo("7-SAT-顺序", "SAT (顺序)", "$serverBase/7-SAT-顺序.json"),
        WordbookInfo("7-SAT-乱序", "SAT (乱序)", "$serverBase/7-SAT-乱序.json"),
    )

    /** 获取已下载的单词本列表（带本地路径和词数） */
    fun downloadedBooks(): List<WordbookInfo> {
        return availableBooks.mapNotNull { book ->
            val file = File(wordbooksDir, "${book.id}.json")
            if (file.exists()) {
                book.copy(localPath = file.absolutePath, totalWords = getWordCount(book.id))
            } else {
                null
            }
        }
    }

    /** 检查某个单词本是否已下载 */
    fun isDownloaded(bookId: String): Boolean {
        return File(wordbooksDir, "$bookId.json").exists()
    }

    /** 下载单词本 */
    suspend fun download(book: WordbookInfo, onProgress: ((Float) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        // 乱序版：下载对应的顺序版，然后本地打乱
        val actualUrl = if (book.id.contains("乱序")) {
            val orderedId = book.id.replace("乱序", "顺序")
            "$serverBase/$orderedId.json"
        } else {
            book.url
        }
        val success = tryDownload(actualUrl, book.id, onProgress)
        if (success && book.id.contains("乱序")) {
            // 下载成功后，打乱 JSON 数组顺序
            shuffleLocalWordbook(book.id)
        }
        if (success) {
            val count = getWordCount(book.id)
            prefs.edit().putInt("count_${book.id}", count).apply()
            Timber.tag("WordbookRepo").d("downloaded ${book.id}: $count words")
        }
        success
    }

    /** 打乱本地词库 JSON 数组顺序（固定种子，保证重新下载后顺序一致） */
    private fun shuffleLocalWordbook(bookId: String) {
        val file = File(wordbooksDir, "$bookId.json")
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                list.add(arr.getJSONObject(i))
            }
            // 用 bookId 作为固定种子，保证每次下载后乱序顺序一致
            val rnd = java.util.Random(bookId.hashCode().toLong())
            list.shuffle(rnd)
            val shuffled = JSONArray()
            list.forEach { shuffled.put(it) }
            file.writeText(shuffled.toString())
            Timber.tag("WordbookRepo").d("shuffled $bookId: ${list.size} words (fixed seed)")
        } catch (e: Exception) {
            Timber.tag("WordbookRepo").e(e, "shuffle failed: $bookId")
        }
    }

    /** 实际下载逻辑（手动处理 HTTP 重定向） */
    private fun tryDownload(urlStr: String, bookId: String, onProgress: ((Float) -> Unit)?): Boolean {
        return try {
            var currentUrl = encodeUrl(urlStr)
            var redirectCount = 0
            val maxRedirects = 5

            var conn: HttpURLConnection
            while (true) {
                conn = currentUrl.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "QringPrint/Android")
                conn.instanceFollowRedirects = false

                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrEmpty()) {
                        Timber.tag("WordbookRepo").e("download failed: redirect without Location")
                        return false
                    }
                    redirectCount++
                    if (redirectCount > maxRedirects) {
                        Timber.tag("WordbookRepo").e("download failed: too many redirects")
                        return false
                    }
                    // 处理相对路径重定向
                    currentUrl = if (location.startsWith("http")) {
                        encodeUrl(location)
                    } else {
                        URL(currentUrl, location)
                    }
                    Timber.tag("WordbookRepo").d("redirect ($redirectCount) -> $currentUrl")
                    continue
                }

                if (code != 200) {
                    Timber.tag("WordbookRepo").e("download failed: HTTP $code")
                    conn.disconnect()
                    return false
                }
                break
            }

            val total = conn.contentLength.toFloat()
            val file = File(wordbooksDir, "$bookId.json")
            var lastProgress = 0f

            try {
                conn.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var read: Int
                        var downloaded = 0L
                        while (input.read(buf).also { read = it } > 0) {
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0 && onProgress != null) {
                                val progress = downloaded / total
                                if (progress - lastProgress > 0.05f || progress >= 1f) {
                                    lastProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }
                true
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Timber.tag("WordbookRepo").e(e, "download failed: $urlStr")
            false
        }
    }

    /** 编码 URL 中的非 ASCII 字符（如中文文件名） */
    private fun encodeUrl(urlStr: String): URL {
        val raw = URL(urlStr)
        // 如果路径全是 ASCII，直接返回
        if (raw.path.all { it.toInt() < 128 }) return raw
        // 分段编码路径中的非 ASCII 字符
        val encodedPath = raw.path.split("/").joinToString("/") { seg ->
            if (seg.isEmpty()) seg
            else {
                val sb = StringBuilder()
                for (ch in seg) {
                    when {
                        ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~' -> sb.append(ch)
                        else -> {
                            for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                                sb.append(String.format("%%%02X", b))
                            }
                        }
                    }
                }
                sb.toString()
            }
        }
        return URL(raw.protocol, raw.host, raw.port, encodedPath)
    }

    /** 删除已下载的单词本 */
    fun delete(bookId: String) {
        File(wordbooksDir, "$bookId.json").delete()
        prefs.edit().remove("count_$bookId").remove("progress_$bookId").apply()
    }

    /** 获取单词本的词数 */
    fun getWordCount(bookId: String): Int {
        val cached = prefs.getInt("count_$bookId", -1)
        if (cached >= 0) return cached

        val file = File(wordbooksDir, "$bookId.json")
        if (!file.exists()) return 0

        return try {
            val json = file.readText()
            val arr = JSONArray(json)
            val count = arr.length()
            prefs.edit().putInt("count_$bookId", count).apply()
            count
        } catch (e: Exception) {
            0
        }
    }

    /** 读取单词本中指定范围的单词 */
    fun loadWords(bookId: String, offset: Int, limit: Int): List<VocabWord> {
        val file = File(wordbooksDir, "$bookId.json")
        if (!file.exists()) return emptyList()

        return try {
            val arr = JSONArray(file.readText())
            val end = minOf(offset + limit, arr.length())
            val words = mutableListOf<VocabWord>()
            for (i in offset until end) {
                val obj = arr.getJSONObject(i)
                val word = obj.optString("word", "")

                val translations = mutableListOf<Pair<String, String>>()
                val transArr = obj.optJSONArray("translations")
                if (transArr != null) {
                    for (j in 0 until transArr.length()) {
                        val t = transArr.getJSONObject(j)
                        translations.add(Pair(
                            t.optString("type", ""),
                            t.optString("translation", "")
                        ))
                    }
                }

                val phrases = mutableListOf<Pair<String, String>>()
                val phraseArr = obj.optJSONArray("phrases")
                if (phraseArr != null) {
                    for (j in 0 until minOf(phraseArr.length(), 3)) { // 每个词最多取3条短语
                        val p = phraseArr.getJSONObject(j)
                        phrases.add(Pair(
                            p.optString("phrase", ""),
                            p.optString("translation", "")
                        ))
                    }
                }

                if (word.isNotEmpty()) {
                    words.add(VocabWord(word, translations, phrases))
                }
            }
            words
        } catch (e: Exception) {
            Timber.tag("WordbookRepo").e(e, "loadWords failed")
            emptyList()
        }
    }

    // ── 打印进度 ──────────────────────────────────────────────

    /** 获取某个单词本的打印进度（已打印到第几个词） */
    fun getProgress(bookId: String): Int {
        return prefs.getInt("progress_$bookId", 0)
    }

    /** 保存打印进度 */
    fun saveProgress(bookId: String, progress: Int) {
        prefs.edit().putInt("progress_$bookId", progress).apply()
    }

    /** 重置进度 */
    fun resetProgress(bookId: String) {
        prefs.edit().putInt("progress_$bookId", 0).apply()
    }
}
