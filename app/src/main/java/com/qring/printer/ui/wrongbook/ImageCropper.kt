package com.qring.printer.ui.wrongbook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import android.graphics.Bitmap
import com.qring.printer.ui.theme.QringPalette

/**
 * 图片裁剪组件。
 *
 * 用户可以通过拖动四角调整裁剪框，也可以拖动裁剪框内部移动位置。
 * 确认后调用 onCrop 返回裁剪后的 Bitmap。
 */
@Composable
fun ImageCropper(
    bitmap: Bitmap,
    onCrop: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    val density = LocalDensity.current
    val imageBmp = remember(bitmap) { bitmap.asImageBitmap() }

    // 裁剪框状态（归一化坐标 0~1）
    var cropLeft by remember { mutableStateOf(0.1f) }
    var cropTop by remember { mutableStateOf(0.1f) }
    var cropRight by remember { mutableStateOf(0.9f) }
    var cropBottom by remember { mutableStateOf(0.9f) }

    // 图片显示区域大小
    var canvasWidth by remember { mutableStateOf(0f) }
    var canvasHeight by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 图片层
        Image(
            bitmap = imageBmp,
            contentDescription = "裁剪图片",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // 裁剪框 + 遮罩层
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, _, _ ->
                        // 整体移动
                        val w = cropRight - cropLeft
                        val h = cropBottom - cropTop
                        val dx = pan.x / canvasWidth
                        val dy = pan.y / canvasHeight
                        val newLeft = (cropLeft + dx).coerceIn(0f, 1f - w)
                        val newRight = newLeft + w
                        val newTop = (cropTop + dy).coerceIn(0f, 1f - h)
                        val newBottom = newTop + h
                        if (newRight <= 1f && newBottom <= 1f) {
                            cropLeft = newLeft
                            cropRight = newRight
                            cropTop = newTop
                            cropBottom = newBottom
                        }
                    }
                }
        ) {
            canvasWidth = size.width
            canvasHeight = size.height

            val l = cropLeft * size.width
            val t = cropTop * size.height
            val r = cropRight * size.width
            val b = cropBottom * size.height

            // 半透明遮罩（裁剪框外）
            val maskColor = Color.Black.copy(alpha = 0.6f)
            // 上
            drawRect(maskColor, topLeft = Offset(0f, 0f), size = Size(size.width, t))
            // 下
            drawRect(maskColor, topLeft = Offset(0f, b), size = Size(size.width, size.height - b))
            // 左
            drawRect(maskColor, topLeft = Offset(0f, t), size = Size(l, b - t))
            // 右
            drawRect(maskColor, topLeft = Offset(r, t), size = Size(size.width - r, b - t))

            // 裁剪框边框
            val borderColors = Color.White
            drawRect(
                color = borderColors,
                topLeft = Offset(l, t),
                size = Size(r - l, b - t),
                style = Stroke(width = 3f)
            )

            // 四角拖拽标记
            val cornerLen = 30f
            val cornerWidth = 6f
            // 左上
            drawRect(borderColors, Offset(l, t), Size(cornerLen, cornerWidth))
            drawRect(borderColors, Offset(l, t), Size(cornerWidth, cornerLen))
            // 右上
            drawRect(borderColors, Offset(r - cornerLen, t), Size(cornerLen, cornerWidth))
            drawRect(borderColors, Offset(r - cornerWidth, t), Size(cornerWidth, cornerLen))
            // 左下
            drawRect(borderColors, Offset(l, b - cornerWidth), Size(cornerLen, cornerWidth))
            drawRect(borderColors, Offset(l, b - cornerLen), Size(cornerWidth, cornerLen))
            // 右下
            drawRect(borderColors, Offset(r - cornerLen, b - cornerWidth), Size(cornerLen, cornerWidth))
            drawRect(borderColors, Offset(r - cornerWidth, b - cornerLen), Size(cornerWidth, cornerLen))

            // 三分线
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            for (i in 1..2) {
                val x = l + (r - l) / 3 * i
                drawLine(
                    borderColors.copy(alpha = 0.6f),
                    start = Offset(x, t),
                    end = Offset(x, b),
                    strokeWidth = 1f,
                    pathEffect = dashEffect
                )
                val y = t + (b - t) / 3 * i
                drawLine(
                    borderColors.copy(alpha = 0.6f),
                    start = Offset(l, y),
                    end = Offset(r, y),
                    strokeWidth = 1f,
                    pathEffect = dashEffect
                )
            }
        }

        // 四角拖拽
        CornerHandle(
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            onDrag = { dx, dy ->
                cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - 0.1f)
                cropTop = (cropTop + dy).coerceIn(0f, cropBottom - 0.1f)
            },
            alignFractionX = cropLeft,
            alignFractionY = cropTop
        )
        CornerHandle(
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            onDrag = { dx, dy ->
                cropRight = (cropRight + dx).coerceIn(cropLeft + 0.1f, 1f)
                cropTop = (cropTop + dy).coerceIn(0f, cropBottom - 0.1f)
            },
            alignFractionX = cropRight,
            alignFractionY = cropTop
        )
        CornerHandle(
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            onDrag = { dx, dy ->
                cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - 0.1f)
                cropBottom = (cropBottom + dy).coerceIn(cropTop + 0.1f, 1f)
            },
            alignFractionX = cropLeft,
            alignFractionY = cropBottom
        )
        CornerHandle(
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            onDrag = { dx, dy ->
                cropRight = (cropRight + dx).coerceIn(cropLeft + 0.1f, 1f)
                cropBottom = (cropBottom + dy).coerceIn(cropTop + 0.1f, 1f)
            },
            alignFractionX = cropRight,
            alignFractionY = cropBottom
        )

        // 底部按钮栏
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Button(
                onClick = {
                    // 计算实际裁剪区域
                    // 图片是 Fit 缩放，需要计算实际图片在 canvas 中的位置
                    val imageW = bitmap.width.toFloat()
                    val imageH = bitmap.height.toFloat()
                    val canvasW = canvasWidth
                    val canvasH = canvasHeight
                    if (canvasW <= 0 || canvasH <= 0) return@Button

                    // Fit 缩放比
                    val scale = minOf(canvasW / imageW, canvasH / imageH)
                    val scaledW = imageW * scale
                    val scaledH = imageH * scale
                    val offsetX = (canvasW - scaledW) / 2f
                    val offsetY = (canvasH - scaledH) / 2f

                    // 裁剪框在 canvas 中的像素坐标
                    val pxLeft = cropLeft * canvasW
                    val pxTop = cropTop * canvasH
                    val pxRight = cropRight * canvasW
                    val pxBottom = cropBottom * canvasH

                    // 映射到原图坐标
                    val bmpX = ((pxLeft - offsetX) / scale).toInt().coerceIn(0, bitmap.width - 1)
                    val bmpY = ((pxTop - offsetY) / scale).toInt().coerceIn(0, bitmap.height - 1)
                    val bmpW = ((pxRight - pxLeft) / scale).toInt().coerceIn(1, bitmap.width - bmpX)
                    val bmpH = ((pxBottom - pxTop) / scale).toInt().coerceIn(1, bitmap.height - bmpY)

                    val cropped = Bitmap.createBitmap(bitmap, bmpX, bmpY, bmpW, bmpH)
                    onCrop(cropped)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("确认裁剪", fontSize = 16.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun CornerHandle(
    canvasWidth: Float,
    canvasHeight: Float,
    onDrag: (Float, Float) -> Unit,
    alignFractionX: Float,
    alignFractionY: Float
) {
    if (canvasWidth <= 0 || canvasHeight <= 0) return

    val handleSize = 48.dp
    val handlePx = with(LocalDensity.current) { handleSize.toPx() }

    Box(
        modifier = Modifier
            .offset(
                x = with(LocalDensity.current) { (alignFractionX * canvasWidth - handlePx / 2).toDp() },
                y = with(LocalDensity.current) { (alignFractionY * canvasHeight - handlePx / 2).toDp() }
            )
            .size(handleSize)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x / canvasWidth, dragAmount.y / canvasHeight)
                }
            }
    )
}
