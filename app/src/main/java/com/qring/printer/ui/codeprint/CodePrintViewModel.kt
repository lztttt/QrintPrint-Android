package com.qring.printer.ui.codeprint

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
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

enum class CodeAlignment(val label: String) {
    LEFT("左对齐"),
    CENTER("居中"),
    RIGHT("右对齐")
}

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
                codeTypeIndex = codeTypeIndex
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

    /** 快速模板填充 */
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

    /**
     * 把内容渲染成 384 点宽的条码灰度 + 二值，返回 (binary, width, height)。
     * 码图按 scalePercent 缩放并按 alignment 对齐在 384 宽画布上。
     * 内容为空返回 null。
     */
    private fun renderCode(state: CodePrintUiState): Triple<ByteArray, Int, Int>? {
        if (state.content.isEmpty()) return null
        val format = formatFor(state.codeTypeIndex)
        val size = 384

        // PDF417 需要特殊处理：它不是正方形的，且需要专门的 writer
        if (format == BarcodeFormat.PDF_417) {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to 4,
                EncodeHintType.PDF417_DIMENSIONS to com.google.zxing.pdf417.encoder.Dimensions(3, 10, 1, 10)
            )
            val writer = PDF417Writer()
            // PDF417 用较窄的宽度生成，因为它本身是宽矩形
            val encodeW = 384
            val encodeH = 192
            val bitMatrix: BitMatrix = try {
                writer.encode(state.content, format, encodeW, encodeH, hints)
            } catch (e: Exception) {
                // 退化：用默认尺寸再试
                writer.encode(state.content, format, size, size, hints)
            }
            val bw = bitMatrix.width
            val bh = bitMatrix.height
            val grayData = IntArray(bw * bh)
            for (y in 0 until bh) {
                for (x in 0 until bw) {
                    grayData[y * bw + x] = if (bitMatrix.get(x, y)) 0 else 255
                }
            }
            var gray = GrayImage(grayData, bw, bh)
            // 缩放到目标尺寸
            val targetW = (384 * state.scalePercent / 100).coerceAtLeast(38)
            val targetH = (targetW * bh / bw).coerceAtLeast(20)
            val scaled = scaleGrayNearest(gray, targetW, targetH)
            val srcBinary = ditherToBinary(scaled, DitherMode.NONE, 128)
            // 居中放到 384 宽画布
            val canvasW = WIDTH_DOTS
            val canvasH = targetH
            val canvas = createBinaryCanvas(canvasW, canvasH)
            val offsetX = when (state.alignment) {
                CodeAlignment.LEFT -> 0
                CodeAlignment.CENTER -> ((canvasW - targetW) / 2).coerceAtLeast(0)
                CodeAlignment.RIGHT -> (canvasW - targetW).coerceAtLeast(0)
            }
            blitBinary(canvas, canvasW, canvasH, srcBinary, targetW, targetH, offsetX, 0)
            return Triple(canvas, canvasW, canvasH)
        }

        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix: BitMatrix = MultiFormatWriter().encode(
            state.content, format, size, size, hints
        )
        val grayData = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                grayData[y * size + x] = if (bitMatrix.get(x, y)) 0 else 255
            }
        }
        var gray = GrayImage(grayData, size, size)
        // 一维码压扁
        if (isOneD(state.codeTypeIndex)) {
            gray = squeezeRows(gray, 140)
        }
        // 根据 scalePercent 计算目标尺寸：10%~100% 对应 38~384 点
        val maxDim = if (isOneD(state.codeTypeIndex)) 384 else 384
        val targetW = (maxDim * state.scalePercent / 100).coerceAtLeast(38)
        val targetH = if (isOneD(state.codeTypeIndex)) {
            (140 * state.scalePercent / 100).coerceAtLeast(20)
        } else {
            targetW  // 二维码正方形
        }
        val scaled = scaleGrayNearest(gray, targetW, targetH)
        // 纯阈值，不抖动
        val srcBinary = ditherToBinary(scaled, DitherMode.NONE, 128)
        // 按 alignment 放到 384 宽画布上
        val canvasW = WIDTH_DOTS
        val canvasH = targetH
        val canvas = createBinaryCanvas(canvasW, canvasH)
        val offsetX = when (state.alignment) {
            CodeAlignment.LEFT -> 0
            CodeAlignment.CENTER -> ((canvasW - targetW) / 2).coerceAtLeast(0)
            CodeAlignment.RIGHT -> (canvasW - targetW).coerceAtLeast(0)
        }
        blitBinary(canvas, canvasW, canvasH, srcBinary, targetW, targetH, offsetX, 0)
        return Triple(canvas, canvasW, canvasH)
    }

    /** 实时预览：内容/码制变化时调用 */
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
                    val result = renderCode(_uiState.value) ?: return@withContext null
                    val (binary, w, h) = result
                    binaryToPreviewBitmap(binary, w, h, false)
                } ?: return@launch
                _uiState.value = _uiState.value.copy(previewBitmap = preview)
                // 等当前帧画完旧位图再回收
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

        _uiState.value = _uiState.value.copy(printing = true, resultMessage = "")

        viewModelScope.launch {
            var fullBmp: Bitmap? = null
            var thumbBmp: Bitmap? = null
            try {
                // 打印前体检
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

                // 重新生成并打印
                val result = withContext(Dispatchers.Default) {
                    val r = renderCode(_uiState.value) ?: return@withContext null
                    val (binary, w, h) = r
                    val raster = packBinaryToRaster(binary, w, h)

                    // 生成缩略图
                    fullBmp = binaryToPreviewBitmap(binary, w, h, false)
                    thumbBmp = Bitmap.createScaledBitmap(fullBmp!!, 200, Math.round(200f * fullBmp!!.height / fullBmp!!.width), true)

                    val printResult = withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, 1)
                    }

                    // 打印成功后保存历史
                    if (printResult.ok) {
                        try {
                            val payload = org.json.JSONObject().apply {
                                put("content", _uiState.value.content)
                                put("codeTypeIndex", _uiState.value.codeTypeIndex)
                            }.toString()
                            historyRepo.saveHistory(HIST_TYPE_CODE, thumbBmp!!, payload)
                        } catch (e: Exception) { }
                    }

                    printResult
                }

                _uiState.value = _uiState.value.copy(
                    printing = false,
                    resultOk = result?.ok ?: false,
                    resultMessage = result?.message ?: "请输入条码内容"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    printing = false,
                    resultOk = false,
                    resultMessage = "打印失败：${e.message}"
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
