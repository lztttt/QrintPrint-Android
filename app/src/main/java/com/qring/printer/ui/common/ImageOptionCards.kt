package com.qring.printer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qring.printer.protocol.DITHER_OPTIONS
import com.qring.printer.protocol.DitherMode
import com.qring.printer.ui.theme.QringPalette

/** 图片类打印页共用的选项卡片（图片打印 / PDF 打印 / 批量打印）。 */

@Composable
fun AdjustmentCard(
    contrast: Int,
    brightness: Int,
    sharpness: Int,
    onContrastChange: (Int) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onSharpnessChange: (Int) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "图像调整",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))

            // 对比度
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "对比度",
                    fontSize = 13.sp,
                    color = QringPalette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (contrast >= 0) "+$contrast" else "$contrast",
                    fontSize = 13.sp,
                    color = QringPalette.textSecondary
                )
            }
            Slider(
                value = contrast.toFloat(),
                onValueChange = { onContrastChange(Math.round(it)) },
                valueRange = -100f..100f,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = QringPalette.brand,
                    activeTrackColor = QringPalette.brand
                )
            )

            // 亮度
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "亮度",
                    fontSize = 13.sp,
                    color = QringPalette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (brightness >= 0) "+$brightness" else "$brightness",
                    fontSize = 13.sp,
                    color = QringPalette.textSecondary
                )
            }
            Slider(
                value = brightness.toFloat(),
                onValueChange = { onBrightnessChange(Math.round(it)) },
                valueRange = -100f..100f,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = QringPalette.brand,
                    activeTrackColor = QringPalette.brand
                )
            )

            // 锐度
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "锐度",
                    fontSize = 13.sp,
                    color = QringPalette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$sharpness",
                    fontSize = 13.sp,
                    color = QringPalette.textSecondary
                )
            }
            Slider(
                value = sharpness.toFloat(),
                onValueChange = { onSharpnessChange(Math.round(it)) },
                valueRange = 0f..100f,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = QringPalette.brand,
                    activeTrackColor = QringPalette.brand
                )
            )
        }
    }
}

@Composable
fun DitherSelector(
    selectedMode: DitherMode,
    onModeChange: (DitherMode) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "抖动算法",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DITHER_OPTIONS.forEach { option ->
                    val active = option.mode == selectedMode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) QringPalette.brand else QringPalette.surfaceSunken)
                            .clickable(enabled = enabled) { onModeChange(option.mode) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = option.label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (active) Color.White else QringPalette.textPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    suffix: String,
    valueText: String,
    onValueChange: (Float) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    color = QringPalette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (suffix.isEmpty()) valueText else "$valueText$suffix",
                    fontSize = 13.sp,
                    color = QringPalette.textSecondary
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = min..max,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = QringPalette.brand,
                    activeTrackColor = QringPalette.brand
                )
            )
        }
    }
}

@Composable
fun ThicknessSlider(
    thickness: Int?,
    onThicknessChange: (Int?) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "打印浓度",
                    fontSize = 13.sp,
                    color = QringPalette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = thickness?.toString() ?: "默认",
                    fontSize = 13.sp,
                    color = QringPalette.textSecondary
                )
            }
            Slider(
                value = (thickness ?: 0).toFloat(),
                onValueChange = { v ->
                    val rounded = Math.round(v)
                    onThicknessChange(if (rounded == 0) null else rounded)
                },
                valueRange = 0f..5f,
                steps = 4,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = QringPalette.brand,
                    activeTrackColor = QringPalette.brand
                )
            )
        }
    }
}

@Composable
fun TransformCard(
    rotation: Int,
    flipH: Boolean,
    flipV: Boolean,
    invert: Boolean,
    onRotationChange: (Int) -> Unit,
    onFlipHChange: () -> Unit,
    onFlipVChange: () -> Unit,
    onInvertChange: () -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "变换",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = QringPalette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${rotation}°",
                    fontSize = 13.sp,
                    color = QringPalette.textSecondary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 旋转按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransformButton(
                    label = "+90°",
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    onClick = { onRotationChange(rotation + 90) }
                )
                TransformButton(
                    label = "-90°",
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    onClick = { onRotationChange(rotation - 90) }
                )
                TransformButton(
                    label = "+180°",
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    onClick = { onRotationChange(rotation + 180) }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 翻转按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransformButton(
                    label = "水平翻转",
                    active = flipH,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    onClick = onFlipHChange
                )
                TransformButton(
                    label = "垂直翻转",
                    active = flipV,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    onClick = onFlipVChange
                )
                TransformButton(
                    label = "反色",
                    active = invert,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    onClick = onInvertChange
                )
            }
        }
    }
}

@Composable
private fun TransformButton(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) QringPalette.brand else QringPalette.surfaceSunken)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) Color.White else QringPalette.textPrimary
        )
    }
}

fun DitherMode.label(): String =
    DITHER_OPTIONS.firstOrNull { it.mode == this }?.label ?: "无"
