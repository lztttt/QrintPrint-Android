package com.qring.printer.ui.common

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import timber.log.Timber
import java.io.File

/**
 * 字体枚举与加载。
 *
 * 四类来源：
 *   1. App 内置字体（assets/fonts）—— 楷体/宋体等，随 APK 打包
 *   2. 用户导入字体 —— 从文件选择器导入的 TTF/OTF，保存在内部存储
 *   3. 系统通用族 —— sans-serif / serif / monospace 及字重变体
 *   4. 系统字体目录扫描 —— /system/fonts 等
 */
object FontList {

    /** 内置字体：文件名 → 显示名 */
    private val BUNDLED_FONTS = listOf(
        "LXGWWenKai-Regular.ttf" to "霞鹜文楷（楷体）",
        "SourceHanSerifCN-Regular.otf" to "思源宋体",
    )

    /** 内置字体族名 → Typeface（App 启动时加载） */
    private val bundledTypefaces = mutableMapOf<String, Typeface>()

    /** 用户导入字体：族名(文件名) → Typeface */
    private val importedTypefaces = mutableMapOf<String, Typeface>()

    /** 用户导入字体目录 */
    private var importedDir: File? = null

    /**
     * 加载 App 内置字体。幂等，App 启动时调用一次。
     */
    fun initBundled(context: Context) {
        if (bundledTypefaces.isNotEmpty()) return
        for ((file, _) in BUNDLED_FONTS) {
            try {
                val tf = Typeface.createFromAsset(context.assets, "fonts/$file")
                bundledTypefaces[file.substringBeforeLast(".")] = tf
            } catch (e: Exception) {
                // 字体缺失/损坏时静默跳过，退回系统字体
            }
        }
        // 加载已导入的字体
        loadImportedFonts(context)
    }

    /**
     * 从内部存储加载用户之前导入的字体。
     */
    private fun loadImportedFonts(context: Context) {
        val dir = File(context.filesDir, "imported_fonts").apply { mkdirs() }
        importedDir = dir
        dir.listFiles()?.forEach { file ->
            if (file.extension.lowercase() in listOf("ttf", "otf")) {
                try {
                    val tf = Typeface.createFromFile(file)
                    val familyName = file.nameWithoutExtension
                    importedTypefaces[familyName] = tf
                    Timber.tag("FontList").d("loaded imported font: $familyName")
                } catch (e: Exception) {
                    Timber.tag("FontList").w(e, "failed to load imported font: ${file.name}")
                }
            }
        }
    }

    /**
     * 从 Uri 导入字体文件，复制到内部存储并加载。
     * 返回导入后的族名（文件名不含扩展名），失败返回 null。
     */
    fun importFont(context: Context, uri: Uri): String? {
        val dir = importedDir ?: File(context.filesDir, "imported_fonts").apply { mkdirs() }
        importedDir = dir

        // 从 Uri 获取文件名
        val fileName = getFileNameFromUri(context, uri) ?: "imported_${System.currentTimeMillis()}.ttf"
        val ext = fileName.substringAfterLast(".", "ttf").lowercase()
        if (ext !in listOf("ttf", "otf")) return null

        val displayName = fileName.substringBeforeLast(".")
        // 避免重名
        var targetName = displayName
        var targetFile = File(dir, "$targetName.$ext")
        var counter = 1
        while (targetFile.exists()) {
            targetName = "${displayName}_$counter"
            targetFile = File(dir, "$targetName.$ext")
            counter++
        }

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            val tf = Typeface.createFromFile(targetFile)
            importedTypefaces[targetName] = tf
            Timber.tag("FontList").d("imported font: $targetName from $fileName")
            targetName
        } catch (e: Exception) {
            Timber.tag("FontList").e(e, "failed to import font: $fileName")
            targetFile.delete()
            null
        }
    }

    /**
     * 删除已导入的字体。
     */
    fun deleteImportedFont(familyName: String) {
        val dir = importedDir ?: return
        val extensions = listOf("ttf", "otf")
        for (ext in extensions) {
            val file = File(dir, "$familyName.$ext")
            if (file.exists()) {
                file.delete()
                break
            }
        }
        importedTypefaces.remove(familyName)
    }

    /**
     * 获取已导入的字体列表。
     */
    fun getImportedFonts(): List<String> {
        return importedTypefaces.keys.toList()
    }

    /**
     * 从 Uri 获取文件名。
     */
    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment
        }
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex("_display_name")
                if (nameIndex >= 0) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name ?: uri.lastPathSegment
    }

    /**
     * 获取设备上可用的字体族名列表（含导入字体）。
     */
    fun getSystemFonts(context: Context): List<String> {
        initBundled(context)
        val fonts = linkedSetOf<String>()

        // 内置字体优先排在最前，方便选择
        for ((file, _) in BUNDLED_FONTS) {
            fonts.add(file.substringBeforeLast("."))
        }

        // 用户导入字体
        for (name in importedTypefaces.keys) {
            fonts.add(name)
        }

        // 兜底通用字体
        fonts.add("sans-serif")
        fonts.add("serif")
        fonts.add("monospace")
        fonts.add("sans-serif-light")
        fonts.add("sans-serif-condensed")
        fonts.add("sans-serif-medium")
        fonts.add("sans-serif-black")
        fonts.add("sans-serif-thin")
        fonts.add("sans-serif-condensed-light")

        // 扫描系统字体目录
        val systemDirs = listOf(
            "/system/fonts",
            "/system/font",
            "/data/fonts",
            "/vendor/font"
        )
        for (dir in systemDirs) {
            val dirFile = File(dir)
            if (dirFile.exists() && dirFile.isDirectory) {
                dirFile.listFiles()?.forEach { file ->
                    if (file.extension.lowercase() in listOf("ttf", "otf", "ttc")) {
                        val name = file.nameWithoutExtension
                            .replace("-Regular", "")
                            .replace("-Bold", "")
                            .replace("-Italic", "")
                            .replace("-Light", "")
                            .replace("-Medium", "")
                            .replace("-Black", "")
                            .replace("-Thin", "")
                            .replace("-Condensed", "")
                            .replace(Regex("[_-]+"), " ")
                            .trim()
                        if (name.isNotEmpty()) fonts.add(name)
                    }
                }
            }
        }

        return fonts.toList()
    }

    /**
     * 判断字体是否为导入字体。
     */
    fun isImported(family: String): Boolean {
        return importedTypefaces.containsKey(family)
    }

    /**
     * 获取字体显示标签。
     */
    fun fontLabel(family: String): String = when (family) {
        "LXGWWenKai-Regular" -> "霞鹜文楷（楷体）"
        "SourceHanSerifCN-Regular" -> "思源宋体"
        "sans-serif" -> "系统默认(无衬线)"
        "serif" -> "衬线"
        "monospace" -> "等宽"
        "sans-serif-light" -> "细体"
        "sans-serif-condensed" -> "紧凑"
        "sans-serif-medium" -> "中等"
        "sans-serif-black" -> "粗黑"
        "sans-serif-thin" -> "极细"
        "sans-serif-condensed-light" -> "紧凑细体"
        else -> if (isImported(family)) "📥 $family" else family
    }

    /**
     * 尝试创建指定字体族名的 Typeface。
     * 内置字体 → 导入字体 → 系统字体 → 默认。
     */
    fun typefaceFor(family: String, bold: Boolean = false, italic: Boolean = false): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        // 内置字体
        val bundled = bundledTypefaces[family]
        if (bundled != null) {
            return try {
                Typeface.create(bundled, style)
            } catch (e: Exception) {
                bundled
            }
        }
        // 导入字体
        val imported = importedTypefaces[family]
        if (imported != null) {
            return try {
                Typeface.create(imported, style)
            } catch (e: Exception) {
                imported
            }
        }
        // 系统字体
        return try {
            Typeface.create(family, style)
        } catch (e: Exception) {
            Typeface.create(Typeface.DEFAULT, style)
        }
    }
}
