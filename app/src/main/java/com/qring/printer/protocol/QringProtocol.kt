package com.qring.printer.protocol

/**
 * 小印 (Qring / BeePrt BY) 热敏打印机私有协议。
 *
 * 协议来自对 com.zxxk.xiaoyin.App 的逆向整理，仅供互操作参考。
 *
 * 注意：这**不是**标准 ESC/POS 状态协议。
 * 标准 ESC/POS 用 DLE EOT (10 04 n) 查状态、且没有电量指令；
 * Qring 用自己的 10 FF 系列命令，一个状态字节里同时带
 * 打印中/开盖/缺纸/低电压/过热五个位，并且有独立的电量查询。
 * 只有走纸 (ESC J) 和光栅位图 (GS v 0) 两条沿用了 ESC/POS。
 *
 * 本文件是纯协议层：只拼字节、解析字节，不碰 socket。
 */

// ── 物理常量 ──────────────────────────────────────────────

/** 58mm 热敏头点数 */
const val WIDTH_DOTS: Int = 384

/** 每行字节数 384/8 = 48，无补位 */
const val WIDTH_BYTES: Int = 48

/** SDK 单次 write 上限，超过要分包 */
const val CHUNK_SIZE: Int = 1024

/** 分包之间的间隔，照搬 SDK 行为 */
const val CHUNK_DELAY_MS: Long = 1L

// ── 字节构造 ──────────────────────────────────────────────

fun bytesOf(values: IntArray): ByteArray = ByteArray(values.size) { (values[it].toByte()) }

// ── 打印控制 ──────────────────────────────────────────────
val CMD_ENABLE: ByteArray = bytesOf(intArrayOf(0x10, 0xFF, 0xF1, 0x02))
val CMD_ENABLE2: ByteArray = bytesOf(intArrayOf(0x1F, 0xB2, 0x10))
val CMD_STOP: ByteArray = bytesOf(intArrayOf(0x10, 0xFF, 0xF1, 0x45))

/** 唤醒：12 个 0x00 */
val CMD_WAKEUP: ByteArray = ByteArray(12)

// ── 查询 ──────────────────────────────────────────────────
val CMD_STATUS: ByteArray = bytesOf(intArrayOf(0x10, 0xFF, 0x40))
val CMD_BATTERY: ByteArray = bytesOf(intArrayOf(0x10, 0xFF, 0x50, 0xF1))
val CMD_MODEL: ByteArray = bytesOf(intArrayOf(0x10, 0xFF, 0x20, 0xF0))
val CMD_FW_VERSION: ByteArray = bytesOf(intArrayOf(0x10, 0xFF, 0x20, 0xF1))
val CMD_SN: ByteArray = bytesOf(intArrayOf(0x10, 0xFF, 0x20, 0xF2))
val CMD_BT_NAME: ByteArray = bytesOf(intArrayOf(0x10, 0xFF, 0x30, 0x11))

/** 打印完成 ACK */
const val ACK_PRINT_DONE: Int = 0xAA

/** 主动上报帧头 */
const val FAULT_FRAME_HEAD: Int = 0xFF

// ── 状态字节位 ──────────────────────────────────────────────
const val ST_PRINTING: Int = 0x01
const val ST_COVER_OPEN: Int = 0x02
const val ST_NO_PAPER: Int = 0x04
const val ST_LOW_BATTERY: Int = 0x08
const val ST_OVERHEAT: Int = 0x10

/** FF xx 主动上报的故障码 */
enum class FaultCode(val code: Int) {
    NO_PAPER(0x01),
    COVER_OPEN(0x02),
    OVERHEAT(0x03),
    LOW_BATTERY(0x04);

    companion object {
        fun fromCode(code: Int): FaultCode? = entries.find { it.code == code }
    }
}

fun faultLabel(code: Int): String = when (code) {
    FaultCode.NO_PAPER.code -> "缺纸"
    FaultCode.COVER_OPEN.code -> "开盖"
    FaultCode.OVERHEAT.code -> "过热"
    FaultCode.LOW_BATTERY.code -> "低电量"
    else -> "未知故障 (0x${code.toString(16)})"
}

// ── 状态字节解析结果 ──────────────────────────────────────

data class QringStatus(
    val raw: Int,
    val printing: Boolean,
    val coverOpen: Boolean,
    val noPaper: Boolean,
    val lowBattery: Boolean,
    val overheat: Boolean
)

fun parseStatus(raw: Int): QringStatus = QringStatus(
    raw = raw,
    printing = (raw and ST_PRINTING) != 0,
    coverOpen = (raw and ST_COVER_OPEN) != 0,
    noPaper = (raw and ST_NO_PAPER) != 0,
    lowBattery = (raw and ST_LOW_BATTERY) != 0,
    overheat = (raw and ST_OVERHEAT) != 0
)

/** 状态字节为 0 表示一切正常 */
fun isStatusHealthy(status: QringStatus): Boolean = status.raw == 0

/**
 * 打印前体检文案。返回 null 表示可以打印。
 *
 * 判断顺序是有讲究的：**开盖必须排在缺纸前面**。
 * 上盖打开时纸传感器看不到纸，会同时把缺纸位也置起来 ——
 * 这时候提示「缺纸」是误导，真正要用户做的动作是合上盖子。
 */
/** 把 QringStatus 应用到 PrinterStatus 上 */
fun applyQringStatus(ps: com.qring.printer.model.PrinterStatus, qs: QringStatus): com.qring.printer.model.PrinterStatus {
    val paperState = when {
        qs.noPaper -> com.qring.printer.model.PaperState.NO_PAPER
        else -> com.qring.printer.model.PaperState.OK
    }
    val hardwareState = when {
        qs.coverOpen -> com.qring.printer.model.HardwareState.COVER_OPEN
        qs.overheat -> com.qring.printer.model.HardwareState.OVERHEAT
        qs.lowBattery -> com.qring.printer.model.HardwareState.NORMAL  // 低电量不影响硬件状态
        else -> com.qring.printer.model.HardwareState.NORMAL
    }
    return ps.copy(
        paperState = paperState,
        hardwareState = hardwareState,
        printing = qs.printing
    )
}

fun faultMessage(status: QringStatus): String? = when {
    status.coverOpen -> "机器未合盖，请检查机器"
    status.noPaper -> "机器缺纸，请检查纸张装配"
    status.overheat -> "机器过热，请稍候再尝试打印"
    else -> null
}

// ── 指令构造 ──────────────────────────────────────────────

/** 打印浓度 / 加热强度。APP 打文字用 1 */
fun cmdThickness(level: Int): ByteArray = bytesOf(intArrayOf(0x10, 0xFF, 0x10, 0x00, level))

/** 自动关机时间，大端 16 位，单位秒 */
fun cmdShutdownTime(seconds: Int): ByteArray = bytesOf(
    intArrayOf(0x10, 0xFF, 0x12, (seconds / 256) and 0xFF, seconds % 256)
)

/**
 * ESC J n —— 走纸 n 点行。
 * n 是单字节，超过 255 要拆成多条，所以返回列表。
 */
fun cmdFeed(dots: Int): List<ByteArray> {
    val commands = mutableListOf<ByteArray>()
    var remaining = dots
    while (remaining > 0) {
        val n = minOf(remaining, 255)
        commands.add(bytesOf(intArrayOf(0x1B, 0x4A, n)))
        remaining -= n
    }
    return commands
}

/** GS v 0 —— 光栅位图头。data 紧跟其后单独发送 */
fun cmdRasterHeader(widthBytes: Int, height: Int, mode: Int): ByteArray = bytesOf(
    intArrayOf(
        0x1D, 0x76, 0x30, mode and 0x03,
        widthBytes % 256, (widthBytes / 256) and 0xFF,
        height % 256, (height / 256) and 0xFF
    )
)
