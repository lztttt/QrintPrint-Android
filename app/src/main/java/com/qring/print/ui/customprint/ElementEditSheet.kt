package com.qring.print.ui.customprint

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qring.print.model.CODE_TYPES
import com.qring.print.model.CodeCategory
import com.qring.print.model.CanvasElement
import com.qring.print.model.ElementKind
import com.qring.print.protocol.DITHER_OPTIONS
import com.qring.print.protocol.DitherMode
import com.qring.print.protocol.TextRenderOptions
import com.qring.print.ui.theme.BRAND
import com.qring.print.ui.theme.QringPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementEditSheet(
    element: CanvasElement,
    onDismiss: () -> Unit,
    onTextChange: (String) -> Unit,
    onCodeContentChange: (String) -> Unit,
    onCodeTypeChange: (Int) -> Unit,
    onDitherChange: (DitherMode) -> Unit,
    onTextOptionsChange: (TextRenderOptions) -> Unit,
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

            when (element.kind) {
                ElementKind.TEXT -> TextEditor(
                    element = element,
                    onTextChange = onTextChange,
                    onOptionsChange = onTextOptionsChange
                )
                ElementKind.IMAGE -> ImageEditor(
                    element = element,
                    onDitherChange = onDitherChange,
                    onSwapImage = onSwapImage
                )
                ElementKind.CODE -> CodeEditor(
                    element = element,
                    onContentChange = onCodeContentChange,
                    onTypeChange = onCodeTypeChange
                )
            }
        }
    }
}

@Composable
private fun TextEditor(
    element: CanvasElement,
    onTextChange: (String) -> Unit,
    onOptionsChange: (TextRenderOptions) -> Unit
) {
    var text by remember(element.id) { mutableStateOf(element.text) }
    var fontSize by remember(element.id) { mutableStateOf(element.textOptions.fontSize) }
    var bold by remember(element.id) { mutableStateOf(element.textOptions.bold) }
    var italic by remember(element.id) { mutableStateOf(element.textOptions.italic) }
    var underline by remember(element.id) { mutableStateOf(element.textOptions.underline) }

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
            cursorBrush = SolidColor(BRAND)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 字号
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("字号", fontSize = 13.sp, color = QringPalette.textPrimary, modifier = Modifier.weight(1f))
        Text("${fontSize.toInt()}", fontSize = 13.sp, color = QringPalette.textSecondary)
    }
    Slider(
        value = fontSize,
        onValueChange = {
            fontSize = it
            onOptionsChange(element.textOptions.copy(fontSize = it))
        },
        valueRange = 12f..72f,
        colors = SliderDefaults.colors(thumbColor = BRAND, activeTrackColor = BRAND)
    )

    // 样式切换
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
}

@Composable
private fun ImageEditor(
    element: CanvasElement,
    onDitherChange: (DitherMode) -> Unit,
    onSwapImage: (Uri) -> Unit
) {
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { onSwapImage(it) } }

    Column {
        Text("抖动模式", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = QringPalette.textPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DITHER_OPTIONS.forEach { option ->
                val active = option.mode == element.ditherMode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) BRAND else QringPalette.surfaceSunken)
                        .clickable { onDitherChange(option.mode) },
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
    onContentChange: (String) -> Unit,
    onTypeChange: (Int) -> Unit
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
            cursorBrush = SolidColor(BRAND)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text("码制", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = QringPalette.textPrimary)
    Spacer(modifier = Modifier.height(8.dp))

    // 二维码
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
                    .background(if (active) BRAND else QringPalette.surfaceSunken)
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

    // 一维码
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
                    .background(if (active) BRAND else QringPalette.surfaceSunken)
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
            .background(if (active) BRAND else QringPalette.surfaceSunken)
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
