package com.qring.print.ui.customprint

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val canvasWidthDp = config.screenWidthDp.toFloat() - Metrics.PAGE_PADDING * 2

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
            // 画布
            CanvasView(
                compositeBitmap = uiState.compositeBitmap,
                elements = uiState.doc.elements,
                selectedId = uiState.doc.selectedId,
                canvasWidthDp = canvasWidthDp,
                onSelect = { viewModel.selectElement(it) },
                onMove = { id, dx, dy -> viewModel.moveSelected(dx, dy) }
            )

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
                onToTop = viewModel::toTopSelected,
                onToBottom = viewModel::toBottomSelected,
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
            onDismiss = viewModel::dismissElementEditor,
            onTextChange = viewModel::updateSelectedText,
            onCodeContentChange = viewModel::updateSelectedCodeContent,
            onCodeTypeChange = viewModel::updateSelectedCodeType,
            onDitherChange = viewModel::updateSelectedDither,
            onTextOptionsChange = viewModel::updateSelectedTextOptions,
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
    androidx.compose.material3.TextButton(onClick = onClick, content = content)
}
