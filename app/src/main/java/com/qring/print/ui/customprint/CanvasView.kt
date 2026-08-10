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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.qring.print.ui.theme.Metrics
import com.qring.print.ui.theme.ONLINE
import com.qring.print.ui.theme.QringPalette
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
    landscape: Boolean,
    onSelect: (String) -> Unit,
    onMove: (String, Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // scale 始终基于 384（打印机宽度），不随 contentHeight 变化
    val scale = canvasWidthDp / 384f

    val bmpW = compositeBitmap?.width ?: 384
    val bmpH = compositeBitmap?.height ?: 384

    // 横排时使用稳定参考宽度：只增不减，避免拖拽中 contentHeight 变化导致元素不动
    // landscape 变化时重置
    var refW by remember(landscape) { mutableStateOf(bmpW) }
    if (bmpW > refW) refW = bmpW

    // 用 rememberUpdatedState 拿到最新的值，避免 pointerInput 重启
    val currentScale by rememberUpdatedState(scale)
    val currentLandscape by rememberUpdatedState(landscape)

    // Box 高度：横排固定为 384*scale，竖排跟随内容
    val boxHeightDp = if (landscape) (384 * scale) else (bmpH * scale)

    // 横排时内容宽度 = refW * scale，需要水平滚动
    val landscapeScroll = rememberScrollState()
    // 自动滚动到最右侧（内容所在位置）
    LaunchedEffect(refW, landscape) {
        if (landscape) {
            landscapeScroll.scrollTo(refW * scale.toInt())
        }
    }

    // 横排可见宽度（屏幕上的）
    val viewportWidthDp = canvasWidthDp
    val contentWidthDp = if (landscape) (refW * scale) else canvasWidthDp
    val showScrollbar = landscape && contentWidthDp > viewportWidthDp

    Column(modifier = modifier) {
        val baseModifier = if (landscape) {
            Modifier
                .fillMaxWidth()
                .height(boxHeightDp.dp)
                .horizontalScroll(landscapeScroll)
                .clip(RoundedCornerShape(12.dp))
                .background(QringPalette.paper)
                .border(1.dp, QringPalette.paperEdge, RoundedCornerShape(12.dp))
        } else {
            Modifier
                .fillMaxWidth()
                .height(boxHeightDp.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(QringPalette.paper)
                .border(1.dp, QringPalette.paperEdge, RoundedCornerShape(12.dp))
        }

        Box(modifier = baseModifier) {
            // 横排时内容区域宽度 = refW * scale，竖排填满宽度
            Box(modifier = Modifier.width(contentWidthDp.dp).height(boxHeightDp.dp)) {
                // 合成图
                if (compositeBitmap != null) {
                    if (landscape) {
                        // 横排：bitmap 右对齐（dotY=0 在右侧）
                        val bmpOffsetX = (refW - bmpW) * scale
                        Image(
                            bitmap = compositeBitmap.asImageBitmap(),
                            contentDescription = "画布预览",
                            modifier = Modifier
                                .offset(x = bmpOffsetX.dp)
                                .width((bmpW * scale).dp)
                                .height((bmpH * scale).dp),
                            contentScale = ContentScale.FillBounds
                        )
                    } else {
                        Image(
                            bitmap = compositeBitmap.asImageBitmap(),
                            contentDescription = "画布预览",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                }

                // 元素选择框（统一用 dp：offset 与 size 单位一致，保证选框和预览对齐）
                elements.forEach { el ->
                    val isSelected = el.id == selectedId
                    // 横排时把元素坐标旋转映射到显示空间
                    val boxX: Int
                    val boxY: Int
                    val boxW: Int
                    val boxH: Int
                    if (landscape) {
                        // 横排：元素内容已预旋转 270°，画布旋转 90° 后内容正立
                        // 视觉尺寸 = (dotW, dotH)，位置 = (refW - dotY - dotW, dotX)
                        boxX = refW - el.dotY - el.dotW
                        boxY = el.dotX
                        boxW = el.dotW
                        boxH = el.dotH
                    } else {
                        boxX = el.dotX
                        boxY = el.dotY
                        boxW = el.dotW
                        boxH = el.dotH
                    }
                    // 统一用 scale（固定 384 基准），横排和竖排一致，元素不拉伸
                    val xDp = (boxX * scale).dp
                    val yDp = (boxY * scale).dp
                    val wDp = (boxW * scale).dp
                    val hDp = (boxH * scale).dp

                    Box(
                        modifier = Modifier
                            .offset(x = xDp, y = yDp)
                            .size(width = wDp, height = hDp)
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = QringPalette.selectOutline,
                                shape = RoundedCornerShape(2.dp)
                            )
                            .clickable { onSelect(el.id) }
                            .then(
                                if (isSelected) {
                                    Modifier.pointerInput(el.id, landscape) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            val dxDot = (dragAmount.x / currentScale).roundToInt()
                                            val dyDot = (dragAmount.y / currentScale).roundToInt()
                                            if (dxDot != 0 || dyDot != 0) {
                                                if (currentLandscape) {
                                                    onMove(el.id, dyDot, -dxDot)
                                                } else {
                                                    onMove(el.id, dxDot, dyDot)
                                                }
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
                                    .size(Metrics.HANDLE_SIZE.dp)
                                    .clip(CircleShape)
                                    .background(QringPalette.handleFill)
                                    .border(2.dp, Color.White, CircleShape)
                            )
                        }
                    }
                }
            }  // 内层 Box
        }  // 外层 Box

        // 横排滚动位置指示条
        if (showScrollbar) {
            Spacer(modifier = Modifier.height(4.dp))
            CanvasScrollBar(
                scrollState = landscapeScroll,
                contentWidthDp = contentWidthDp,
                viewportWidthDp = viewportWidthDp,
                scale = scale,
                refW = refW
            )
        }
    }
}

/**
 * 横排画布滚动位置指示条。
 * 显示当前可见区域在内容中的位置，并标注点数坐标。
 */
@Composable
private fun CanvasScrollBar(
    scrollState: androidx.compose.foundation.ScrollState,
    contentWidthDp: Float,
    viewportWidthDp: Float,
    scale: Float,
    refW: Int
) {
    val scrollPx = scrollState.value
    val maxScroll = scrollState.maxValue.coerceAtLeast(1)
    val scrollRatio = scrollPx.toFloat() / maxScroll

    // 指示条宽度比例
    val barRatio = viewportWidthDp / contentWidthDp
    val barWidthDp = (viewportWidthDp * barRatio).coerceAtLeast(20f)

    // 指示条位置
    val trackWidthDp = viewportWidthDp - barWidthDp
    val barOffsetDp = trackWidthDp * scrollRatio

    // 当前可见区域对应的点数范围
    val visibleStartDot = ((refW - scrollPx / scale).roundToInt()).coerceIn(0, refW)
    val visibleEndDot = ((refW - (scrollPx + viewportWidthDp / scale) / 1f).roundToInt()).coerceIn(0, refW)
    // 实际：左边的点 = refW - (scrollPx/scale + viewportWidthDp/scale), 右边的点 = refW - scrollPx/scale
    val rightDot = (refW - scrollPx / scale).roundToInt()
    val leftDot = (refW - (scrollPx + viewportWidthDp / scale) / 1f / 1f).roundToInt()
    val leftDotClamped = leftDot.coerceIn(0, refW)

    Column(modifier = Modifier.fillMaxWidth()) {
        // 点数标注
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${leftDotClamped}点",
                fontSize = 9.sp,
                color = QringPalette.textSecondary
            )
            Text(
                text = "${rightDot.coerceIn(0, refW)}点",
                fontSize = 9.sp,
                color = QringPalette.textSecondary
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // 轨道
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(QringPalette.surfaceSunken)
        ) {
            // 滑块
            Box(
                modifier = Modifier
                    .offset(x = barOffsetDp.dp)
                    .width(barWidthDp.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(QringPalette.brand)
            )
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
                    colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand),
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
