package com.qring.print.ui.home

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
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import com.qring.print.MainViewModel
import com.qring.print.R
import com.qring.print.model.ConnState
import com.qring.print.ui.navigation.Routes
import com.qring.print.ui.theme.BRAND
import com.qring.print.ui.theme.BRAND_PRESSED
import com.qring.print.ui.theme.CARD_GRAD_END
import com.qring.print.ui.theme.CARD_GRAD_MID
import com.qring.print.ui.theme.CARD_GRAD_OFF_END
import com.qring.print.ui.theme.CARD_GRAD_OFF_START
import com.qring.print.ui.theme.CARD_GRAD_START
import com.qring.print.ui.theme.Metrics
import com.qring.print.ui.theme.ONLINE
import com.qring.print.ui.theme.QringPalette
import com.qring.print.ui.theme.WARNING

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
                onClick = { showDevicePicker = true }
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
                onCustomClick = { /* TODO: Phase 4 */ }
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
                .background(BRAND),
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
    status: com.qring.print.model.PrinterStatus,
    onClick: () -> Unit
) {
    val connected = status.connState == ConnState.CONNECTED
    val gradient = if (connected) {
        Brush.linearGradient(listOf(CARD_GRAD_START, CARD_GRAD_MID, CARD_GRAD_END))
    } else {
        Brush.linearGradient(listOf(CARD_GRAD_OFF_START, CARD_GRAD_OFF_END))
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
                    text = status.deviceName.ifEmpty { "未连接" },
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = when (status.connState) {
                        ConnState.CONNECTED -> stringResource(R.string.status_connected)
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
                    icon = if (connected && status.hardwareState == com.qring.print.model.HardwareState.NORMAL)
                        Icons.Default.CheckCircle else Icons.Default.Warning,
                    value = when {
                        status.hardwareState == com.qring.print.model.HardwareState.COVER_OPEN -> "开盖"
                        status.hardwareState == com.qring.print.model.HardwareState.OVERHEAT -> "过热"
                        status.paperState == com.qring.print.model.PaperState.NO_PAPER -> "缺纸"
                        status.printing -> "打印中"
                        connected -> "正常"
                        else -> "--"
                    }
                )
                // 纸张
                StatusTile(
                    icon = Icons.Default.Description,
                    value = when (status.paperState) {
                        com.qring.print.model.PaperState.OK -> "有纸"
                        com.qring.print.model.PaperState.NO_PAPER -> "缺纸"
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
            com.qring.print.ui.theme.TILE_AMBER, Icons.Default.Description, onTextClick),
        QuickAction("image", R.string.action_image, R.string.action_image_desc,
            com.qring.print.ui.theme.TILE_BLUE, Icons.Default.GridView, onImageClick),
        QuickAction("qrcode", R.string.action_code, R.string.action_code_desc,
            com.qring.print.ui.theme.TILE_LILAC, Icons.Default.QrCode, onCodeClick),
        QuickAction("custom", R.string.action_custom, R.string.action_custom_desc,
            com.qring.print.ui.theme.TILE_MINT, Icons.Default.GridView, onCustomClick),
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
                    tint = com.qring.print.ui.theme.TILE_ICON,
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
