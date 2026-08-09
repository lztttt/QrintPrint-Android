package com.qring.print.ui.customprint

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.print.R
import com.qring.print.model.CanvasElement
import com.qring.print.model.ElementKind
import com.qring.print.ui.theme.ACTION_BLUE
import com.qring.print.ui.theme.BRAND
import com.qring.print.ui.theme.HANDLE_FILL
import com.qring.print.ui.theme.HANDLE_SIZE
import com.qring.print.ui.theme.Metrics
import com.qring.print.ui.theme.ONLINE
import com.qring.print.ui.theme.QringPalette
import com.qring.print.ui.theme.SELECT_OUTLINE
import kotlin.math.roundToInt

/**
 * 画布视图 — 显示合成结果 + 元素选择框 + 拖动手势。
 */
@Composable
fun CanvasView(
    compositeBitmap: android.graphics.Bitmap?,
    elements: List<CanvasElement>,
    selectedId: String,
    canvasWidthDp: Float,
    onSelect: (String) -> Unit,
    onMove: (String, Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scale = canvasWidthDp / 384f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(QringPalette.paper)
            .border(1.dp, QringPalette.paperEdge, RoundedCornerShape(12.dp))
    ) {
        // 合成图
        if (compositeBitmap != null) {
            Image(
                bitmap = compositeBitmap.asImageBitmap(),
                contentDescription = "画布预览",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        }

        // 元素选择框
        elements.forEach { el ->
            val isSelected = el.id == selectedId
            val xDp = el.dotX * scale
            val yDp = el.dotY * scale
            val wDp = el.dotW * scale
            val hDp = el.dotH * scale

            Box(
                modifier = Modifier
                    .offset { IntOffset((el.dotX * scale).roundToInt(), (el.dotY * scale).roundToInt()) }
                    .size(
                        width = with(density) { (el.dotW * scale).toDp() },
                        height = with(density) { (el.dotH * scale).toDp() }
                    )
                    .border(
                        width = if (isSelected) 2.dp else 0.dp,
                        color = SELECT_OUTLINE,
                        shape = RoundedCornerShape(2.dp)
                    )
                    .clickable { onSelect(el.id) }
                    .then(
                        if (isSelected) {
                            Modifier.pointerInput(el.id) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val dxDp = dragAmount.x
                                    val dyDp = dragAmount.y
                                    val dxDot = (dxDp / scale).roundToInt()
                                    val dyDot = (dyDp / scale).roundToInt()
                                    if (dxDot != 0 || dyDot != 0) {
                                        onMove(el.id, dxDot, dyDot)
                                    }
                                }
                            }
                        } else Modifier
                    )
            ) {
                // 缩放手柄（仅选中时显示）
                if (isSelected && el.kind != ElementKind.TEXT) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(HANDLE_SIZE.dp)
                            .clip(CircleShape)
                            .background(HANDLE_FILL)
                            .border(2.dp, Color.White, CircleShape)
                    )
                }
            }
        }
    }
}

/**
 * 工具栏 — 插入元素、删除、层级、保存、打印。
 */
@Composable
fun EditorToolbar(
    hasSelection: Boolean,
    hasElements: Boolean,
    busy: Boolean,
    printing: Boolean,
    onInsertText: () -> Unit,
    onInsertImage: () -> Unit,
    onInsertCode: () -> Unit,
    onDelete: () -> Unit,
    onToTop: () -> Unit,
    onToBottom: () -> Unit,
    onSave: () -> Unit,
    onPrint: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 插入按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolButton(
                    label = "文字",
                    icon = Icons.Default.Edit,
                    onClick = onInsertText,
                    modifier = Modifier.weight(1f)
                )
                ToolButton(
                    label = "图片",
                    icon = Icons.Default.Add,
                    onClick = onInsertImage,
                    modifier = Modifier.weight(1f)
                )
                ToolButton(
                    label = "条码",
                    icon = Icons.Default.Add,
                    onClick = onInsertCode,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolButton(
                    label = "删除",
                    icon = Icons.Default.Delete,
                    onClick = onDelete,
                    enabled = hasSelection,
                    modifier = Modifier.weight(1f)
                )
                ToolButton(
                    label = "置顶",
                    icon = Icons.Default.KeyboardArrowUp,
                    onClick = onToTop,
                    enabled = hasSelection,
                    modifier = Modifier.weight(1f)
                )
                ToolButton(
                    label = "置底",
                    icon = Icons.Default.KeyboardArrowDown,
                    onClick = onToBottom,
                    enabled = hasSelection,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 保存/预览/打印
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSave,
                    enabled = !busy && !printing && hasElements,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QringPalette.surfaceSunken,
                        contentColor = QringPalette.textPrimary
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("保存", fontSize = 13.sp)
                }
                Button(
                    onClick = onPreview,
                    enabled = !busy && !printing && hasElements,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QringPalette.surfaceSunken,
                        contentColor = QringPalette.textPrimary
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Preview, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("预览", fontSize = 13.sp)
                }
                Button(
                    onClick = onPrint,
                    enabled = !busy && !printing && hasElements,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = BRAND),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (printing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("打印", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(Metrics.EDITOR_TOOL_HEIGHT.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = QringPalette.surfaceSunken,
            contentColor = QringPalette.textPrimary
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 12.sp)
    }
}
