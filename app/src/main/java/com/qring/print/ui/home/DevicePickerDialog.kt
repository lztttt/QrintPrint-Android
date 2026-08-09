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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qring.print.MainViewModel
import com.qring.print.R
import com.qring.print.bt.BtDevice
import com.qring.print.ui.theme.BRAND
import com.qring.print.ui.theme.ONLINE
import com.qring.print.ui.theme.QringPalette

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

    LaunchedEffect(Unit) {
        viewModel.checkBluetoothState()
        viewModel.refreshPairedDevices()
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
                            color = BRAND,
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
                                colors = ButtonDefaults.buttonColors(containerColor = BRAND),
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
                                colors = ButtonDefaults.buttonColors(containerColor = BRAND),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("打开蓝牙")
                            }
                        }
                    }
                }
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
                                showPaired = true,
                                onClick = {
                                    viewModel.connectDevice(device.address)
                                    viewModel.stopScan()
                                    onDismiss()
                                }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                    }

                    val newDevices = scannedDevices.filter { d -> !pairedDevices.any { it.address == d.address } }
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
                                showPaired = false,
                                onClick = {
                                    viewModel.connectDevice(device.address)
                                    viewModel.stopScan()
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device: BtDevice,
    showPaired: Boolean,
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
                    .background(BRAND.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = BRAND,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = QringPalette.textPrimary
                )
                Text(
                    text = device.address,
                    fontSize = 12.sp,
                    color = QringPalette.textSecondary
                )
            }

            if (showPaired) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(ONLINE)
                )
            }
        }
    }
}
