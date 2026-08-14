package com.qring.printer.data

import android.content.Context
import android.graphics.Bitmap
import com.qring.printer.model.HistoryRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * 历史记录存储仓库。
 * 存 SharedPreferences JSON 索引 + 缩略图文件。
 */
class HistoryRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("qringprint_history", Context.MODE_PRIVATE)
    private val thumbsDir = File(context.filesDir, "history").apply { mkdirs() }
    private val imagesDir = File(context.filesDir, "history_images").apply { mkdirs() }

    /**
     * 通用：把 content:// 或 file:// 源拷贝到内部存储的子目录，返回内部路径。
     * 用于规避 Uri 权限过期（PDF / 批量打印等长生命周期场景）。
     */
    fun copyUriToInternal(uriString: String, subDir: String, prefix: String): String? {
        return try {
            val uri = android.net.Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { input ->
                val dir = File(context.filesDir, subDir).apply { mkdirs() }
                val file = File(dir, prefix + UUID.randomUUID().toString().take(8) + ".bin")
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
                file.absolutePath
            }
        } catch (e: Exception) {
            Timber.tag("HistoryRepo").e(e, "failed to copy uri to internal storage")
            null
        }
    }

    /**
     * 保存图片到内部存储，返回内部存储路径
     */
    fun saveImageToInternalStorage(uriString: String): String? {
        return copyUriToInternal(uriString, "history_images", "img_")
    }

    /** 保存 PDF 到内部存储，返回内部存储路径 */
    fun savePdfToInternalStorage(uriString: String): String? {
        return copyUriToInternal(uriString, "pdfs", "pdf_")
    }

    /**
     * 保存一条历史记录。
     * @param typeName 类型: text / image / code / custom
     * @param thumbnail 缩略图位图（会被缩放、压缩、落盘）
     * @param payload JSON 字符串，包含重建打印所需的所有参数
     */
    suspend fun saveHistory(
        typeName: String,
        thumbnail: Bitmap,
        payload: String
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().take(8)
        val thumbFile = File(thumbsDir, "hist_$id.png")

        // 缩略图等比缩到 200px 宽
        val ratio = 200f / thumbnail.width
        val thumbH = maxOf(1, Math.round(thumbnail.height * ratio))
        val scaled = Bitmap.createScaledBitmap(thumbnail, 200, thumbH, true)
        thumbFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 85, it) }
        if (scaled != thumbnail) scaled.recycle()

        val record = HistoryRecord(
            id = id,
            typeName = typeName,
            thumbnailPath = thumbFile.absolutePath,
            payload = payload,
            createdAt = System.currentTimeMillis()
        )

        val json = historyRecordToJson(record)
        prefs.edit().putString("hist_$id", json).apply()

        Timber.tag("HistoryRepo").d("saved history $id: $typeName")
        id
    }

    /**
     * 列出所有历史记录（最新在前）。
     */
    suspend fun listHistory(): List<HistoryRecord> = withContext(Dispatchers.IO) {
        val records = mutableListOf<HistoryRecord>()
        prefs.all.forEach { (_, value) ->
            if (value is String) {
                try {
                    records.add(jsonToHistoryRecord(value))
                } catch (e: Exception) { }
            }
        }
        records.sortedByDescending { it.createdAt }
    }

    /**
     * 删除一条历史记录。
     */
    suspend fun deleteHistory(id: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove("hist_$id").apply()
        File(thumbsDir, "hist_$id.png").delete()
    }

    /**
     * 清空历史。
     */
    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
        thumbsDir.listFiles()?.forEach { it.delete() }
    }

    // ── JSON 序列化 ──────────────────────────────────────────

    private fun historyRecordToJson(record: HistoryRecord): String {
        return JSONObject().apply {
            put("id", record.id)
            put("typeName", record.typeName)
            put("thumbnailPath", record.thumbnailPath)
            put("payload", record.payload)
            put("createdAt", record.createdAt)
        }.toString()
    }

    private fun jsonToHistoryRecord(json: String): HistoryRecord {
        val obj = JSONObject(json)
        return HistoryRecord(
            id = obj.getString("id"),
            typeName = obj.getString("typeName"),
            thumbnailPath = obj.optString("thumbnailPath", ""),
            payload = obj.optString("payload", ""),
            createdAt = obj.getLong("createdAt")
        )
    }
}
