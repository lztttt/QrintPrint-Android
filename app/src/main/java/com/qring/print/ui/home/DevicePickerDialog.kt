package com.qring.print.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.print.MainViewModel
import com.qring.print.R
import com.qring.print.bt.BtDevice
import com.qring.print.model.ConnState
import com.qring.print.ui.theme.ONLINE
import com.qring.print.ui.theme.QringPalette
import com.qring.print.ui.theme.WARNING

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onRequestPermission: (() -> Unit)? = null,
    onEnableBluetooth: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val bluetoothEnabled by viewModel.bluetoothEnabled.collectAsState()
    val needsPermission by viewModel.needsPermission.collectAsState()
    val printerStatus by viewModel.printerStatus.collectAsState()

    val lastId = viewModel.lastDeviceId
    val currentConnectedId = if (printerStatus.connState == ConnState.CONNECTED) printerStatus.deviceId else null

    // 别名编辑弹窗状态
    var aliasEditAddress by remember { mutableStateOf<String?>(null) }
    var aliasInput by remember { mutableStateOf("") }
    val appContext = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.checkBluetoothState()
        viewModel.refreshPairedDevices()
    }

    LaunchedEffect(bluetoothEnabled) {
        // bluetoothEnabled 是异步更新的，第一次组合时还是 false，
        // 单独挂一个副作用等它变 true 再自动开始扫描
        if (bluetoothEnabled) {
            viewModel.startScan { }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.stopScan()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = QringPalette.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            // 标题
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.select_printer),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = QringPalette.textPrimary
                )
                IconButton(
                    onClick = {
                        viewModel.stopScan()
                        viewModel.startScan { }
                    }
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = QringPalette.brand,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = QringPalette.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 蓝牙未开启或权限不足时的提示
            if (needsPermission) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("需要蓝牙权限", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(
                            "请在系统设置中授予蓝牙权限",
                            fontSize = 12.sp,
                            color = QringPalette.textSecondary
                        )
                        if (onRequestPermission != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onRequestPermission,
                                colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("去授权")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else if (!bluetoothEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("蓝牙未开启", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(
                            "请在系统设置中打开蓝牙",
                            fontSize = 12.sp,
                            color = QringPalette.textSecondary
                        )
                        if (onEnableBluetooth != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onEnableBluetooth,
                                colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("打开蓝牙")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 上次连接的设备：一键重连
            if (lastId != null && bluetoothEnabled) {
                LastDeviceCard(
                    name = viewModel.deviceAlias(lastId) ?: viewModel.lastDeviceName ?: lastId,
                    address = lastId,
                    rssi = viewModel.deviceRssi(lastId),
                    connected = currentConnectedId == lastId,
                    onReconnect = {
                        viewModel.connectLastDevice()
                        viewModel.stopScan()
                        onDismiss()
                    },
                    onEditAlias = {
                        aliasEditAddress = lastId
                        aliasInput = viewModel.deviceAlias(lastId) ?: ""
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 设备列表
            if (scannedDevices.isEmpty() && !isScanning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_devices),
                        color = QringPalette.textSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (pairedDevices.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.paired_devices),
                                fontSize = 13.sp,
                                color = QringPalette.textSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        items(pairedDevices, key = { "paired_${it.address}" }) { device ->
                            DeviceItem(
                                device = device,
                                rssi = viewModel.deviceRssi(device.address),
                                showPaired = true,
                                isCurrent = currentConnectedId == device.address,
                                alias = viewModel.deviceAlias(device.address),
                                onEditAlias = {
                                    aliasEditAddress = device.address
                                    aliasInput = viewModel.deviceAlias(device.address) ?: ""
                                },
                                onClick = {
                                    viewModel.connectDevice(device.address)
                                    viewModel.stopScan()
                                    onDismiss()
                                }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                    }

                    // 未配对 = 扫描发现的设备里去掉已配对的（用 paired 标志 + 地址去重）
                    val pairedAddrs = pairedDevices.map { it.address }.toSet()
                    val newDevices = scannedDevices.filter { d -> !d.paired && d.address !in pairedAddrs }
                    if (newDevices.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.available_devices),
                                fontSize = 13.sp,
                                color = QringPalette.textSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        items(newDevices, key = { "scan_${it.address}" }) { device ->
                            DeviceItem(
                                device = device,
                                rssi = device.rssi ?: viewModel.deviceRssi(device.address),
                                showPaired = false,
                                isCurrent = currentConnectedId == device.address,
                                alias = viewModel.deviceAlias(device.address),
                                onEditAlias = {
                                    aliasEditAddress = device.address
                                    aliasInput = viewModel.deviceAlias(device.address) ?: ""
                                },
                                onClick = {
                                    viewModel.connectDevice(device.address)
                                    viewModel.stopScan()
                                    onDismiss()
                                }
                            )
                        }
                    } else {
                        // 未配对设备还没出现时，明确告诉用户当前状态
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isScanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = QringPalette.brand,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "正在搜索附近设备…",
                                        color = QringPalette.textSecondary,
                                        fontSize = 13.sp
                                    )
                                } else {
                                    Text(
                                        text = "未发现新设备，请确认打印机已开机并进入配对模式",
                                        color = QringPalette.textSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 别名编辑弹窗
    val editingAddress = aliasEditAddress
    if (editingAddress != null) {
        AliasDialog(
            initial = aliasInput,
            onConfirm = { alias ->
                if (alias.isNotEmpty()) {
                    viewModel.setDeviceAlias(editingAddress, alias)
                } else {
                    com.qring.print.data.DeviceAliasStore.remove(appContext, editingAddress)
                }
            },
            onDismiss = { aliasEditAddress = null }
        )
    }
}

@Composable
private fun DeviceItem(
    device: BtDevice,
    rssi: Int?,
    showPaired: Boolean,
    isCurrent: Boolean,
    alias: String?,
    onEditAlias: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent) ONLINE.copy(alpha = 0.15f) else QringPalette.brand.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = if (isCurrent) ONLINE else QringPalette.brand,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = alias ?: device.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = QringPalette.textPrimary
                    )
                    if (isCurrent) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "已连接",
                            fontSize = 11.sp,
                            color = ONLINE
                        )
                    }
                }
                Text(
                    text = if (alias != null) device.name else device.address,
                    fontSize = 11.sp,
                    color = QringPalette.textSecondary
                )
            }

            // 信号强度（数值 + 信号条）
            if (rssi != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "$rssi dBm",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (rssi >= -65) ONLINE else WARNING
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    SignalBars(rssi = rssi)
                }
            }

            // 别名编辑
            IconButton(onClick = onEditAlias, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "设置别名",
                    tint = QringPalette.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 信号强度：按 RSSI 画 1~4 格信号条 */
@Composable
private fun SignalBars(rssi: Int) {
    val bars = when {
        rssi >= -50 -> 4
        rssi >= -65 -> 3
        rssi >= -75 -> 2
        else -> 1
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 1..4) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((4 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= bars) QringPalette.brand else QringPalette.surfaceSunken)
            )
        }
    }
}

/** 上次连接的设备：一键重连 */
@Composable
private fun LastDeviceCard(
    name: String,
    address: String,
    rssi: Int?,
    connected: Boolean,
    onReconnect: () -> Unit,
    onEditAlias: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) ONLINE.copy(alpha = 0.12f) else QringPalette.brand.copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (connected) ONLINE else QringPalette.brand),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "上次连接",
                    fontSize = 11.sp,
                    color = QringPalette.textSecondary
                )
                Text(
                    text = name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = QringPalette.textPrimary,
                    maxLines = 1
                )
                Text(
                    text = address,
                    fontSize = 11.sp,
                    color = QringPalette.textSecondary
                )
            }

            if (rssi != null) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
                    Text(
                        text = "$rssi dBm",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (rssi >= -65) ONLINE else WARNING
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    SignalBars(rssi = rssi)
                }
            }

            IconButton(onClick = onEditAlias, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "设置别名",
                    tint = QringPalette.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }

            Button(
                onClick = onReconnect,
                enabled = !connected,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connected) ONLINE.copy(alpha = 0.2f) else QringPalette.brand,
                    contentColor = if (connected) ONLINE else Color.White,
                    disabledContainerColor = ONLINE.copy(alpha = 0.2f),
                    disabledContentColor = ONLINE
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (connected) "已连接" else "一键重连",
                    fontSize = 13.sp
                )
            }
        }
    }
}

/** 别名编辑弹窗 */
@Composable
private fun AliasDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备别名") },
        text = {
            Column {
                Text("给这台设备起个名字，方便区分多台打印机", fontSize = 12.sp, color = QringPalette.textSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(QringPalette.surfaceSunken)
                        .padding(12.dp),
                    textStyle = TextStyle(fontSize = 15.sp, color = QringPalette.textPrimary),
                    cursorBrush = SolidColor(QringPalette.brand),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(input.trim())
                    onDismiss()
                }
            ) { Text("保存", color = QringPalette.brand) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = QringPalette.textSecondary) }
        }
    )
}
