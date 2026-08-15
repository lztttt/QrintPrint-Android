package com.qring.printer.ui.codeprint

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.pdf417.PDF417Writer
import com.google.zxing.datamatrix.DataMatrixWriter
import com.google.zxing.aztec.AztecWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.data.HistoryPayloadHolder
import com.qring.printer.data.HistoryRepository
import com.qring.printer.model.ConnState
import com.qring.printer.model.HIST_TYPE_CODE
import com.qring.printer.model.PrinterStatus
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.protocol.RasterData
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.protocol.binaryToPreviewBitmap
import com.qring.printer.protocol.bitmapToRaster
import com.qring.printer.protocol.ditherToBinary
import com.qring.printer.protocol.GrayImage
import com.qring.printer.protocol.DitherMode
import com.qring.printer.protocol.packBinaryToRaster
import com.qring.printer.protocol.scaleGrayNearest
import com.qring.printer.protocol.squeezeRows
import com.qring.printer.protocol.createBinaryCanvas
import com.qring.printer.protocol.blitBinary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CodeAlignment(val label: String) {
    LEFT("左对齐"),
    CENTER("居中"),
    RIGHT("右对齐")
}

/** 二维码纠错等级（仅 QR Code 生效） */
enum class QrEcc(val label: String, val level: ErrorCorrectionLevel) {
    L("L 约7%", ErrorCorrectionLevel.L),
    M("M 约15%", ErrorCorrectionLevel.M),
    Q("Q 约25%", ErrorCorrectionLevel.Q),
    H("H 约30%", ErrorCorrectionLevel.H)
}

/** 占位符：{content} 条码内容；{time_now} 当前时间；{(1:100)} 批量序号 */
private val SEQ_REGEX = Regex("\\{\\((\\d+):(\\d+)\\)\\}")

data class CodePrintUiState(
    val content: String = "",
    val codeTypeIndex: Int = 0, // 默认 QR Code
    val previewBitmap: Bitmap? = null,
    val showPreview: Boolean = false,
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false,
    val busy: Boolean = false,
    // 打印尺寸比例 0~100，对应 38~384 点
    val scalePercent: Int = 50,
    // 对齐方式
    val alignment: CodeAlignment = CodeAlignment.CENTER,
    // 二维码纠错等级
    val ecc: QrEcc = QrEcc.M,
    // 上方标注
    val showTopText: Boolean = false,
    val topText: String = "",
    // 下方标注
    val showBottomText: Boolean = false,
    val bottomText: String = "",
    // 标注字号
    val captionFontSize: Float = 14f,
    // 批量打印数量（默认 1；含 {(start:end)} 占位符时自动取区间长度）
    val batchCount: Int = 1,
    val progressText: String = "",
)

class CodePrintViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val historyRepo = HistoryRepository(application)
    private val app = application

    private val _uiState = MutableStateFlow(CodePrintUiState())
    val uiState: StateFlow<CodePrintUiState> = _uiState.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    init {
        restoreFromHistoryPayload()
    }

    private fun restoreFromHistoryPayload() {
        val (type, payload) = HistoryPayloadHolder.consumePayload() ?: return
        if (type != HIST_TYPE_CODE) {
            HistoryPayloadHolder.setPayload(type, payload)
            return
        }
        try {
            val obj = JSONObject(payload)
            val content = obj.optString("content", "")
            val codeTypeIndex = obj.optInt("codeTypeIndex", 0)
            _uiState.value = _uiState.value.copy(
                content = content,
                codeTypeIndex = codeTypeIndex,
                ecc = QrEcc.entries.getOrElse(obj.optInt("ecc", 1)) { QrEcc.M },
                scalePercent = obj.optInt("scalePercent", 50).coerceIn(10, 100),
                alignment = CodeAlignment.entries.getOrElse(obj.optInt("alignment", 1)) { CodeAlignment.CENTER },
                showTopText = obj.optBoolean("showTopText", false),
                topText = obj.optString("topText", ""),
                showBottomText = obj.optBoolean("showBottomText", false),
                bottomText = obj.optString("bottomText", ""),
                captionFontSize = obj.optDouble("captionFontSize", 14.0).toFloat().coerceIn(10f, 24f),
                batchCount = obj.optInt("batchCount", 1).coerceIn(1, 200)
            )
            if (content.isNotEmpty()) {
                updatePreview()
            }
        } catch (e: Exception) { }
    }

    fun updateContent(content: String) {
        _uiState.value = _uiState.value.copy(content = content)
    }

    fun setCodeTypeIndex(index: Int) {
        _uiState.value = _uiState.value.copy(codeTypeIndex = index)
    }

    fun setScalePercent(percent: Int) {
        _uiState.value = _uiState.value.copy(scalePercent = percent.coerceIn(10, 100))
    }

    fun setAlignment(alignment: CodeAlignment) {
        _uiState.value = _uiState.value.copy(alignment = alignment)
    }

    fun setEcc(ecc: QrEcc) {
        _uiState.value = _uiState.value.copy(ecc = ecc)
    }

    fun setShowTopText(show: Boolean) { _uiState.value = _uiState.value.copy(showTopText = show) }
    fun setTopText(text: String) { _uiState.value = _uiState.value.copy(topText = text) }
    fun setShowBottomText(show: Boolean) { _uiState.value = _uiState.value.copy(showBottomText = show) }
    fun setBottomText(text: String) { _uiState.value = _uiState.value.copy(bottomText = text) }
    fun setCaptionFontSize(size: Float) { _uiState.value = _uiState.value.copy(captionFontSize = size.coerceIn(10f, 24f)) }
    fun setBatchCount(count: Int) { _uiState.value = _uiState.value.copy(batchCount = count.coerceIn(1, 200)) }

    /** 在输入框中插入变量占位符（图形化编辑入口） */
    fun insertPlaceholder(placeholder: String) {
        _uiState.value = _uiState.value.copy(content = _uiState.value.content + placeholder)
    }

    // ── 占位符解析 ──────────────────────────────────────────

    /** 当前时间文本 */
    private fun timeNow(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
    }

    /**
     * 解析模板占位符：
     * - {content}   → 条码内容（已含序号替换的结果）
     * - {time_now}  → 当前时间
     * - {(start:end)} → 批量序号（第 seq 张，共 total 张，线性映射到 start..end）
     */
    private fun resolvePlaceholders(template: String, resolvedContent: String, seq: Int, total: Int): String {
        var s = template
        s = s.replace("{content}", resolvedContent)
        s = s.replace("{time_now}", timeNow())
        s = SEQ_REGEX.replace(s) { m ->
            val start = m.groupValues[1].toIntOrNull() ?: 1
            val end = m.groupValues[2].toIntOrNull() ?: start
            if (total <= 1) {
                start.toString()
            } else {
                val v = start + Math.round((seq - 1) * (end - start).toFloat() / (total - 1))
                v.coerceIn(minOf(start, end), maxOf(start, end)).toString()
            }
        }
        return s
    }

    /** 条码实际内容（解析占位符后的第 seq 张） */
    fun resolveContentFor(seq: Int, total: Int): String {
        return resolvePlaceholders(_uiState.value.content, _uiState.value.content, seq, total)
    }

    /** 检测内容中的批量区间占位符 {(start:end)}，返回区间长度；无则 null */
    fun seqRangeInContent(): Pair<Int, Int>? {
        val m = SEQ_REGEX.find(_uiState.value.content) ?: return null
        val start = m.groupValues[1].toIntOrNull() ?: return null
        val end = m.groupValues[2].toIntOrNull() ?: return null
        return start to end
    }

    /** 有效批量数量：内容含 {(start:end)} 时取区间长度（用户手动设置优先） */
    fun effectiveBatchCount(): Int {
        val range = seqRangeInContent() ?: return _uiState.value.batchCount
        val rangeLen = kotlin.math.abs(range.second - range.first) + 1
        // 若用户未手动改过（默认 1），自动用区间长度
        return if (_uiState.value.batchCount <= 1) rangeLen else _uiState.value.batchCount
    }

    // ── 快速模板 ────────────────────────────────────────────

    /** URL 模板 */
    fun applyUrlTemplate(url: String) {
        val finalUrl = url.trim().let { if (it.isEmpty()) "" else it }
        _uiState.value = _uiState.value.copy(
            content = finalUrl,
            codeTypeIndex = 0
        )
        updatePreview()
    }

    /** 电话模板 */
    fun applyPhoneTemplate(phone: String) {
        val p = phone.trim()
        _uiState.value = _uiState.value.copy(
            content = if (p.isEmpty()) "" else "tel:$p",
            codeTypeIndex = 4
        )
        updatePreview()
    }

    /** WiFi 模板：生成 WIFI:T:WPA;S:ssid;P:password;; 格式 */
    fun applyWifiTemplate(ssid: String, password: String, encryption: String) {
        val s = ssid.trim()
        val p = password.trim()
        val enc = when (encryption.uppercase()) {
            "WPA", "WPA2", "WPA/WPA2" -> "WPA"
            "WEP" -> "WEP"
            else -> "nopass"
        }
        val content = if (s.isEmpty()) "" else "WIFI:T:$enc;S:$s;P:$p;;"
        _uiState.value = _uiState.value.copy(content = content, codeTypeIndex = 0)
        updatePreview()
    }

    /** 邮箱模板 */
    fun applyEmailTemplate(email: String) {
        val e = email.trim()
        _uiState.value = _uiState.value.copy(
            content = if (e.isEmpty()) "" else "mailto:$e",
            codeTypeIndex = 0
        )
        updatePreview()
    }

    /** 短信模板 */
    fun applySmsTemplate(phone: String) {
        val p = phone.trim()
        _uiState.value = _uiState.value.copy(
            content = if (p.isEmpty()) "" else "sms:$p",
            codeTypeIndex = 0
        )
        updatePreview()
    }

    /** 名片模板：生成 vCard 3.0 格式 */
    fun applyCardTemplate(name: String, phone: String, email: String, org: String, title: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val sb = StringBuilder()
        sb.append("BEGIN:VCARD\n")
        sb.append("VERSION:3.0\n")
        sb.append("FN:$n\n")
        sb.append("N:;$n;;;\n")
        if (phone.isNotBlank()) sb.append("TEL;TYPE=CELL:${phone.trim()}\n")
        if (email.isNotBlank()) sb.append("EMAIL:${email.trim()}\n")
        if (org.isNotBlank()) sb.append("ORG:${org.trim()}\n")
        if (title.isNotBlank()) sb.append("TITLE:${title.trim()}\n")
        sb.append("END:VCARD")
        _uiState.value = _uiState.value.copy(content = sb.toString(), codeTypeIndex = 0)
        updatePreview()
    }

    /** 尝试获取当前连接的 WiFi SSID */
    fun getCurrentWifiSsid(): String {
        return try {
            @Suppress("DEPRECATION")
            val wifiManager = app.getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager?.connectionInfo
            val ssid = info?.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: ""
            if (ssid == "<unknown ssid>" || ssid.isEmpty()) "" else ssid
        } catch (e: Exception) {
            ""
        }
    }

    // ── 码制与渲染 ──────────────────────────────────────────

    /** 码制对应的 ZXing 格式 */
    private fun formatFor(codeTypeIndex: Int): BarcodeFormat {
        val label = com.qring.printer.model.CODE_TYPES.getOrNull(codeTypeIndex)?.label ?: "QR Code"
        return when (label) {
            "QR Code" -> BarcodeFormat.QR_CODE
            "Data Matrix" -> BarcodeFormat.DATA_MATRIX
            "Aztec" -> BarcodeFormat.AZTEC
            "PDF417" -> BarcodeFormat.PDF_417
            "Code 128" -> BarcodeFormat.CODE_128
            "Code 39" -> BarcodeFormat.CODE_39
            "Code 93" -> BarcodeFormat.CODE_93
            "EAN-13" -> BarcodeFormat.EAN_13
            "EAN-8" -> BarcodeFormat.EAN_8
            "UPC-A" -> BarcodeFormat.UPC_A
            "ITF" -> BarcodeFormat.ITF
            else -> BarcodeFormat.QR_CODE
        }
    }

    private fun isOneD(codeTypeIndex: Int): Boolean =
        com.qring.printer.model.CODE_TYPES.getOrNull(codeTypeIndex)?.category == com.qring.printer.model.CodeCategory.ONE_D

    private fun isQr(codeTypeIndex: Int): Boolean =
        formatFor(codeTypeIndex) == BarcodeFormat.QR_CODE

    /** 生成码图（不含标注），返回 (binary, width, height) */
    private fun renderCodeFigure(resolvedContent: String, state: CodePrintUiState): Triple<ByteArray, Int, Int>? {
        if (resolvedContent.isEmpty()) return null
        val format = formatFor(state.codeTypeIndex)
        val size = 384

        if (format == BarcodeFormat.PDF_417) {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to 4,
                EncodeHintType.PDF417_DIMENSIONS to com.google.zxing.pdf417.encoder.Dimensions(3, 10, 1, 10)
            )
            val writer = PDF417Writer()
            val encodeW = 384
            val encodeH = 192
            val bitMatrix: BitMatrix = try {
                writer.encode(resolvedContent, format, encodeW, encodeH, hints)
            } catch (e: Exception) {
                writer.encode(resolvedContent, format, size, size, hints)
            }
            val bw = bitMatrix.width
            val bh = bitMatrix.height
            val grayData = IntArray(bw * bh)
            for (y in 0 until bh) {
                for (x in 0 until bw) {
                    grayData[y * bw + x] = if (bitMatrix.get(x, y)) 0 else 255
                }
            }
            val gray = GrayImage(grayData, bw, bh)
            val targetW = (384 * state.scalePercent / 100).coerceAtLeast(38)
            val targetH = (targetW * bh / bw).coerceAtLeast(20)
            val scaled = scaleGrayNearest(gray, targetW, targetH)
            val srcBinary = ditherToBinary(scaled, DitherMode.NONE, 128)
            return Triple(srcBinary, targetW, targetH)
        }

        val hints = mutableMapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        // 二维码纠错等级（仅 QR Code）
        if (isQr(state.codeTypeIndex)) {
            hints[EncodeHintType.ERROR_CORRECTION] = state.ecc.level
        }
        val bitMatrix: BitMatrix = MultiFormatWriter().encode(
            resolvedContent, format, size, size, hints
        )
        val grayData = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                grayData[y * size + x] = if (bitMatrix.get(x, y)) 0 else 255
            }
        }
        var gray = GrayImage(grayData, size, size)
        if (isOneD(state.codeTypeIndex)) {
            gray = squeezeRows(gray, 140)
        }
        val maxDim = 384
        val targetW = (maxDim * state.scalePercent / 100).coerceAtLeast(38)
        val targetH = if (isOneD(state.codeTypeIndex)) {
            (140 * state.scalePercent / 100).coerceAtLeast(20)
        } else {
            targetW
        }
        val scaled = scaleGrayNearest(gray, targetW, targetH)
        val srcBinary = ditherToBinary(scaled, DitherMode.NONE, 128)
        return Triple(srcBinary, targetW, targetH)
    }

    /**
     * 渲染单张条码（含标注文字），返回 384 宽画布 (binary, w, h)。
     * seq/total 用于占位符序号替换。
     */
    private fun renderCode(state: CodePrintUiState, seq: Int, total: Int): Triple<ByteArray, Int, Int>? {
        val resolvedContent = resolvePlaceholders(state.content, state.content, seq, total)
        if (resolvedContent.isEmpty()) return null
        val figure = renderCodeFigure(resolvedContent, state) ?: return null
        val (codeBinary, codeW, codeH) = figure

        val topLine = if (state.showTopText) resolvePlaceholders(state.topText, resolvedContent, seq, total) else ""
        val bottomLine = if (state.showBottomText) resolvePlaceholders(state.bottomText, resolvedContent, seq, total) else ""

        val hasTop = topLine.isNotEmpty()
        val hasBottom = bottomLine.isNotEmpty()

        // 标注文字高度：字号 + 上下留白
        val capFont = state.captionFontSize
        val topH = if (hasTop) (capFont + 8f).toInt() else 0
        val bottomH = if (hasBottom) (capFont + 8f).toInt() else 0

        val canvasW = WIDTH_DOTS
        val canvasH = topH + codeH + bottomH
        val canvas = createBinaryCanvas(canvasW, canvasH)

        // 码图按 alignment 水平偏移
        val offsetX = when (state.alignment) {
            CodeAlignment.LEFT -> 0
            CodeAlignment.CENTER -> ((canvasW - codeW) / 2).coerceAtLeast(0)
            CodeAlignment.RIGHT -> (canvasW - codeW).coerceAtLeast(0)
        }
        blitBinary(canvas, canvasW, canvasH, codeBinary, codeW, codeH, offsetX, topH)

        // 标注文字：转成位图再 blit（与码图同一二值体系）
        if (hasTop) {
            val textBmp = renderCaptionBitmap(topLine, capFont, canvasW)
            val tg = bitmapToGrayRaw(textBmp)
            textBmp.recycle()
            val tb = ditherToBinary(tg, DitherMode.NONE, 211)
            blitBinary(canvas, canvasW, canvasH, tb, tg.width, tg.height, 0, 0)
        }
        if (hasBottom) {
            val textBmp = renderCaptionBitmap(bottomLine, capFont, canvasW)
            val tg = bitmapToGrayRaw(textBmp)
            textBmp.recycle()
            val tb = ditherToBinary(tg, DitherMode.NONE, 211)
            blitBinary(canvas, canvasW, canvasH, tb, tg.width, tg.height, 0, topH + codeH)
        }

        return Triple(canvas, canvasW, canvasH)
    }

    /** 渲染标注文字位图（居中，白底黑字） */
    private fun renderCaptionBitmap(text: String, fontSize: Float, width: Int): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = fontSize
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT
        }
        val fm = paint.fontMetrics
        val textH = (fm.descent - fm.ascent)
        val height = Math.round(textH + 4f)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        // 超宽截断（标注一般不超宽，超了截断处理）
        var display = text
        while (paint.measureText(display) > width - 4 && display.length > 1) {
            display = display.substring(0, display.length - 1)
        }
        if (display != text) {
            display = display.substring(0, display.length - 1) + "…"
        }
        val baseline = (height - (fm.ascent + fm.descent)) / 2f
        canvas.drawText(display, width / 2f, baseline, paint)
        return bmp
    }

    private fun bitmapToGrayRaw(bmp: Bitmap): GrayImage {
        return com.qring.printer.protocol.bitmapToGrayRaw(bmp)
    }

    /** 实时预览：渲染第 1 张 */
    fun updatePreview() {
        val state = _uiState.value
        if (state.content.isEmpty()) {
            val old = _uiState.value.previewBitmap
            if (old != null) {
                _uiState.value = _uiState.value.copy(previewBitmap = null)
                old.recycle()
            }
            return
        }
        viewModelScope.launch {
            try {
                val old = _uiState.value.previewBitmap
                val preview = withContext(Dispatchers.Default) {
                    val result = renderCode(_uiState.value, 1, 1) ?: return@withContext null
                    val (binary, w, h) = result
                    binaryToPreviewBitmap(binary, w, h, false)
                } ?: return@launch
                _uiState.value = _uiState.value.copy(previewBitmap = preview)
                old?.let { if (it != preview) { delay(150); it.recycle() } }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    resultOk = false,
                    resultMessage = "条码生成失败：${e.message}"
                )
            }
        }
    }

    fun print() {
        val state = _uiState.value
        if (state.printing || state.busy) return
        if (printerStatus.value.connState != ConnState.CONNECTED) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请先在首页连接打印机"
            )
            return
        }
        if (state.content.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请输入条码内容"
            )
            return
        }

        val total = effectiveBatchCount()
        _uiState.value = _uiState.value.copy(printing = true, resultMessage = "", progressText = "")

        viewModelScope.launch {
            var fullBmp: Bitmap? = null
            var thumbBmp: Bitmap? = null
            try {
                val fault = withContext(Dispatchers.IO) {
                    printerConnection.preflightCheck()
                }
                if (fault != null) {
                    _uiState.value = _uiState.value.copy(
                        printing = false,
                        resultOk = false,
                        resultMessage = fault
                    )
                    return@launch
                }

                val result = withContext(Dispatchers.Default) {
                    var lastOk = false
                    var lastMsg = ""
                    for (seq in 1..total) {
                        _uiState.value = _uiState.value.copy(
                            progressText = "正在打印第 $seq/$total 张…"
                        )
                        val r = renderCode(_uiState.value, seq, total) ?: continue
                        val (binary, w, h) = r
                        val raster = packBinaryToRaster(binary, w, h)

                        if (fullBmp == null) {
                            fullBmp = binaryToPreviewBitmap(binary, w, h, false)
                            thumbBmp = Bitmap.createScaledBitmap(
                                fullBmp!!, 200, Math.round(200f * fullBmp!!.height / fullBmp!!.width), true
                            )
                        }

                        val printResult = withContext(Dispatchers.IO) {
                            printerConnection.printRaster(raster, 1)
                        }
                        lastOk = printResult.ok
                        lastMsg = printResult.message
                        if (!printResult.ok) break
                    }

                    // 全部成功保存历史
                    if (lastOk) {
                        try {
                            val payload = JSONObject().apply {
                                put("content", _uiState.value.content)
                                put("codeTypeIndex", _uiState.value.codeTypeIndex)
                                put("ecc", _uiState.value.ecc.ordinal)
                                put("scalePercent", _uiState.value.scalePercent)
                                put("alignment", _uiState.value.alignment.ordinal)
                                put("showTopText", _uiState.value.showTopText)
                                put("topText", _uiState.value.topText)
                                put("showBottomText", _uiState.value.showBottomText)
                                put("bottomText", _uiState.value.bottomText)
                                put("captionFontSize", _uiState.value.captionFontSize.toDouble())
                                put("batchCount", _uiState.value.batchCount)
                            }.toString()
                            historyRepo.saveHistory(HIST_TYPE_CODE, thumbBmp!!, payload)
                        } catch (e: Exception) {
                            Timber.tag("CodeVM").w(e, "saveHistory failed")
                        }
                    }
                    com.qring.printer.bt.PrintResult(lastOk, if (lastOk) "打印完成，共 $total 张" else lastMsg)
                }

                _uiState.value = _uiState.value.copy(
                    printing = false,
                    resultOk = result.ok,
                    resultMessage = result.message,
                    progressText = ""
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    printing = false,
                    resultOk = false,
                    resultMessage = "打印失败：${e.message}",
                    progressText = ""
                )
            } finally {
                try { fullBmp?.recycle() } catch (e: Exception) { }
                try { thumbBmp?.recycle() } catch (e: Exception) { }
                if (_uiState.value.printing) {
                    _uiState.value = _uiState.value.copy(printing = false)
                }
            }
        }
    }

    fun dismissPreview() {
        _uiState.value = _uiState.value.copy(showPreview = false)
    }

    fun clearResult() {
        _uiState.value = _uiState.value.copy(resultMessage = "", resultOk = false)
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.previewBitmap?.recycle()
    }
}
