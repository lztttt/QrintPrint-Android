package com.qring.printer.ui.customprint

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qring.printer.model.CODE_TYPES
import com.qring.printer.model.CodeCategory
import com.qring.printer.model.CanvasElement
import com.qring.printer.model.ElementKind
import com.qring.printer.protocol.DITHER_OPTIONS
import com.qring.printer.protocol.DitherMode
import com.qring.printer.protocol.TextRenderOptions
import com.qring.printer.ui.theme.QringPalette

@Composable
private fun TextEditor(
    element: CanvasElement,
    fontFamilies: List<String>,
    revision: Int,
    onTextChange: (String) -> Unit,
    onSizeChange: (Int) -> Unit,
    onRotationChange: (Int) -> Unit,
    onFlipHChange: () -> Unit,
onFlipVChange: () -> Unit,
onInvertChange: () -> Unit,
onOptionsChange: (TextRenderOptions) -> Unit,
    onFontFamilyChange: (Int) -> Unit
) {
    var text by remember(element.id) { mutableStateOf(element.text) }
    var bold by remember(element.id, revision) { mutableStateOf(element.textOptions.bold) }
    var italic by remember(element.id, revision) { mutableStateOf(element.textOptions.italic) }
    var underline by remember(element.id, revision) { mutableStateOf(element.textOptions.underline) }
    val fontSize = remember(element.id, revision) { element.textOptions.fontSize }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken),
        shape = RoundedCornerShape(8.dp)
    ) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                onTextChange(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(12.dp),
            textStyle = TextStyle(fontSize = 16.sp, color = QringPalette.textPrimary),
            cursorBrush = SolidColor(QringPalette.brand)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    var fontExpanded by remember(element.id) { mutableStateOf(false) }
    val currentFamily = fontFamilies.getOrElse(
        fontFamilies.indexOf(element.textOptions.fontFamily).takeIf { it >= 0 } ?: 0
    ) { "sans-serif" }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("字体", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
        Text(
            text = com.qring.printer.ui.common.FontList.fontLabel(currentFamily),
            fontSize = 13.sp,
            color = QringPalette.brand,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(QringPalette.surfaceSunken)
                .clickable { fontExpanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        DropdownMenu(expanded = fontExpanded, onDismissRequest = { fontExpanded = false }) {
            fontFamilies.forEachIndexed { index, family ->
                DropdownMenuItem(
                    text = { Text(com.qring.printer.ui.common.FontList.fontLabel(family), fontSize = 13.sp, maxLines = 1) },
                    onClick = {
                        onFontFamilyChange(index)
                        fontExpanded = false
                    }
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    EditableSliderRow(
        label = "字号",
        value = fontSize,
        min = 12f,
        max = 72f,
        suffix = "",
        onValueChange = {
            onOptionsChange(element.textOptions.copy(fontSize = it))
        }
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StyleChip("B", bold, true, false, false) {
            bold = !bold
            onOptionsChange(element.textOptions.copy(bold = bold))
        }
        StyleChip("I", italic, false, true, false) {
            italic = !italic
            onOptionsChange(element.textOptions.copy(italic = italic))
        }
        StyleChip("U", underline, false, false, true) {
            underline = !underline
            onOptionsChange(element.textOptions.copy(underline = underline))
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    EditableSliderRow(
        label = "宽度",
        value = element.dotW.toFloat(),
        min = 24f,
        max = 384f,
        suffix = "pt",
        onValueChange = { onSizeChange(Math.round(it)) }
    )
    TransformRow(
        rotation = element.rotation,
flipH = element.flipH,
flipV = element.flipV,
invert = element.invert,
onRotationChange = onRotationChange,
onFlipHChange = onFlipHChange,
onFlipVChange = onFlipVChange,
onInvertChange = onInvertChange
)
}

@Composable
private fun ImageEditor(
    element: CanvasElement,
    revision: Int,
    onDitherChange: (DitherMode) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onScaleChange: (Int) -> Unit,
    onRotationChange: (Int) -> Unit,
    onFlipHChange: () -> Unit,
onFlipVChange: () -> Unit,
onInvertChange: () -> Unit,
onSwapImage: (Uri) -> Unit
) {
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { onSwapImage(it) } }

    // 缩放百分比 = dotW / 384 * 100
    val scalePct = remember(element.id, revision) {
        (element.dotW.toFloat() / 384f * 100f).coerceIn(10f, 150f)
    }

    Column {
        Text("抖动模式", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = QringPalette.textPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DITHER_OPTIONS.forEach { option ->
                val active = option.mode == element.ditherMode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) QringPalette.brand else QringPalette.surfaceSunken)
                        .clickable { onDitherChange(option.mode) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option.label,
                        fontSize = 12.sp,
                        color = if (active) Color.White else QringPalette.textPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        EditableSliderRow(
            label = "阈值",
            value = element.ditherThreshold.toFloat(),
            min = 0f,
            max = 255f,
            suffix = "",
            onValueChange = { onThresholdChange(Math.round(it)) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        EditableSliderRow(
            label = "缩放",
            value = scalePct,
            min = 10f,
            max = 150f,
            suffix = "%",
            onValueChange = { onScaleChange(Math.round(it)) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        TransformRow(
            rotation = element.rotation,
            flipH = element.flipH,
            flipV = element.flipV,
            invert = element.invert,
            onRotationChange = onRotationChange,
            onFlipHChange = onFlipHChange,
            onFlipVChange = onFlipVChange,
            onInvertChange = onInvertChange
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = QringPalette.surfaceSunken,
                contentColor = QringPalette.textPrimary
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("更换图片")
        }
    }
}

@Composable
private fun CodeEditor(
    element: CanvasElement,
    revision: Int,
    onContentChange: (String) -> Unit,
    onTypeChange: (Int) -> Unit,
    onSizeChange: (Int) -> Unit,
    onRotationChange: (Int) -> Unit,
    onFlipHChange: () -> Unit,
onFlipVChange: () -> Unit,
onInvertChange: () -> Unit
) {
var content by remember(element.id) { mutableStateOf(element.codeContent) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken),
        shape = RoundedCornerShape(8.dp)
    ) {
        BasicTextField(
            value = content,
            onValueChange = {
                content = it
                onContentChange(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(12.dp),
            textStyle = TextStyle(fontSize = 16.sp, color = QringPalette.textPrimary),
            cursorBrush = SolidColor(QringPalette.brand)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text("码制", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = QringPalette.textPrimary)
    Spacer(modifier = Modifier.height(8.dp))

    Text("二维码", fontSize = 12.sp, color = QringPalette.textSecondary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CODE_TYPES.filter { it.category == CodeCategory.TWO_D }.forEach { codeType ->
            val idx = CODE_TYPES.indexOf(codeType)
            val active = idx == element.codeTypeIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) QringPalette.brand else QringPalette.surfaceSunken)
                    .clickable { onTypeChange(idx) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = codeType.label.replace(" Code", "").replace("Matrix", "M"),
                    fontSize = 11.sp,
                    color = if (active) Color.White else QringPalette.textPrimary
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text("一维码", fontSize = 12.sp, color = QringPalette.textSecondary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CODE_TYPES.filter { it.category == CodeCategory.ONE_D }.forEach { codeType ->
            val idx = CODE_TYPES.indexOf(codeType)
            val active = idx == element.codeTypeIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) QringPalette.brand else QringPalette.surfaceSunken)
                    .clickable { onTypeChange(idx) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = codeType.label.replace(" Code", ""),
                    fontSize = 11.sp,
                    color = if (active) Color.White else QringPalette.textPrimary
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    EditableSliderRow(
        label = "宽度",
        value = element.dotW.toFloat(),
        min = 24f,
        max = 384f,
        suffix = "pt",
        onValueChange = { onSizeChange(Math.round(it)) }
    )

    Spacer(modifier = Modifier.height(8.dp))

    TransformRow(
        rotation = element.rotation,
flipH = element.flipH,
flipV = element.flipV,
invert = element.invert,
onRotationChange = onRotationChange,
onFlipHChange = onFlipHChange,
onFlipVChange = onFlipVChange,
onInvertChange = onInvertChange
)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementEditSheet(
    element: CanvasElement,
    fontFamilies: List<String>,
    revision: Int,
    onDismiss: () -> Unit,
    onTextChange: (String) -> Unit,
    onCodeContentChange: (String) -> Unit,
    onCodeTypeChange: (Int) -> Unit,
    onDitherChange: (DitherMode) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onSizeChange: (Int) -> Unit,
    onScaleChange: (Int) -> Unit,
    onRotationChange: (Int) -> Unit,
    onFlipHChange: () -> Unit,
onFlipVChange: () -> Unit,
onInvertChange: () -> Unit,
onDelete: () -> Unit,
    onTextOptionsChange: (TextRenderOptions) -> Unit,
    onFontFamilyChange: (Int) -> Unit,
    onSwapImage: (Uri) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = QringPalette.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "编辑元素",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 用 key(revision) 强制每次 revision 变化时重新读取 element 属性
            androidx.compose.runtime.key(revision, element.id) {
                when (element.kind) {
                    ElementKind.TEXT -> TextEditor(
                        element = element,
                        fontFamilies = fontFamilies,
                        revision = revision,
                        onTextChange = onTextChange,
                        onSizeChange = onSizeChange,
                        onRotationChange = onRotationChange,
                        onFlipHChange = onFlipHChange,
                        onFlipVChange = onFlipVChange,
                        onInvertChange = onInvertChange,
                        onOptionsChange = onTextOptionsChange,
                        onFontFamilyChange = onFontFamilyChange
                    )
                    ElementKind.IMAGE -> ImageEditor(
                        element = element,
                        revision = revision,
                        onDitherChange = onDitherChange,
                        onThresholdChange = onThresholdChange,
                        onScaleChange = onScaleChange,
                        onRotationChange = onRotationChange,
                        onFlipHChange = onFlipHChange,
                        onFlipVChange = onFlipVChange,
                        onInvertChange = onInvertChange,
                        onSwapImage = onSwapImage
                    )
                    ElementKind.CODE -> CodeEditor(
                        element = element,
                        revision = revision,
                        onContentChange = onCodeContentChange,
                        onTypeChange = onCodeTypeChange,
                        onSizeChange = onSizeChange,
                        onRotationChange = onRotationChange,
                        onFlipHChange = onFlipHChange,
                        onFlipVChange = onFlipVChange,
                        onInvertChange = onInvertChange
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 删除
            Button(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF4D4F).copy(alpha = 0.12f),
                    contentColor = Color(0xFFFF4D4F)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("删除该元素", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── 可编辑滑块行：滑块 + 手动输入框 ──────────────────────

@Composable
private fun EditableSliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    suffix: String = "",
    onValueChange: (Float) -> Unit
) {
    val displayValue = Math.round(value)
    var textValue by remember(value) { mutableStateOf(displayValue.toString()) }
    var isEditing by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))

            // 可编辑的数值输入框
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(QringPalette.surfaceSunken)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isEditing) {
                    BasicTextField(
                        value = textValue,
                        onValueChange = { input ->
                            textValue = input.filter { it.isDigit() }
                        },
                        textStyle = TextStyle(
                            fontSize = 13.sp,
                            color = QringPalette.textPrimary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        cursorBrush = SolidColor(QringPalette.brand),
                        decorationBox = { innerTextField ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                innerTextField()
                                if (suffix.isNotEmpty()) {
                                    Text(suffix, fontSize = 11.sp, color = QringPalette.textSecondary)
                                }
                            }
                        }
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                textValue = displayValue.toString()
                                isEditing = true 
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$displayValue",
                            fontSize = 13.sp,
                            color = QringPalette.textPrimary
                        )
                        if (suffix.isNotEmpty()) {
                            Text(suffix, fontSize = 11.sp, color = QringPalette.textSecondary)
                        }
                    }
                }
            }
        }

        Slider(
            value = value.coerceIn(min, max),
            onValueChange = {
                isEditing = false
                onValueChange(it)
            },
            valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = QringPalette.brand,
                activeTrackColor = QringPalette.brand
            )
        )
    }

    // 输入框失焦时提交值
    if (isEditing) {
        androidx.compose.runtime.LaunchedEffect(textValue) {
            // 延迟提交，等用户输完
        }
    }

    // 点击外部时提交
    if (isEditing) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.dp)
                .clickable {
                    val parsed = textValue.toIntOrNull() ?: displayValue
                    val clamped = parsed.toFloat().coerceIn(min, max)
                    onValueChange(clamped)
                    isEditing = false
                }
        )
    }
}

// ── 变换行：旋转 + 翻转 ──────────────────────────────────

@Composable
private fun TransformRow(
    rotation: Int,
    flipH: Boolean,
    flipV: Boolean,
    invert: Boolean,
    onRotationChange: (Int) -> Unit,
    onFlipHChange: () -> Unit,
    onFlipVChange: () -> Unit,
    onInvertChange: () -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("变换", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
            Text(
                text = "${rotation}°",
                fontSize = 13.sp,
                color = QringPalette.textSecondary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 旋转按钮行：+90° / -90° / +180°
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TransformButton(
                label = "+90°",
                modifier = Modifier.weight(1f),
                onClick = { onRotationChange(rotation + 90) }
            )
            TransformButton(
                label = "-90°",
                modifier = Modifier.weight(1f),
                onClick = { onRotationChange(rotation - 90) }
            )
            TransformButton(
                label = "+180°",
                modifier = Modifier.weight(1f),
                onClick = { onRotationChange(rotation + 180) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 翻转按钮行：水平翻转 / 垂直翻转
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TransformButton(
                label = "水平翻转",
                active = flipH,
                modifier = Modifier.weight(1f),
                onClick = onFlipHChange
            )
            TransformButton(
                label = "垂直翻转",
                active = flipV,
                modifier = Modifier.weight(1f),
                onClick = onFlipVChange
            )
            TransformButton(
                label = "反色",
                active = invert,
                modifier = Modifier.weight(1f),
                onClick = onInvertChange
            )
        }
    }
}

@Composable
private fun TransformButton(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) QringPalette.brand else QringPalette.surfaceSunken)
            .clickable(onClick = onClick),
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

@Composable
private fun StyleChip(
    label: String,
    active: Boolean,
    bold: Boolean,
    italic: Boolean,
    underline: Boolean,
    onTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) QringPalette.brand else QringPalette.surfaceSunken)
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (active) Color.White else QringPalette.textPrimary
        )
    }
}
