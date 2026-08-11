package com.qring.printer.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.printer.MainViewModel
import com.qring.printer.R
import com.qring.printer.model.ConnState
import com.qring.printer.ui.navigation.Routes
import com.qring.printer.ui.theme.Metrics
import com.qring.printer.ui.theme.ON_CARD_MUTED
import com.qring.printer.ui.theme.ON_CARD_SUBTITLE
import com.qring.printer.ui.theme.ON_CARD_TILE
import com.qring.printer.ui.theme.ONLINE
import com.qring.printer.ui.theme.QringPalette
import com.qring.printer.ui.theme.WARNING

// ── 首页 ──────────────────────────────────────────────────

@Composable
fun HomeScreen(
    navController: androidx.navigation.NavController,
    viewModel: MainViewModel = viewModel()
) {
    val printerStatus by viewModel.printerStatus.collectAsState()
    var showDevicePicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏
        TopBar()

        // 可滚动内容
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.PAGE_PADDING.dp)
                .padding(top = Metrics.PAGE_PADDING.dp)
                .padding(bottom = (Metrics.TAB_BAR_HEIGHT + 24).dp)
        ) {
            // 打印机状态卡
            PrinterStatusCard(
                status = printerStatus,
                alias = if (printerStatus.connState == ConnState.CONNECTED)
                    viewModel.deviceAlias(printerStatus.deviceId) else null,
                onClick = { showDevicePicker = true }
            )

            // 调试模式卡片
            DebugCard(
                status = printerStatus,
                rssi = if (printerStatus.connState == ConnState.CONNECTED)
                    viewModel.deviceRssi(printerStatus.deviceId) else null,
                onRefreshInfo = { viewModel.refreshDeviceInfo() },
                onFeedPaper = { dots, callback -> viewModel.feedPaper(dots, callback) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.quick_print),
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = QringPalette.textPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 快速打印宫格
            QuickActionGrid(
                onTextClick = { navController.navigate(Routes.TEXT_PRINT) },
                onImageClick = { navController.navigate(Routes.IMAGE_PRINT) },
                onCodeClick = { navController.navigate(Routes.CODE_PRINT) },
                onCustomClick = { navController.navigate(Routes.CUSTOM_PRINT) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "更多功能",
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = QringPalette.textPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            MoreActionGrid(
                onScheduleClick = { navController.navigate(Routes.SCHEDULE) },
                onLabelClick = { navController.navigate(Routes.LABEL) },
                onCalendarClick = { navController.navigate(Routes.CALENDAR) },
                onTodoClick = { navController.navigate(Routes.TODO) },
                onWordbookClick = { navController.navigate(Routes.WORDBOOK) },
                onMathClick = { navController.navigate(Routes.MATH) }
            )
        }
    }

    // 设备选择弹窗
    if (showDevicePicker) {
        DevicePickerDialog(
            viewModel = viewModel,
            onDismiss = { showDevicePicker = false }
        )
    }
}

// ── 顶栏 ──────────────────────────────────────────────────

@Composable
private fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(QringPalette.surface)
            .statusBarsPadding()
            .height((Metrics.TOP_BAR_HEIGHT).dp)
            .padding(horizontal = Metrics.PAGE_PADDING.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Logo badge
        Box(
            modifier = Modifier
                .size(Metrics.BRAND_BADGE_SIZE.dp)
                .clip(RoundedCornerShape(Metrics.BRAND_BADGE_RADIUS.dp))
                .background(QringPalette.brand),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Print,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(Metrics.BRAND_BADGE_ICON.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = QringPalette.textPrimary
            )
            Text(
                text = stringResource(R.string.app_subtitle),
                fontSize = 11.sp,
                color = QringPalette.textSecondary
            )
        }
    }
}

// ── 打印机状态卡 ──────────────────────────────────────────

@Composable
fun PrinterStatusCard(
    status: com.qring.printer.model.PrinterStatus,
    alias: String?,
    onClick: () -> Unit
) {
    val connected = status.connState == ConnState.CONNECTED
    val gradient = if (connected) {
        Brush.linearGradient(listOf(QringPalette.cardGradStart, QringPalette.cardGradMid, QringPalette.cardGradEnd))
    } else {
        Brush.linearGradient(listOf(QringPalette.cardGradOffStart, QringPalette.cardGradOffEnd))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .padding(16.dp)
        ) {
            // 状态信息
            Column {
                Text(
                    text = (alias ?: status.deviceName).ifEmpty { "未连接" },
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = when (status.connState) {
                        ConnState.CONNECTED -> {
                            if (alias != null && status.deviceName.isNotEmpty()) status.deviceName
                            else stringResource(R.string.status_connected)
                        }
                        ConnState.CONNECTING -> stringResource(R.string.status_connecting)
                        ConnState.DISCONNECTED -> stringResource(R.string.status_disconnected)
                    },
                    color = ON_CARD_SUBTITLE,
                    fontSize = 13.sp
                )
            }

            // 状态图标/指示灯
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (connected) ONLINE else ON_CARD_MUTED)
            )

            // 底部指标行
            Row(
                modifier = Modifier.align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 状态
                StatusTile(
                    icon = if (connected && status.hardwareState == com.qring.printer.model.HardwareState.NORMAL)
                        Icons.Default.CheckCircle else Icons.Default.Warning,
                    value = when {
                        status.hardwareState == com.qring.printer.model.HardwareState.COVER_OPEN -> "开盖"
                        status.hardwareState == com.qring.printer.model.HardwareState.OVERHEAT -> "过热"
                        status.paperState == com.qring.printer.model.PaperState.NO_PAPER -> "缺纸"
                        status.printing -> "打印中"
                        connected -> "正常"
                        else -> "--"
                    }
                )
                // 纸张
                StatusTile(
                    icon = Icons.Default.Description,
                    value = when (status.paperState) {
                        com.qring.printer.model.PaperState.OK -> "有纸"
                        com.qring.printer.model.PaperState.NO_PAPER -> "缺纸"
                        else -> "--"
                    }
                )
                // 电量
                StatusTile(
                    icon = Icons.Default.BatteryFull,
                    value = status.batteryPercent?.let { "$it%" } ?: "--"
                )
            }
        }
    }
}

@Composable
private fun StatusTile(icon: ImageVector, value: String) {
    Row(
        modifier = Modifier
            .background(ON_CARD_TILE, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp
        )
    }
}

// ── 快速打印宫格 ──────────────────────────────────────────

@Composable
fun QuickActionGrid(
    onTextClick: () -> Unit,
    onImageClick: () -> Unit,
    onCodeClick: () -> Unit,
    onCustomClick: () -> Unit
) {
    val actions = listOf(
        QuickAction("txt", R.string.action_text, R.string.action_text_desc,
            com.qring.printer.ui.theme.TILE_AMBER, Icons.Default.Description, onTextClick),
        QuickAction("image", R.string.action_image, R.string.action_image_desc,
            com.qring.printer.ui.theme.TILE_BLUE, Icons.Default.GridView, onImageClick),
        QuickAction("qrcode", R.string.action_code, R.string.action_code_desc,
            com.qring.printer.ui.theme.TILE_LILAC, Icons.Default.QrCode, onCodeClick),
        QuickAction("custom", R.string.action_custom, R.string.action_custom_desc,
            com.qring.printer.ui.theme.TILE_MINT, Icons.Default.GridView, onCustomClick),
    )

    // 2x2 grid
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.GRID_GAP.dp)) {
        for (row in actions.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.GRID_GAP.dp)) {
                for (action in row) {
                    QuickActionCard(
                        action = action,
                        modifier = Modifier.weight(1f)
                    )
                }
                // 补齐奇数行
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

data class QuickAction(
    val key: String,
    val titleRes: Int,
    val subtitleRes: Int,
    val color: Color,
    val icon: ImageVector,
    val onClick: () -> Unit
)

// ── 更多功能宫格 ────────────────────────────────────────

@Composable
fun MoreActionGrid(
onScheduleClick: () -> Unit,
onLabelClick: () -> Unit,
onCalendarClick: () -> Unit,
onTodoClick: () -> Unit,
onWordbookClick: () -> Unit = {},
onMathClick: () -> Unit = {}
) {
val actions = listOf(
QuickAction2("schedule", "课程表", "手动编辑课程表", com.qring.printer.ui.theme.TILE_MINT, Icons.Default.DateRange, onScheduleClick),
QuickAction2("label", "标签纸", "批量标签打印", com.qring.printer.ui.theme.TILE_BLUE, Icons.Default.Label, onLabelClick),
QuickAction2("calendar", "日程", "系统日程打印", com.qring.printer.ui.theme.TILE_LILAC, Icons.Default.GridView, onCalendarClick),
QuickAction2("todo", "Todo", "待办事项打印", com.qring.printer.ui.theme.TILE_AMBER, Icons.Default.Checklist, onTodoClick),
QuickAction2("wordbook", "单词本", "下载并打印单词", Color(0xFFB4A7D6), Icons.Default.MenuBook, onWordbookClick),
QuickAction2("math", "口算题", "自动生成口算", Color(0xFF6FCF97), Icons.Default.Calculate, onMathClick),
)

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.GRID_GAP.dp)) {
        for (row in actions.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.GRID_GAP.dp)) {
                for (action in row) {
                    QuickActionCard2(
                        action = action,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

data class QuickAction2(
    val key: String,
    val title: String,
    val subtitle: String,
    val color: Color,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
fun QuickActionCard2(action: QuickAction2, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .height(Metrics.ACTION_CARD_HEIGHT.dp)
            .clickable(onClick = action.onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(Metrics.TILE_SIZE.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(action.color),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    tint = com.qring.printer.ui.theme.TILE_ICON,
                    modifier = Modifier.size(Metrics.TILE_ICON.dp)
                )
            }

            Column {
                Text(
                    text = action.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = QringPalette.textPrimary
                )
                Text(
                    text = action.subtitle,
                    fontSize = 12.sp,
                    color = QringPalette.textSecondary
                )
            }
        }
    }
}

@Composable
fun QuickActionCard(action: QuickAction, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .height(Metrics.ACTION_CARD_HEIGHT.dp)
            .clickable(onClick = action.onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(Metrics.TILE_SIZE.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(action.color),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    tint = com.qring.printer.ui.theme.TILE_ICON,
                    modifier = Modifier.size(Metrics.TILE_ICON.dp)
                )
            }

            Column {
                Text(
                    text = stringResource(action.titleRes),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = QringPalette.textPrimary
                )
                Text(
                    text = stringResource(action.subtitleRes),
                    fontSize = 12.sp,
                    color = QringPalette.textSecondary
                )
            }
        }
    }
}

// ── 调试模式卡片 ──────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugCard(
    status: com.qring.printer.model.PrinterStatus,
    rssi: Int?,
    onRefreshInfo: () -> Unit,
    onFeedPaper: (dots: Int, callback: (Boolean) -> Unit) -> Unit
) {
    val connected = status.connState == ConnState.CONNECTED
    var feedDots by remember { mutableStateOf("100") }
    var feedResult by remember { mutableStateOf("") }
    var feeding by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = QringPalette.brand,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "调试模式",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = QringPalette.textPrimary
                )
                Spacer(modifier = Modifier.weight(1f))
                if (connected) {
                    androidx.compose.material3.TextButton(
                        onClick = onRefreshInfo,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = QringPalette.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "刷新",
                            fontSize = 12.sp,
                            color = QringPalette.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!connected) {
                Text(
                    text = "连接打印机后显示设备信息",
                    fontSize = 13.sp,
                    color = QringPalette.textSecondary
                )
            } else {
                // 设备信息网格
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DebugInfoTile("型号", status.model.ifEmpty { "--" })
                    DebugInfoTile("固件", status.firmware.ifEmpty { "--" })
                    DebugInfoTile("SN", status.sn.ifEmpty { "--" })
                    DebugInfoTile(
                        "电量",
                        status.batteryPercent?.let { "$it%" } ?: "--"
                    )
                    DebugInfoTile(
                        "信号",
                        rssi?.let { "$it dBm" } ?: "--"
                    )
                    DebugInfoTile(
                        "状态",
                        when {
                            status.hardwareState == com.qring.printer.model.HardwareState.COVER_OPEN -> "开盖"
                            status.hardwareState == com.qring.printer.model.HardwareState.OVERHEAT -> "过热"
                            status.paperState == com.qring.printer.model.PaperState.NO_PAPER -> "缺纸"
                            status.printing -> "打印中"
                            else -> "正常"
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 走纸测试
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = feedDots,
                        onValueChange = { feedDots = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("走纸点数", fontSize = 12.sp) },
                        modifier = Modifier.width(120.dp),
                        singleLine = true,
                        enabled = !feeding
                    )
                    androidx.compose.material3.Button(
                        onClick = {
                            val dots = feedDots.toIntOrNull() ?: 100
                            if (dots < 1 || dots > 2000) {
                                feedResult = "点数范围 1~2000"
                                return@Button
                            }
                            feeding = true
                            feedResult = ""
                            onFeedPaper(dots) { ok ->
                                feeding = false
                                feedResult = if (ok) "已走纸 $dots 点行" else "走纸失败"
                            }
                        },
                        enabled = !feeding,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = QringPalette.brand
                        )
                    ) {
                        if (feeding) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("走纸", fontSize = 13.sp, color = Color.White)
                        }
                    }
                }

                if (feedResult.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = feedResult,
                        fontSize = 12.sp,
                        color = if (feedResult.startsWith("已")) ONLINE else QringPalette.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugInfoTile(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(QringPalette.surfaceSunken)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = QringPalette.textSecondary
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = QringPalette.textPrimary
        )
    }
}
