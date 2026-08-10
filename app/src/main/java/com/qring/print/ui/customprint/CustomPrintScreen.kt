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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.print.model.CanvasElement
import com.qring.print.model.ElementKind
import com.qring.print.ui.theme.Metrics
import com.qring.print.ui.theme.ONLINE
import com.qring.print.ui.theme.QringPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomPrintScreen(
    navController: androidx.navigation.NavController,
    viewModel: CustomPrintViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()
    val fontFamilies by viewModel.fontFamilies.collectAsState()
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val canvasWidthDp = config.screenWidthDp.toFloat() - Metrics.PAGE_PADDING * 2

    LaunchedEffect(Unit) {
        viewModel.loadFonts()
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.insertImage(it) }
    }

    val codeTypeSheetState = rememberModalBottomSheetState()
    var showCodeTypePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 16.dp)
    ) {
        // 顶栏
        TopAppBar(
            title = { Text("自定义打印") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                TextButton(onClick = { viewModel.showSaveDialog() }) {
                    Text("保存模板", color = QringPalette.textPrimary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = QringPalette.surface,
                titleContentColor = QringPalette.textPrimary
            )
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.PAGE_PADDING.dp)
        ) {
            // 画布信息栏：方向切换 + 尺寸 + 提示
            CanvasHeader(
                landscape = uiState.doc.landscape,
                widthDots = if (uiState.doc.landscape) uiState.doc.height() else 384,
                heightDots = if (uiState.doc.landscape) 384 else uiState.doc.height(),
                onToggleLandscape = { viewModel.setLandscape(!uiState.doc.landscape) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 画布
            CanvasView(
                compositeBitmap = uiState.compositeBitmap,
                elements = uiState.doc.elements,
                selectedId = uiState.doc.selectedId,
                canvasWidthDp = canvasWidthDp,
                landscape = uiState.doc.landscape,
                onSelect = { viewModel.selectElement(it) },
                onMove = { id, dx, dy -> viewModel.moveSelected(dx, dy) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 提示
            Text(
                text = "点击画布中的选框或下方元素队列可编辑单个元素",
                fontSize = 11.sp,
                color = QringPalette.textSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 元素队列：点一下选中并编辑该元素
            ElementQueue(
                elements = uiState.doc.elements,
                selectedId = uiState.doc.selectedId,
                labelFor = viewModel::elementLabel,
                onTap = { id ->
                    viewModel.selectElement(id)
                    viewModel.showElementEditor()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 打印浓度
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = QringPalette.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "打印浓度",
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp,
                            color = QringPalette.textPrimary
                        )
                        Text(
                            text = if (uiState.thickness > 0) uiState.thickness.toString() else "默认",
                            fontSize = 13.sp,
                            color = QringPalette.textSecondary
                        )
                    }
                    Slider(
                        value = uiState.thickness.toFloat(),
                        onValueChange = { v ->
                            val rounded = Math.round(v)
                            viewModel.setThickness(if (rounded == 0) null else rounded)
                        },
                        valueRange = 0f..5f,
                        steps = 4,
                        enabled = !uiState.printing,
                        colors = SliderDefaults.colors(
                            thumbColor = QringPalette.brand,
                            activeTrackColor = QringPalette.brand
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 工具栏
            EditorToolbar(
                hasSelection = uiState.doc.selectedId.isNotEmpty(),
                hasElements = uiState.doc.elements.isNotEmpty(),
                busy = uiState.busy,
                printing = uiState.printing,
                onInsertText = viewModel::insertText,
                onInsertImage = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onInsertCode = {
                    if (showCodeTypePicker.not()) {
                        showCodeTypePicker = true
                    }
                },
                onDelete = viewModel::deleteSelected,
                onSave = viewModel::showSaveDialog,
                onPrint = viewModel::print,
                onPreview = viewModel::showPreview
            )

            // 结果
            if (uiState.resultMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.resultOk)
                            ONLINE.copy(alpha = 0.1f)
                        else
                            Color(0xFFFF4D4F).copy(alpha = 0.1f)
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = uiState.resultMessage,
                        modifier = Modifier.padding(12.dp),
                        color = if (uiState.resultOk) ONLINE else Color(0xFFFF4D4F),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    // 元素编辑器
    if (uiState.showEditor && uiState.doc.selectedId.isNotEmpty()) {
        ElementEditSheet(
            element = uiState.doc.selected()!!,
            fontFamilies = fontFamilies,
            revision = uiState.revision,
            onDismiss = viewModel::dismissElementEditor,
            onTextChange = viewModel::updateSelectedText,
            onCodeContentChange = viewModel::updateSelectedCodeContent,
            onCodeTypeChange = viewModel::updateSelectedCodeType,
            onDitherChange = viewModel::updateSelectedDither,
            onThresholdChange = viewModel::setSelectedImageThreshold,
            onSizeChange = viewModel::setSelectedSize,
            onScaleChange = viewModel::setSelectedScale,
            onRotationChange = viewModel::setSelectedRotation,
            onFlipHChange = viewModel::toggleFlipH,
            onFlipVChange = viewModel::toggleFlipV,
            onDelete = {
                viewModel.deleteSelected()
                viewModel.dismissElementEditor()
            },
            onTextOptionsChange = viewModel::updateSelectedTextOptions,
            onFontFamilyChange = viewModel::setElementFontFamily,
            onSwapImage = { uri ->
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        )
    }

    // 模板保存弹窗
    if (uiState.showTemplateDialog) {
        TemplateSaveDialog(
            name = uiState.templateName,
            onNameChange = viewModel::updateTemplateName,
            onSave = viewModel::confirmSave,
            onSaveAs = viewModel::confirmSaveAs,
            onDismiss = viewModel::dismissSaveDialog
        )
    }

    // 预览
    if (uiState.showPreview && uiState.compositeBitmap != null) {
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissPreview,
            sheetState = rememberModalBottomSheetState(),
            containerColor = QringPalette.pageBg
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Text(
                    text = "打印预览",
                    fontSize = 14.sp,
                    color = QringPalette.textSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.foundation.Image(
                    bitmap = uiState.compositeBitmap!!.asImageBitmap(),
                    contentDescription = "预览",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(Color.White),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
    }

    // 码制选择
    if (showCodeTypePicker) {
        CodeTypePickerSheet(
            onSelect = { index ->
                viewModel.insertCode(index)
                showCodeTypePicker = false
            },
            onDismiss = { showCodeTypePicker = false }
        )
    }
}

@Composable
private fun TextButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { content() }
}

/** 画布信息栏：横竖排切换 + 尺寸显示 */
@Composable
private fun CanvasHeader(
    landscape: Boolean,
    widthDots: Int,
    heightDots: Int,
    onToggleLandscape: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "宽 ${widthDots} 点(${String.format("%.1f", widthDots / 8.0)}mm) × 高 ${heightDots} 点(${String.format("%.1f", heightDots / 8.0)}mm)",
            fontSize = 11.sp,
            color = QringPalette.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(QringPalette.surface)
                .clickable { onToggleLandscape() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = if (landscape) "横排 ▾" else "竖排 ▾",
                fontSize = 12.sp,
                color = QringPalette.brand
            )
        }
    }
}

/** 元素队列：文字1、图片1、文字2…，点一下选中并进入编辑 */
@Composable
private fun ElementQueue(
    elements: List<CanvasElement>,
    selectedId: String,
    labelFor: (CanvasElement) -> String,
    onTap: (String) -> Unit
) {
    Column {
        Text(
            text = "元素",
            fontSize = 12.sp,
            color = QringPalette.textSecondary,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        if (elements.isEmpty()) {
            Text(
                text = "画布是空的，用下方工具栏添加文字 / 图片 / 条码",
                fontSize = 12.sp,
                color = QringPalette.textSecondary
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(elements, key = { it.id }) { el ->
                    val selected = el.id == selectedId
                    val icon = when (el.kind) {
                        ElementKind.TEXT -> Icons.Default.Description
                        ElementKind.IMAGE -> Icons.Default.Image
                        ElementKind.CODE -> Icons.Default.QrCode
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) QringPalette.brand else QringPalette.surfaceSunken)
                            .clickable { onTap(el.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (selected) Color.White else QringPalette.textPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = labelFor(el),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (selected) Color.White else QringPalette.textPrimary
                        )
                    }
                }
            }
        }
    }
}
