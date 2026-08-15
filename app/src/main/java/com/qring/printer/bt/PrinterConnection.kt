package com.qring.printer.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.qring.printer.model.ConnState
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.ACK_PRINT_DONE
import com.qring.printer.protocol.CMD_BATTERY
import com.qring.printer.protocol.CMD_ENABLE
import com.qring.printer.protocol.CMD_ENABLE2
import com.qring.printer.protocol.CMD_FW_VERSION
import com.qring.printer.protocol.CMD_MODEL
import com.qring.printer.protocol.CMD_SN
import com.qring.printer.protocol.CMD_STATUS
import com.qring.printer.protocol.CMD_STOP
import com.qring.printer.protocol.CMD_WAKEUP
import com.qring.printer.protocol.CMD_ENABLE
import com.qring.printer.protocol.CMD_ENABLE2
import com.qring.printer.protocol.CHUNK_DELAY_MS
import com.qring.printer.protocol.CHUNK_SIZE
import com.qring.printer.protocol.FAULT_FRAME_HEAD
import com.qring.printer.protocol.QringStatus
import com.qring.printer.protocol.RasterData
import com.qring.printer.protocol.cmdFeed
import com.qring.printer.protocol.cmdRasterHeader
import com.qring.printer.protocol.cmdThickness
import com.qring.printer.protocol.faultLabel
import com.qring.printer.protocol.faultMessage
import com.qring.printer.protocol.parseStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Timer
import java.util.TimerTask
import java.util.UUID

const val DOMAIN_CONN = 0x0001
const val TAG_CONN = "PrinterConnection"

/** 串口服务标准 UUID，经典蓝牙 SPP 固定用这个 */
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

private const val PREF_NAME = "qringprint_printer"
private const val KEY_LAST_DEVICE = "last_device_id"

/** 状态轮询间隔 */
private const val POLL_INTERVAL_MS = 10000L

/** 查询响应等待上限 */
private const val QUERY_TIMEOUT_MS = 1500L

/** 发命令后等打印机准备响应的时间 */
private const val QUERY_SETTLE_MS = 150L

/** 等打印完成 ACK 的上限 — 基础值，实际按打印高度动态计算 */
private const val ACK_TIMEOUT_BASE_MS = 15000L

/** 每行光栅额外等待时间 */
private const val ACK_TIMEOUT_PER_ROW_MS = 10L

/** ACK 等待上限 — 与 v1.0 一致，保证大打印任务不会过早超时 */
private const val ACK_TIMEOUT_MAX_MS = 120000L

/** 打印前后走纸点行 */
private const val FEED_BEFORE = 10
private const val FEED_AFTER = 100

/**
 * Qring / BeePrt 打印机连接管理。全局单例 —— 同一时刻只连一台。
 *
 * 职责边界：
 *   本类           —— socket 生命周期、分包收发、查询/ACK 时序、轮询调度、持久化
 *   QringProtocol  —— 纯协议，拼字节 / 解析位
 *   RasterEncoder  —— 图像与文本 → 光栅字节
 *   UI             —— 只读 PrinterStatus，或调本类的高层打印方法
 */
class PrinterConnection private constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var context: Context? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var pollTimer: Timer? = null
    private var foreground = true
    private var busy = false

    /** 滚动接收缓冲 */
    private val rxBuffer = mutableListOf<Int>()

    companion object {
        @Volatile
        private var instance: PrinterConnection? = null

        fun getInstance(): PrinterConnection {
            return instance ?: synchronized(this) {
                instance ?: PrinterConnection().also { instance = it }
            }
        }
    }

    // ── 生命周期 ──────────────────────────────────────────────

    fun init(context: Context) {
        this.context = context.applicationContext
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = manager.adapter
        } catch (e: Exception) {
            Timber.tag(TAG_CONN).w(e, "BluetoothManager unavailable")
        }
    }

    fun onForeground() {
        foreground = true
        if (isAlive() && pollTimer == null) {
            startPolling()
        }
    }

    fun onBackground() {
        foreground = false
        stopPolling()
    }

    fun isAlive(): Boolean {
        val s = socket ?: return false
        return try {
            s.isConnected
        } catch (e: Exception) {
            false
        }
    }

    fun isBusy(): Boolean = busy

    // ── 连接 / 断开 ───────────────────────────────────────────

    suspend fun connect(address: String): Boolean = withContext(Dispatchers.IO) {
        doConnect(address)
    }

    private suspend fun doConnect(address: String): Boolean = mutex.withLock {
        disconnect()

        PrinterStatusRepository.update { it.copy(
            deviceId = address,
            deviceName = resolveName(address),
            connState = ConnState.CONNECTING,
            lastError = ""
        ) }

        val adapter = bluetoothAdapter ?: run {
            PrinterStatusRepository.update { it.copy(
                connState = ConnState.DISCONNECTED,
                lastError = "当前设备不支持蓝牙"
            ) }
            return false
        }

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            PrinterStatusRepository.update { it.copy(
                connState = ConnState.DISCONNECTED,
                lastError = "设备地址无效"
            ) }
            return false
        }

        return try {
            // 取消发现（会减慢连接速度）
            try { adapter.cancelDiscovery() } catch (e: Exception) { }

            val sppSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            sppSocket.connect()

            socket = sppSocket
            inputStream = sppSocket.inputStream
            outputStream = sppSocket.outputStream

            // 启动接收线程
            startReceiveThread()

            PrinterStatusRepository.update { it.copy(connState = ConnState.CONNECTED) }

            // 复位打印机残留状态：断连可能中断在打印半途（ENABLE 已发/光栅未完成），
            // 打印机内部还处于「使能/接收光栅」状态，直接把新光栅接上去会导致图像错位。
            // 先发 STOP + ENABLE 序列强制退出残留状态，再清空输入缓冲。
            try {
                sendAll(listOf(CMD_STOP, CMD_ENABLE, CMD_ENABLE2))
                delay(100)
            } catch (e: Exception) {
                Timber.tag(TAG_CONN).w(e, "reset sequence failed")
            }
            synchronized(rxBuffer) { rxBuffer.clear() }

            persistDeviceId(address)
            refreshAllLocked()
            queryDeviceInfoLocked()
            if (foreground) startPolling()

            true
        } catch (e: SecurityException) {
            Timber.tag(TAG_CONN).e(e, "connect permission denied")
            PrinterStatusRepository.update { it.copy(
                connState = ConnState.DISCONNECTED,
                lastError = "蓝牙权限不足"
            ) }
            false
        } catch (e: IOException) {
            Timber.tag(TAG_CONN).e(e, "connect io failed")
            PrinterStatusRepository.update { it.copy(
                connState = ConnState.DISCONNECTED,
                lastError = "连接失败：${e.message}"
            ) }
            false
        } catch (e: Exception) {
            Timber.tag(TAG_CONN).e(e, "connect threw")
            PrinterStatusRepository.update { it.copy(
                connState = ConnState.DISCONNECTED,
                lastError = "连接失败：${e.message}"
            ) }
            false
        }
    }

    fun disconnect() {
        stopPolling()
        busy = false

        val activeSocket = socket
        socket = null
        inputStream = null
        outputStream = null

        if (activeSocket != null) {
            try { activeSocket.close() } catch (e: Exception) {
                Timber.tag(TAG_CONN).w(e, "close socket failed")
            }
        }

        synchronized(rxBuffer) {
            rxBuffer.clear()
        }

        PrinterStatusRepository.update { it.copy(
            connState = ConnState.DISCONNECTED,
            paperState = com.qring.printer.model.PaperState.UNKNOWN,
            hardwareState = com.qring.printer.model.HardwareState.UNKNOWN,
            batteryPercent = null,
            printing = false
        ) }
    }

    suspend fun autoReconnect() = withContext(Dispatchers.IO) { doAutoReconnect() }

    private suspend fun doAutoReconnect() {
        val deviceId = loadDeviceId() ?: return
        if (deviceId.isEmpty()) return

        val adapter = bluetoothAdapter ?: return
        val paired = try {
            adapter.bondedDevices?.map { it.address } ?: return
        } catch (e: Exception) {
            return
        }
        if (paired.contains(deviceId)) {
            if (matchesDeviceFilter(resolveName(deviceId))) {
                connect(deviceId)
            }
        }
    }

    // ── 接收线程 ──────────────────────────────────────────────

    private var receiveJob: Job? = null

    private fun startReceiveThread() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            val ins = inputStream ?: return@launch
            val buf = ByteArray(1024)
            try {
                while (isActive) {
                    val n = ins.read(buf)
                    if (n < 0) {
                        // 断开
                        withContext(Dispatchers.Main) {
                            disconnect()
                        }
                        break
                    }
                    synchronized(rxBuffer) {
                        for (i in 0 until n) {
                            rxBuffer.add(buf[i].toInt() and 0xFF)
                        }
                        if (rxBuffer.size > 4096) {
                            rxBuffer.subList(0, rxBuffer.size - 4096).clear()
                        }
                    }
                }
            } catch (e: IOException) {
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        disconnect()
                    }
                }
            }
        }
    }

    // ── 底层收发 ──────────────────────────────────────────────

    private suspend fun send(data: ByteArray): Boolean {
        val os = outputStream ?: return false
        return try {
            val total = data.size
            var offset = 0
            while (offset < total) {
                val end = minOf(offset + CHUNK_SIZE, total)
                os.write(data, offset, end - offset)
                os.flush()
                offset = end
                // SPP 链路自带流控（写满阻塞背压），无需人工延时；
                // 保持 0 连续写，避免固件把停顿误判为数据结束
                if (CHUNK_DELAY_MS > 0) delay(CHUNK_DELAY_MS)
            }
            true
        } catch (e: IOException) {
            Timber.tag(TAG_CONN).e(e, "write failed")
            false
        }
    }

    private suspend fun sendAll(commands: List<ByteArray>): Boolean {
        for (cmd in commands) {
            if (!send(cmd)) return false
        }
        return true
    }

    private suspend fun waitBytes(n: Int, timeoutMs: Long): List<Int> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(rxBuffer) {
                if (rxBuffer.size >= n) {
                    // 先拷贝出结果再清空，避免返回的 subList 因底层被改而失效
                    val result = rxBuffer.take(n)
                    rxBuffer.subList(0, n).clear()
                    return result
                }
            }
            delay(20)
        }
        synchronized(rxBuffer) {
            return rxBuffer.toList().also { rxBuffer.clear() }
        }
    }

    /** 清空输入 → 发命令 → 稍等 → 读响应 */
    private suspend fun query(command: ByteArray, nbytes: Int): List<Int> {
        synchronized(rxBuffer) { rxBuffer.clear() }
        if (!send(command)) return emptyList()
        delay(QUERY_SETTLE_MS)
        return waitBytes(nbytes, QUERY_TIMEOUT_MS)
    }

    /** 等打印完成 ACK (0xAA)，同时盯着 FF xx 故障帧 */
    private suspend fun waitAck(timeoutMs: Long): PrintResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(rxBuffer) {
                // 检查 ACK
                if (rxBuffer.contains(ACK_PRINT_DONE)) {
                    rxBuffer.clear()
                    return PrintResult(true, "打印完成")
                }
                // 检查故障帧
                for (i in 0 until rxBuffer.size - 1) {
                    if (rxBuffer[i] == FAULT_FRAME_HEAD) {
                        val code = rxBuffer[i + 1]
                        if (code in 0x01..0x04) {
                            rxBuffer.clear()
                            return PrintResult(false, faultLabel(code))
                        }
                    }
                }
            }
            delay(100)
        }
        return PrintResult(false, "等待打印完成超时")
    }

    // ── 查询 ──────────────────────────────────────────────────

    suspend fun queryStatus(): QringStatus? {
        val response = query(CMD_STATUS, 1)
        if (response.isEmpty()) return null
        return parseStatus(response[0])
    }

    suspend fun queryBattery(): Int? {
        val response = query(CMD_BATTERY, 2)
        if (response.size < 2) return null
        return response[1]
    }

    private suspend fun queryString(command: ByteArray): String {
        val response = query(command, 64)
        val sb = StringBuilder()
        for (b in response) {
            if (b in 0x20 until 0x7F) {
                sb.append(b.toChar())
            }
        }
        return sb.toString().trim()
    }

    /** 查询设备信息（带锁，避免与打印并发写 socket） */
    suspend fun queryDeviceInfo() = mutex.withLock {
        queryDeviceInfoLocked()
    }

    private suspend fun queryDeviceInfoLocked() {
        if (busy) return
        val model = queryString(CMD_MODEL)
        val firmware = queryString(CMD_FW_VERSION)
        val sn = queryString(CMD_SN)
        PrinterStatusRepository.update { it.copy(model = model, firmware = firmware, sn = sn) }
    }

    /** 单独走纸（对应网页版的 feedPaper） */
    suspend fun feedPaper(dots: Int): Boolean = mutex.withLock {
        if (!isAlive()) return false
        if (busy) return false
        try {
            sendAll(listOf(CMD_ENABLE, CMD_ENABLE2))
            send(CMD_WAKEUP)
            sendAll(cmdFeed(dots))
            send(CMD_STOP)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 刷新状态（带锁）。
     * 与打印共用同一把 mutex：打印期间轮询会排队等待，避免查询命令
     * 插入光栅数据流导致固件误解析（图片断层/错位的根因之一）。
     */
    suspend fun refreshAll() = mutex.withLock {
        refreshAllLocked()
    }

    private suspend fun refreshAllLocked() {
        val status = queryStatus()
        if (status != null) {
            PrinterStatusRepository.applyStatus(status)
        }
        val battery = queryBattery()
        if (battery != null) {
            PrinterStatusRepository.update { it.copy(batteryPercent = battery) }
        }
    }

    /**
     * 打印前体检（带锁）。返回故障文案，null 表示可以打印。
     */
    suspend fun preflightCheck(): String? = mutex.withLock {
        if (!isAlive()) return@withLock "打印机未连接"
        val status = queryStatus() ?: return@withLock null
        PrinterStatusRepository.applyStatus(status)
        faultMessage(status)
    }

    // ── 打印 ──────────────────────────────────────────────────

    /**
     * 打印一张已经转好的光栅位图。
     * 时序：enable → thickness → wakeup → feed(前) → 光栅 → feed(后) → stop → 等 ACK
     */
    suspend fun printRaster(raster: RasterData, thickness: Int?): PrintResult = mutex.withLock {
        if (!isAlive()) return PrintResult(false, "打印机未连接")
        if (busy) return PrintResult(false, "上一个打印任务还没结束")

        busy = true
        stopPolling()
        synchronized(rxBuffer) { rxBuffer.clear() }

        try {
            if (!sendAll(listOf(CMD_ENABLE, CMD_ENABLE2))) {
                return PrintResult(false, "发送失败，连接可能已断开")
            }
            if (thickness != null) {
                send(cmdThickness(thickness))
            }
            send(CMD_WAKEUP)
            sendAll(cmdFeed(FEED_BEFORE))

            send(cmdRasterHeader(raster.widthBytes, raster.height, 0))
            if (!send(raster.data)) {
                return PrintResult(false, "位图发送中断")
            }

            sendAll(cmdFeed(FEED_AFTER))
            send(CMD_STOP)

            PrinterStatusRepository.update { it.copy(printing = true) }
            // 根据打印高度动态计算 ACK 超时：基础 8s + 每行 5ms，上限 30s
            val ackTimeout = minOf(
                ACK_TIMEOUT_MAX_MS,
                ACK_TIMEOUT_BASE_MS + raster.height * ACK_TIMEOUT_PER_ROW_MS
            )
            Timber.tag(TAG_CONN).d("printRaster: height=${raster.height}, ackTimeout=${ackTimeout}ms")
            val result = waitAck(ackTimeout)
            PrinterStatusRepository.update { it.copy(printing = false) }
            if (!result.ok) {
                PrinterStatusRepository.update { it.copy(lastError = result.message) }
                // 超时/失败后尝试停止打印机：打印机可能仍在处理残留数据，
                // 发 STOP 强制退出，避免残留状态影响下一次打印
                try {
                    sendAll(listOf(CMD_STOP))
                } catch (e: Exception) {
                    Timber.tag(TAG_CONN).w(e, "stop after ack failure failed")
                }
            }
            result
        } finally {
            busy = false
            withContext(NonCancellable) {
                // 已在 mutex 内，用无锁内部版避免死锁
                refreshAllLocked()
            }
            if (foreground && isAlive()) startPolling()
        }
    }

    // ── 状态轮询 ──────────────────────────────────────────────

    /**
     * 连续打印多块光栅（块间不走纸，拼接成完整内容）。
     * 用于横排长内容：旋转后宽度超过 384 点时，按 384 宽分块，
     * 一次性发送所有块（GS v 0 连续命令即上下拼接），最后统一走纸/等 ACK。
     */
    suspend fun printRasterChunks(chunks: List<RasterData>, thickness: Int?): PrintResult = mutex.withLock {
        if (!isAlive()) return PrintResult(false, "打印机未连接")
        if (busy) return PrintResult(false, "上一个打印任务还没结束")
        if (chunks.isEmpty()) return PrintResult(false, "没有可打印的数据")

        busy = true
        stopPolling()
        synchronized(rxBuffer) { rxBuffer.clear() }

        try {
            if (!sendAll(listOf(CMD_ENABLE, CMD_ENABLE2))) {
                return PrintResult(false, "发送失败，连接可能已断开")
            }
            if (thickness != null) {
                send(cmdThickness(thickness))
            }
            send(CMD_WAKEUP)
            sendAll(cmdFeed(FEED_BEFORE))

            for (chunk in chunks) {
                send(cmdRasterHeader(chunk.widthBytes, chunk.height, 0))
                if (!send(chunk.data)) {
                    return PrintResult(false, "位图发送中断")
                }
            }

            sendAll(cmdFeed(FEED_AFTER))
            send(CMD_STOP)

            PrinterStatusRepository.update { it.copy(printing = true) }
            val totalHeight = chunks.sumOf { it.height }
            val ackTimeout = minOf(
                ACK_TIMEOUT_MAX_MS,
                ACK_TIMEOUT_BASE_MS + totalHeight * ACK_TIMEOUT_PER_ROW_MS
            )
            Timber.tag(TAG_CONN).d("printRasterChunks: chunks=${chunks.size}, totalHeight=$totalHeight, ackTimeout=${ackTimeout}ms")
            val result = waitAck(ackTimeout)
            PrinterStatusRepository.update { it.copy(printing = false) }
            if (!result.ok) {
                PrinterStatusRepository.update { it.copy(lastError = result.message) }
                try {
                    sendAll(listOf(CMD_STOP))
                } catch (e: Exception) {
                    Timber.tag(TAG_CONN).w(e, "stop after ack failure failed")
                }
            }
            result
        } finally {
            busy = false
            withContext(NonCancellable) {
                refreshAllLocked()
            }
            if (foreground && isAlive()) startPolling()
        }
    }

    private fun startPolling() {
        stopPolling()
        pollTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    scope.launch { pollOnce() }
                }
            }, POLL_INTERVAL_MS, POLL_INTERVAL_MS)
        }
        // 立即执行一次
        scope.launch { pollOnce() }
    }

    private fun stopPolling() {
        pollTimer?.cancel()
        pollTimer = null
    }

    /**
     * 轮询：只查状态字节（1字节，快速可靠），不查电量。
     * 电量查询容易和打印机内部处理冲突，只在连接和打印后查一次。
     * 状态字节里的 ST_LOW_BATTERY 位已经能反映低电量状态。
     */
    private suspend fun pollOnce() {
        if (socket == null || busy) return
        val status = queryStatus()
        if (status != null) {
            PrinterStatusRepository.applyStatus(status)
            // 状态字节带低电量位时，如果当前没有电量值或电量值不为0，标记为低电量
            if (status.lowBattery) {
                PrinterStatusRepository.update { it.copy(batteryPercent = it.batteryPercent ?: 5) }
            }
        }
    }

    // ── 工具 ──────────────────────────────────────────────────

    private fun resolveName(address: String): String {
        return try {
            val device = bluetoothAdapter?.getRemoteDevice(address)
            device?.name ?: address
        } catch (e: Exception) {
            address
        }
    }

    private suspend fun persistDeviceId(deviceId: String) {
        val ctx = context ?: return
        try {
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_DEVICE, deviceId)
                .apply()
        } catch (e: Exception) {
            Timber.tag(TAG_CONN).w(e, "persist deviceId failed")
        }
    }

    private fun loadDeviceId(): String? {
        val ctx = context ?: return ""
        return try {
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_DEVICE, "")
        } catch (e: Exception) {
            ""
        }
    }

    /** 上次连接过的打印机地址，没有则返回 null */
    fun lastDeviceId(): String? = loadDeviceId()?.takeIf { it.isNotEmpty() }

    /** 上次连接过的打印机名称 */
    fun lastDeviceName(): String? {
        val id = lastDeviceId() ?: return null
        return resolveName(id).ifEmpty { null }
    }

}
