package com.qring.printer.bt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

const val DOMAIN = 0x0001
const val TAG = "BtPermission"

/**
 * 设备名过滤前缀 —— 只展示自家 Qring 打印机。
 */
const val DEVICE_NAME_PREFIX: String = "Qring"

/**
 * 大小写不敏感匹配 —— 不同批次固件可能上报 Qring / QRing / QRING。
 */
fun matchesDeviceFilter(name: String?): Boolean {
    if (name.isNullOrEmpty()) return false
    return name.lowercase().startsWith(DEVICE_NAME_PREFIX.lowercase())
}

data class BtDevice(
    val address: String,
    val name: String,
    val paired: Boolean,
    /** 信号强度 dBm，发现时从广播里取，越接近 0 越强 */
    val rssi: Int? = null
)

/**
 * 蓝牙权限检查。
 */
object BtPermissionHelper {

    fun hasBluetoothPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter?.isEnabled == true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "BluetoothManager unavailable")
            false
        }
    }
}

/**
 * 蓝牙设备发现。
 */
class PrinterDiscovery(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothAdapter: BluetoothAdapter? = try {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    } catch (e: Exception) {
        null
    }

    private var receiver: BroadcastReceiver? = null
    private var onUpdate: ((List<BtDevice>) -> Unit)? = null
    private var running = false

    private val seenAddresses = mutableSetOf<String>()
    private val devices = mutableListOf<BtDevice>()

    /** 每台设备最近一次看到的信号强度（dBm） */
    private val lastRssi = mutableMapOf<String, Int>()

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        // 优先用广播里带的名字（BLE 设备常靠这个），否则读缓存名
                        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                            ?: (try { it.name } catch (e: SecurityException) { null })
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                            ?.toInt()?.takeIf { it != Short.MIN_VALUE.toInt() }
                        rssi?.let { r -> lastRssi[it.address] = r }
                        Timber.tag(TAG).d("ACTION_FOUND addr=${it.address} name=$name rssi=$rssi")
                        if (matchesDeviceFilter(name) && !seenAddresses.contains(it.address)) {
                            seenAddresses.add(it.address)
                            devices.add(BtDevice(it.address, name ?: it.address, false, rssi))
                            onUpdate?.invoke(devices.toList())
                        }
                    }
                }
                BluetoothDevice.ACTION_NAME_CHANGED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    device?.let { d ->
                        // 名字解析出来后更新显示名（比如 Qring_50C6 → Qring_50C6_BLE）
                        val idx = devices.indexOfFirst { it.address == d.address }
                        if (idx >= 0 && name != null && matchesDeviceFilter(name)) {
                            devices[idx] = devices[idx].copy(name = name)
                            onUpdate?.invoke(devices.toList())
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // 系统扫描约 12 秒自动结束；只要弹窗还开着就续扫。
                    // 立即重启会被蓝牙栈拒绝（还在清理上一轮），所以延迟并重试。
                    if (running) {
                        scope.launch {
                            // MIUI 在扫描结束后 ~10s 内拒绝再次 startDiscovery，延长重试窗口
                            var attempt = 1
                            while (attempt <= 8 && running) {
                                delay(2000L * attempt)
                                if (!running) return@launch
                                val ok = try {
                                    bluetoothAdapter?.startDiscovery() == true
                                } catch (e: Exception) {
                                    false
                                }
                                if (ok) return@launch
                                attempt++
                            }
                        }
                    }
                }
            }
        }
    }

    fun isRunning(): Boolean = running

    /**
     * 开始扫描。已配对设备预置进结果里。
     * 返回 false 表示环境不支持。
     */
    fun start(pairedDevices: List<BtDevice>, onUpdate: (List<BtDevice>) -> Unit): Boolean {
        if (running) return true
        if (bluetoothAdapter == null) return false

        this.onUpdate = onUpdate
        seenAddresses.clear()
        devices.clear()
        devices.addAll(pairedDevices)

        try {
            // 注册广播接收器
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 系统蓝牙的 ACTION_FOUND 广播，NOT_EXPORTED 收不到，必须 EXPORTED
                context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(discoveryReceiver, filter)
            }

            // 开始发现
            bluetoothAdapter.startDiscovery()
            running = true
            return true
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "startDiscovery permission denied")
            return false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "startDiscovery failed")
            return false
        }
    }

    /** 指定设备最近一次扫描到的信号强度（dBm），没有则 null */
    fun rssiFor(address: String): Int? = lastRssi[address]

    fun stop() {
        if (!running) return
        running = false
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "cancelDiscovery permission denied")
        }
        try {
            context.unregisterReceiver(discoveryReceiver)
        } catch (e: Exception) {
            // 未注册时忽略
        }
    }

    /**
     * 已配对且名字匹配过滤器的设备。
     */
    fun listPairedDevices(): List<BtDevice> {
        if (bluetoothAdapter == null) return emptyList()
        return try {
            bluetoothAdapter.bondedDevices
                ?.filter { matchesDeviceFilter(it.name) }
                ?.map { BtDevice(it.address, it.name ?: it.address, true) }
                ?: emptyList()
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "getBondedDevices permission denied")
            emptyList()
        }
    }

    /**
     * 取远端设备名。
     */
    fun remoteName(address: String): String {
        return try {
            val device = bluetoothAdapter?.getRemoteDevice(address)
            device?.name ?: ""
        } catch (e: SecurityException) {
            ""
        } catch (e: Exception) {
            ""
        }
    }
}
