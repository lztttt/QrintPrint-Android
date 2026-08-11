package com.qring.printer.data

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release OTA 更新管理器。
 *
 * 流程：
 * 1. 检查 GitHub 最新 Release（/repos/{owner}/{repo}/releases/latest）
 * 2. 比对版本号（BuildConfig.VERSION_NAME vs release tag_name）
 * 3. 下载 APK 到缓存目录
 * 4. 通过 FileProvider 触发系统安装 Intent
 */
class UpdateManager(private val app: Application) {

    // GitHub 仓库配置 —— 按实际项目修改
    companion object {
        // 从 BuildConfig 读取，或硬编码
        val GITHUB_OWNER = "lztttt"
        val GITHUB_REPO = "QrintPrint"
        val CURRENT_VERSION = com.qring.printer.BuildConfig.VERSION_NAME
    }

    data class UpdateInfo(
        val version: String,         // 新版本号，如 "1.0.1"
        val releaseNotes: String,    // 更新说明
        val downloadUrl: String,     // APK 下载地址
        val downloadSize: Long,      // APK 大小（字节）
        val htmlUrl: String,         // Release 页面 URL
        val publishedAt: String = ""  // 发布时间
    )

    data class ReleaseNote(
        val version: String,
        val notes: String,
        val htmlUrl: String,
        val publishedAt: String
    )

    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        data class Available(val info: UpdateInfo) : UpdateState()
        object UpToDate : UpdateState()
        data class Downloading(val progress: Int) : UpdateState()  // 0-100
        object ReadyToInstall : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val apiBase = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO"

    /**
     * 检查更新。
     */
    suspend fun checkForUpdate() {
        _state.value = UpdateState.Checking
        try {
            val info = withContext(Dispatchers.IO) {
                val url = URL("$apiBase/releases/latest")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "QringPrint/$CURRENT_VERSION")
                }
                conn.inputStream.bufferedReader().use { it.readText() }
                    .let { json -> parseRelease(JSONObject(json)) }
            }

            if (info != null && isNewerVersion(info.version, CURRENT_VERSION)) {
                _state.value = UpdateState.Available(info)
            } else {
                _state.value = UpdateState.UpToDate
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "检查更新失败")
        }
    }

    /**
     * 下载 APK 并触发安装。
     */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        _state.value = UpdateState.Downloading(0)
        try {
            val apkFile = withContext(Dispatchers.IO) {
                val updateDir = File(app.cacheDir, "updates").apply { mkdirs() }
                val apk = File(updateDir, "qringprint-${info.version}.apk")

                val url = URL(info.downloadUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "QringPrint/$CURRENT_VERSION")
                    instanceFollowRedirects = true
                }

                val total = conn.contentLengthLong.coerceAtLeast(1)
                conn.inputStream.use { input ->
                    apk.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var downloaded = 0L
                        while (input.read(buffer).also { bytesRead = it } > 0) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            val progress = (downloaded * 100 / total).toInt()
                            _state.value = UpdateState.Downloading(progress)
                        }
                    }
                }
                apk
            }

            _state.value = UpdateState.ReadyToInstall
            installApk(apkFile)
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "下载失败")
        }
    }

    /**
     * 触发系统安装 Intent。
     */
    private fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        app.startActivity(intent)
    }

    /**
     * 解析 GitHub Release JSON，提取 APK 资源。
     */
    private fun parseRelease(json: JSONObject): UpdateInfo? {
        val tagName = json.optString("tag_name", "").removePrefix("v")  // v1.0.1 → 1.0.1
        val body = json.optString("body", "暂无更新说明")
        val htmlUrl = json.optString("html_url", "")

        // 在 assets 中找 .apk 文件
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                val downloadUrl = asset.optString("browser_download_url", "")
                val size = asset.optLong("size", 0)
                return UpdateInfo(
                    version = tagName,
                    releaseNotes = body,
                    downloadUrl = downloadUrl,
                    downloadSize = size,
                    htmlUrl = htmlUrl,
                    publishedAt = json.optString("published_at", "")
                )
            }
        }
        // 没有 APK asset，可能是 source code only
        return null
    }

    /**
     * 简单的语义化版本比较：1.0.0 < 1.0.1 < 1.1.0 < 2.0.0
     */
    private fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false  // 相等
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    /**
     * 获取所有 Release 记录（更新日志）。
     */
    suspend fun fetchAllReleases(): List<ReleaseNote> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$apiBase/releases?per_page=20")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "QringPrint/$CURRENT_VERSION")
                }
                val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = org.json.JSONArray(jsonText)
                val list = mutableListOf<ReleaseNote>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val tagName = obj.optString("tag_name", "").removePrefix("v")
                    val body = obj.optString("body", "暂无说明")
                    val htmlUrl = obj.optString("html_url", "")
                    val publishedAt = obj.optString("published_at", "")
                    if (tagName.isNotEmpty()) {
                        list.add(ReleaseNote(tagName, body, htmlUrl, publishedAt))
                    }
                }
                list
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
