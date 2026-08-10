package com.qring.print.ui.common

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import java.io.File

/**
 * 字体枚举与加载。
 *
 * 三类来源：
 *   1. App 内置字体（assets/fonts）—— 楷体/宋体等，随 APK 打包
 *   2. 系统通用族 —— sans-serif / serif / monospace 及字重变体
 *   3. 系统字体目录扫描 —— /system/fonts 等
 */
object FontList {

    /** 内置字体：文件名 → 显示名 */
    private val BUNDLED_FONTS = listOf(
        "LXGWWenKai-Regular.ttf" to "霞鹜文楷（楷体）",
        "SourceHanSerifCN-Regular.otf" to "思源宋体",
    )

    /** 内置字体族名 → Typeface（App 启动时加载） */
    private val bundledTypefaces = mutableMapOf<String, Typeface>()

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
    }

    /**
     * 获取设备上可用的字体族名列表。
     */
    fun getSystemFonts(context: Context): List<String> {
        initBundled(context)
        val fonts = linkedSetOf<String>()

        // 内置字体优先排在最前，方便选择
        for ((file, _) in BUNDLED_FONTS) {
            fonts.add(file.substringBeforeLast("."))
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
        else -> family
    }

    /**
     * 尝试创建指定字体族名的 Typeface。
     * 内置字体（assets）优先，否则回落到系统字体。
     */
    fun typefaceFor(family: String, bold: Boolean = false, italic: Boolean = false): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val bundled = bundledTypefaces[family]
        if (bundled != null) {
            return try {
                Typeface.create(bundled, style)
            } catch (e: Exception) {
                bundled
            }
        }
        return try {
            Typeface.create(family, style)
        } catch (e: Exception) {
            Typeface.create(Typeface.DEFAULT, style)
        }
    }
}
