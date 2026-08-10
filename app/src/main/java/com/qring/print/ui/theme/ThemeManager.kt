package com.qring.print.ui.theme

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 主题色管理器。
 * 用 SharedPreferences 持久化用户选择的品牌色，
 * 通过 mutableStateOf 让 Compose 自动感知颜色变化。
 */
object ThemeManager {

    private const val PREFS_NAME = "qringprint_theme"
    private const val KEY_BRAND_COLOR = "brand_color"

    /** 默认主题色：青绿色 (Teal) */
    val DEFAULT_COLOR = Color(0xFF0D9488)

    /** 预设主题色列表 */
    val PRESET_COLORS = listOf(
        Color(0xFF0D9488), // 青绿 Teal
        Color(0xFF2563EB), // 蓝色 Blue
        Color(0xFF0891B2), // 青色 Cyan
        Color(0xFFEA580C), // 橙色 Orange
        Color(0xFFDC2626), // 红色 Red
        Color(0xFF16A34A), // 绿色 Green
        Color(0xFFCA8A04), // 琥珀 Amber
        Color(0xFF4F46E5), // 靛蓝 Indigo
        Color(0xFFDB2777), // 粉红 Pink
        Color(0xFF0F172A), // 深灰 Slate
    )

    private val _brandColor = mutableStateOf(DEFAULT_COLOR)
    val brandColor: State<Color> = _brandColor

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val colorInt = prefs.getInt(KEY_BRAND_COLOR, DEFAULT_COLOR.toArgb())
        _brandColor.value = Color(colorInt)
    }

    fun setBrandColor(context: Context, color: Color) {
        _brandColor.value = color
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_BRAND_COLOR, color.toArgb()).apply()
    }

    /** 使颜色变暗 */
    fun darken(color: Color, factor: Float = 0.15f): Color {
        return Color(
            red = color.red * (1f - factor),
            green = color.green * (1f - factor),
            blue = color.blue * (1f - factor),
            alpha = color.alpha
        )
    }

    /** 使颜色变亮 */
    fun lighten(color: Color, factor: Float = 0.15f): Color {
        return Color(
            red = color.red + (1f - color.red) * factor,
            green = color.green + (1f - color.green) * factor,
            blue = color.blue + (1f - color.blue) * factor,
            alpha = color.alpha
        )
    }
}
