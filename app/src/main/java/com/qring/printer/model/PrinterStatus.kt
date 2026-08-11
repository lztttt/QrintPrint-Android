package com.qring.printer.model

import com.qring.printer.protocol.applyQringStatus

/**
 * 打印机状态。
 *
 * 全局单例 —— 整个 App 只连一台打印机。
 * 用 StateFlow 暴露给 UI，替代 HarmonyOS 的 @ObservedV2 + @Trace。
 */
enum class ConnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

enum class PaperState {
    UNKNOWN,
    OK,
    NO_PAPER
}

enum class HardwareState {
    UNKNOWN,
    NORMAL,
    COVER_OPEN,
    OVERHEAT
}

object PrinterStatusRepository {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(PrinterStatus())
    val state: kotlinx.coroutines.flow.StateFlow<PrinterStatus> = _state

    fun update(transform: (PrinterStatus) -> PrinterStatus) {
        _state.value = transform(_value)
    }

    var _value: PrinterStatus
        get() = _state.value
        set(value) { _state.value = value }

    fun applyStatus(qringStatus: com.qring.printer.protocol.QringStatus) {
        _state.value = applyQringStatus(_state.value, qringStatus)
    }
}

data class PrinterStatus(
    val deviceId: String = "",
    val deviceName: String = "",
    val connState: ConnState = ConnState.DISCONNECTED,
    val paperState: PaperState = PaperState.UNKNOWN,
    val hardwareState: HardwareState = HardwareState.UNKNOWN,
    val batteryPercent: Int? = null,
    val printing: Boolean = false,
    val model: String = "",
    val firmware: String = "",
    val sn: String = "",
    val lastError: String = ""
)
