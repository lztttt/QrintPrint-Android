package com.qring.printer.ui.wrongbook

import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.qring.printer.ui.common.PrintWarningDialog
import com.qring.printer.ui.theme.Metrics
import com.qring.printer.ui.theme.ONLINE
import com.qring.printer.ui.theme.QringPalette
import com.qring.printer.data.HistoryPayloadHolder
import com.qring.printer.model.HIST_TYPE_WRONGBOOK

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WrongBookScreen(
    navController: NavHostController,
    viewModel: WrongBookViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // 检查是否从历史记录跳转过来
    androidx.compose.runtime.LaunchedEffect(Unit) {
        HistoryPayloadHolder.consumeRecord()?.let { record ->
            if (record.typeName == HIST_TYPE_WRONGBOOK && record.thumbnailPath.isNotEmpty()) {
                viewModel.loadFromHistory(record.thumbnailPath, record.payload)
            }
        }
    }

    var currentPhotoPath by remember { mutableStateOf<String?>(null) }
    val takePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            currentPhotoPath?.let { path ->
                try {
                    val bitmap = decodeFileToBitmap(path)
                    viewModel.setOriginalBitmap(bitmap)
                } catch (e: Exception) { toast(context, "拍照加载失败") }
            }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera(context) { path, uri ->
                currentPhotoPath = path
                takePhotoLauncher.launch(uri)
            }
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val bitmap = decodeUriToBitmap(context, uri)
                viewModel.setOriginalBitmap(bitmap)
            } catch (e: Exception) { }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(QringPalette.pageBg)) {
        TopAppBar(
            title = { Text("错题本") },
            navigationIcon = {
                IconButton(onClick = { viewModel.backToSelect(); navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = QringPalette.surface, titleContentColor = QringPalette.textPrimary)
        )

        when (state.step) {
            WrongBookStep.SELECT -> {
                SelectStep(
                    onCamera = {
                        if (android.Manifest.permission.CAMERA.let { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                            launchCamera(context) { path, uri -> currentPhotoPath = path; takePhotoLauncher.launch(uri) }
                        } else {
                            cameraPermission.launch(android.Manifest.permission.CAMERA)
                        }
                    },
                    onGallery = { pickImageLauncher.launch("image/*") }
                )
            }
            WrongBookStep.CROP -> {
                state.originalBitmap?.let { bmp ->
                    ImageCropper(bitmap = bmp, onCrop = { viewModel.setCroppedBitmap(it) }, onCancel = { viewModel.backToSelect() })
                }
            }
            WrongBookStep.ENHANCE -> { EnhanceStep(state = state, viewModel = viewModel) }
        }
    }

    PrintWarningDialog(onGoBack = { navController.popBackStack() })
}

// ── 选择步骤 ────────────────────────────────────────────

@Composable
private fun SelectStep(onCamera: () -> Unit, onGallery: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = Metrics.PAGE_PADDING.dp).padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("拍照或上传错题图片", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = QringPalette.textPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("裁剪 → 文档增强去阴影 → 可旋转/翻转打印 → 保存标签", fontSize = 12.sp, color = QringPalette.textSecondary)
        Spacer(modifier = Modifier.height(40.dp))

        Card(modifier = Modifier.fillMaxWidth().height(120.dp).clickable(onClick = onCamera), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = QringPalette.surface)) {
            Row(modifier = Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(QringPalette.brand), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column { Text("拍照", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = QringPalette.textPrimary); Text("使用相机拍摄错题", fontSize = 12.sp, color = QringPalette.textSecondary) }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth().height(120.dp).clickable(onClick = onGallery), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = QringPalette.surface)) {
            Row(modifier = Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF6FCF97)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Image, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column { Text("从相册上传", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = QringPalette.textPrimary); Text("选择已有的错题图片", fontSize = 12.sp, color = QringPalette.textSecondary) }
            }
        }
    }
}

// ── 增强步骤 ────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EnhanceStep(state: WrongBookState, viewModel: WrongBookViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Metrics.PAGE_PADDING.dp).padding(top = 12.dp, bottom = 12.dp)
    ) {
        if (state.processing) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = QringPalette.brand)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(state.processingHint, fontSize = 14.sp, color = QringPalette.textPrimary)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // 预览
        state.previewBitmap?.let { bmp ->
            Text("打印预览", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = QringPalette.textPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                    val canvasWidthDp = maxWidth.value
                    val scale = canvasWidthDp / bmp.width
                    val contentH = bmp.height * scale
                    Box(modifier = Modifier.fillMaxWidth().height(300.dp).verticalScroll(rememberScrollState())) {
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = "预览", modifier = Modifier.width(canvasWidthDp.dp).height(contentH.dp), contentScale = ContentScale.FillBounds)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("尺寸: ${bmp.width} × ${bmp.height} 点", fontSize = 11.sp, color = QringPalette.textSecondary)
        } ?: run {
            state.enhancedBitmap?.let { bmp ->
                Card(modifier = Modifier.fillMaxWidth().height(300.dp), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = "增强结果", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 编辑选项
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("图片编辑", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.textPrimary)
                Spacer(modifier = Modifier.height(8.dp))

                // 旋转 / 翻转 / 反色 三个图标按钮
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    // 旋转 90°
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { viewModel.setRotation(state.rotation + 90) }) {
                            Icon(Icons.Default.RotateRight, contentDescription = "旋转90°", tint = QringPalette.brand)
                        }
                        Text("旋转90°", fontSize = 10.sp, color = QringPalette.textSecondary)
                    }
                    // 水平翻转
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { viewModel.toggleFlipH() }) {
                            Icon(Icons.Default.Flip, contentDescription = "水平翻转", tint = if (state.flipH) QringPalette.brand else QringPalette.textSecondary)
                        }
                        Text("翻转", fontSize = 10.sp, color = QringPalette.textSecondary)
                    }
                    // 反色
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { viewModel.toggleInvert() }) {
                            Icon(Icons.Default.InvertColors, contentDescription = "反色", tint = if (state.invert) QringPalette.brand else QringPalette.textSecondary)
                        }
                        Text("反色", fontSize = 10.sp, color = QringPalette.textSecondary)
                    }
                    // 旋转角度显示
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Text("${state.rotation}°", fontSize = 13.sp, color = QringPalette.brand, fontWeight = FontWeight.Medium)
                        }
                        Text("角度", fontSize = 10.sp, color = QringPalette.textSecondary)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 标签管理
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("标签", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = QringPalette.textPrimary)
                Spacer(modifier = Modifier.height(8.dp))

                if (state.selectedTags.isNotEmpty()) {
                    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.selectedTags.forEach { tag ->
                            FilterChip(selected = true, onClick = { viewModel.toggleTag(tag) }, label = { Text("#$tag", fontSize = 11.sp) })
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (state.allTags.isNotEmpty()) {
                    Text("选择已有标签", fontSize = 11.sp, color = QringPalette.textSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.allTags.filter { it !in state.selectedTags }.forEach { tag ->
                            FilterChip(selected = false, onClick = { viewModel.toggleTag(tag) }, label = { Text("#$tag", fontSize = 11.sp) })
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.newTagInput, onValueChange = viewModel::setNewTagInput,
                        modifier = Modifier.weight(1f), placeholder = { Text("新标签", fontSize = 13.sp) },
                        textStyle = TextStyle(fontSize = 13.sp), singleLine = true, shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.addTag(state.newTagInput) }, enabled = state.newTagInput.isNotBlank()) {
                        Icon(Icons.Default.Add, contentDescription = "添加标签", tint = QringPalette.brand)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (state.resultMessage.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = if (state.resultOk) ONLINE.copy(alpha = 0.1f) else Color(0xFFFF4D4F).copy(alpha = 0.1f))) {
                Text(state.resultMessage, modifier = Modifier.padding(12.dp), color = if (state.resultOk) ONLINE else Color(0xFFFF4D4F), fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // 操作按钮
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { viewModel.enhance() }, enabled = !state.processing,
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = QringPalette.surfaceSunken, contentColor = QringPalette.textPrimary),
                shape = RoundedCornerShape(10.dp)
            ) { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(6.dp)); Text("重新增强", fontSize = 13.sp) }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { viewModel.saveToWrongBook() }, enabled = !state.saving && state.enhancedBitmap != null,
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6FCF97), contentColor = Color.White),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (state.saving) { CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp) }
                else { Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp)) }
                Spacer(modifier = Modifier.width(6.dp)); Text("保存到错题本", fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.print() }, enabled = !state.processing && !state.printing && state.enhancedBitmap != null,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand), shape = RoundedCornerShape(12.dp)
        ) {
            if (state.printing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp)); Text("打印中…", fontSize = 15.sp)
            } else {
                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp)); Text("打印", fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        androidx.compose.material3.TextButton(onClick = { viewModel.backToCrop() }) {
            Text("返回裁剪", fontSize = 13.sp, color = QringPalette.textSecondary)
        }
    }
}

// ── 工具函数 ────────────────────────────────────────────

private fun launchCamera(context: android.content.Context, onReady: (path: String, uri: Uri) -> Unit) {
    val photoFile = java.io.File.createTempFile("wrong_book_photo", ".jpg", context.getExternalFilesDir(Environment.DIRECTORY_PICTURES))
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", photoFile)
    onReady(photoFile.absolutePath, uri)
}

private fun decodeUriToBitmap(context: android.content.Context, uri: Uri): Bitmap {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, 2048)
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    return context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) ?: throw IllegalArgumentException("无法解码图片") } ?: throw IllegalArgumentException("无法打开图片")
}

private fun decodeFileToBitmap(path: String): Bitmap {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, 2048)
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    return android.graphics.BitmapFactory.decodeFile(path, opts) ?: throw IllegalArgumentException("无法解码图片")
}

private fun calculateSampleSize(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1; val longest = maxOf(width, height); while (longest / sample > maxSide) sample *= 2; return sample
}

private fun toast(context: android.content.Context, msg: String) { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() }
