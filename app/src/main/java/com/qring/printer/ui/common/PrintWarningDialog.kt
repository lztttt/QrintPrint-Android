package com.qring.printer.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.qring.printer.model.ConnState
import com.qring.printer.model.PaperState
import com.qring.printer.model.PrinterStatus
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.ui.theme.QringPalette

/**
 * 打印前状态检查弹窗。
 *
 * 进入打印功能时自动检查：未连接 / 缺纸 / 电量低于5%。
 * 如果有问题则弹出提示，用户可以选择返回或继续。
 *
 * 用法：在打印页面的 Column 最外层直接调用，
 * 它会在进入时自动触发一次检查。
 */
@Composable
fun PrintWarningDialog(
    onContinue: () -> Unit = {},
    onGoBack: () -> Unit = {}
) {
    val printerStatus by PrinterStatusRepository.state.collectAsState()
    var showWarning by remember { mutableStateOf(false) }
    var warningMessage by remember { mutableStateOf("") }

    // 进入页面时检查一次
    LaunchedEffect(Unit) {
        val msg = checkPrintWarning(printerStatus)
        if (msg != null) {
            warningMessage = msg
            showWarning = true
        }
    }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = {
                showWarning = false
                onGoBack()
            },
            title = { Text("无法打印", color = QringPalette.textPrimary) },
            text = { Text(warningMessage, color = QringPalette.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showWarning = false
                    onGoBack()
                }) {
                    Text("返回", color = QringPalette.brand)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showWarning = false
                    onContinue()
                }) {
                    Text("仍然继续", color = QringPalette.textSecondary)
                }
            }
        )
    }
}

/**
 * 检查打印条件。返回 null 表示可以打印，否则返回警告文案。
 */
fun checkPrintWarning(status: PrinterStatus): String? {
    return when {
        status.connState != ConnState.CONNECTED ->
            "打印机未连接，请先在首页连接打印机后再试。"
        status.paperState == PaperState.NO_PAPER ->
            "打印机缺纸，请装好热敏纸后再试。"
        status.batteryPercent != null && status.batteryPercent < 5 ->
            "打印机电量过低（${status.batteryPercent}%），请充电后再试。"
        else -> null
    }
}
