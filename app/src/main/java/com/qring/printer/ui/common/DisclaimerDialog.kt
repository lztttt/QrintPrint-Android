package com.qring.printer.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qring.printer.ui.theme.QringPalette

/**
 * 首次使用免责声明弹窗。
 *
 * 首次打开 App 时展示，用户需同意才能继续使用。
 * 之后不再弹出（通过 SharedPreferences 记录）。
 */
@Composable
fun DisclaimerDialog() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("qringprint_disclaimer", Context.MODE_PRIVATE) }
    val agreed = remember { prefs.getBoolean("agreed", false) }
    var show by remember { mutableStateOf(!agreed) }

    if (show) {
        AlertDialog(
            onDismissRequest = { /* 不能关闭，必须同意 */ },
            title = {
                Text(
                    text = "免责声明",
                    fontWeight = FontWeight.Bold,
                    color = QringPalette.textPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "本应用（QringPrint）为个人开发的第三方客户端，仅供个人学习交流使用，请在下载后 24 小时内删除。",
                        fontSize = 13.sp,
                        color = QringPalette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "· 本应用基于开源项目移植，不拥有任何版权。\n· 打印内容版权归原内容所有者。\n· 请勿用于商业用途。\n· 使用本应用产生的一切后果由用户自行承担。",
                        fontSize = 12.sp,
                        color = QringPalette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "QQ 交流群：419668261",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = QringPalette.brand,
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("QQ群号", "419668261"))
                            Toast.makeText(context, "群号已复制：419668261", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "点击群号可复制到剪贴板，欢迎加入交流反馈。",
                        fontSize = 11.sp,
                        color = QringPalette.textSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putBoolean("agreed", true).apply()
                    show = false
                }) {
                    Text("我已阅读并同意", color = QringPalette.brand)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    // 拒绝则退出应用
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(0)
                }) {
                    Text("不同意并退出", color = QringPalette.textSecondary)
                }
            }
        )
    }
}
