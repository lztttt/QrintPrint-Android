package com.qring.print

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.print.bt.BtDevice
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

    init {
        printerConnection.init(application)
        // 启动自动重连
        viewModelScope.launch {
            printerConnection.autoReconnect()
        }
    }

    fun refreshPairedDevices() {
        _pairedDevices.value = discovery.listPairedDevices()
    }

    fun startScan(onDevicesFound: (List<BtDevice>) -> Unit) {
        if (_isScanning.value) return
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

    override fun onCleared() {
        super.onCleared()
        discovery.stop()
    }
}
