package com.qring.printer.ui.template

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.printer.model.TemplateRecord
import com.qring.printer.ui.theme.Metrics
import com.qring.printer.ui.theme.QringPalette
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TemplateScreen(
    onOpenTemplate: (String) -> Unit,
    viewModel: TemplateViewModel = viewModel()
) {
    val templates by viewModel.templates.collectAsState()
    val isEmpty by viewModel.isEmpty.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadTemplates()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QringPalette.pageBg)
            .statusBarsPadding()
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(QringPalette.surface)
                .padding(horizontal = Metrics.PAGE_PADDING.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "我的模板",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = QringPalette.textPrimary
            )
        }

        if (isEmpty) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.GridView,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = QringPalette.textSecondary.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "还没有模板",
                        color = QringPalette.textSecondary,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "在自定义打印中保存一个吧",
                        color = QringPalette.textSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(Metrics.PAGE_PADDING.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(templates, key = { it.id }) { template ->
                    TemplateCard(
                        template = template,
                        onClick = { onOpenTemplate(template.id) },
                        onDelete = { viewModel.deleteTemplate(template.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: TemplateRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
    ) {
        Column {
            // 缩略图
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                val thumbFile = File(template.thumbnailPath)
                if (thumbFile.exists()) {
                    val bmp = remember(thumbFile.absolutePath) {
                        BitmapFactory.decodeFile(thumbFile.absolutePath)
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                } else {
                    Text("无图", color = QringPalette.textSecondary, fontSize = 12.sp)
                }
            }

            // 信息
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = QringPalette.textPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = "${template.elements.size} 元素",
                        fontSize = 11.sp,
                        color = QringPalette.textSecondary
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = QringPalette.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
