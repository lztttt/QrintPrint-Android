package com.qring.print.ui.common

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import java.io.File

/**
 * 系统字体枚举。
 * 从系统目录和内置字体列表中收集可用字体。
 */
object FontList {

    /**
     * 获取设备上可用的字体族名列表。
     */
    fun getSystemFonts(context: Context): List<String> {
        val fonts = linkedSetOf<String>()

        // 兜底字体
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

        // 从 AssetManager 获取（部分系统字体）
        try {
            val assetFonts = context.assets.list("fonts")
            assetFonts?.forEach { fontFonts ->
                if (fontFonts is String) {
                    val name = fontFonts.substringBeforeLast(".")
                    if (name.isNotEmpty()) fonts.add(name)
                }
            }
        } catch (e: Exception) { }

        return fonts.toList()
    }

    /**
     * 获取字体显示标签。
     */
    fun fontLabel(family: String): String = when (family) {
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
     */
    fun typefaceFor(family: String, bold: Boolean = false, italic: Boolean = false): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return try {
            Typeface.create(family, style)
        } catch (e: Exception) {
            Typeface.create(Typeface.DEFAULT, style)
        }
    }
}
