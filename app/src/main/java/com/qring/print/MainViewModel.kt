package com.qring.print

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.print.bt.BtDevice
import com.qring.print.bt.BtPermissionHelper
import com.qring.print.bt.PrinterConnection
import com.qring.print.bt.PrinterDiscovery
import com.qring.print.model.PrinterStatus
import com.qring.print.model.PrinterStatusRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val discovery = PrinterDiscovery(application)

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    private val _pairedDevices = MutableStateFlow<List<BtDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BtDevice>> = _pairedDevices.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BtDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BtDevice>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _bluetoothEnabled = MutableStateFlow(false)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val _needsPermission = MutableStateFlow(false)
    val needsPermission: StateFlow<Boolean> = _needsPermission.asStateFlow()

    init {
        // 自动重连已在 MainActivity 里统一处理，这里不再重复
    }

    fun refreshPairedDevices() {
        _pairedDevices.value = discovery.listPairedDevices()
    }

    fun checkBluetoothState() {
        val context = getApplication<Application>()
        _bluetoothEnabled.value = BtPermissionHelper.isBluetoothEnabled(context)
        _needsPermission.value = !BtPermissionHelper.hasBluetoothPermissions(context)
    }

    fun onPermissionGranted() {
        _needsPermission.value = false
        checkBluetoothState()
    }

    fun startScan(onDevicesFound: (List<BtDevice>) -> Unit) {
        if (_isScanning.value) return
        _bluetoothEnabled.value = BtPermissionHelper.isBluetoothEnabled(getApplication())
        if (!_bluetoothEnabled.value) return
        val paired = discovery.listPairedDevices()
        _scannedDevices.value = paired
    _isScanning.value = discovery.start(paired) { devices ->
        _scannedDevices.value = devices
        onDevicesFound(devices)
    }
    }

    fun stopScan() {
        discovery.stop()
        _isScanning.value = false
    }

    fun connectDevice(address: String) {
        viewModelScope.launch {
            printerConnection.connect(address)
        }
    }

    /** 上次连接过的打印机地址，用于一键重连 */
    val lastDeviceId: String?
        get() = printerConnection.lastDeviceId()

    /** 上次连接过的打印机名称 */
    val lastDeviceName: String?
        get() = printerConnection.lastDeviceName()

    /** 一键重连上次的设备 */
    fun connectLastDevice() {
        val id = printerConnection.lastDeviceId() ?: return
        connectDevice(id)
    }

    /** 指定设备最近一次扫描到的信号强度（dBm） */
    fun deviceRssi(address: String): Int? = discovery.rssiFor(address)

    /** 设备别名 */
    fun deviceAlias(address: String): String? =
        com.qring.print.data.DeviceAliasStore.get(getApplication(), address)

    fun setDeviceAlias(address: String, alias: String) {
        com.qring.print.data.DeviceAliasStore.set(getApplication(), address, alias)
    }

    override fun onCleared() {
        super.onCleared()
        discovery.stop()
    }
}
