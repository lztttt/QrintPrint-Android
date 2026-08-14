package com.qring.printer.ui.customprint

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.printer.bt.PrinterConnection
import com.qring.printer.data.HistoryPayloadHolder
import com.qring.printer.data.HistoryRepository
import com.qring.printer.data.TemplateRepository
import com.qring.printer.model.CanvasDoc
import com.qring.printer.model.CanvasElement
import com.qring.printer.model.ConnState
import com.qring.printer.model.ElementKind
import com.qring.printer.model.HIST_TYPE_CUSTOM
import com.qring.printer.model.PrinterStatus
import com.qring.printer.model.PrinterStatusRepository
import com.qring.printer.model.TemplateRecord
import com.qring.printer.protocol.GrayImage
import com.qring.printer.protocol.TextRenderOptions
import com.qring.printer.protocol.WIDTH_DOTS
import com.qring.printer.render.CanvasComposite
import com.qring.printer.render.DEFAULT_CODE_1D_WIDTH
import com.qring.printer.render.DEFAULT_CODE_2D_SIZE
import com.qring.printer.render.DEFAULT_IMAGE_WIDTH
import com.qring.printer.render.codeOneDAspect
import com.qring.printer.render.compositeToBitmap
import com.qring.printer.render.compositeToPrintRaster
import com.qring.printer.render.composeCanvas
import com.qring.printer.render.loadImageGray
import com.qring.printer.render.renderElementNow
import com.qring.printer.ui.common.FontList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class CustomPrintUiState(
    val doc: CanvasDoc = CanvasDoc(),
    val revision: Int = 0,
    val busy: Boolean = false,
    val busyHint: String = "",
    val printing: Boolean = false,
    val resultMessage: String = "",
    val resultOk: Boolean = false,
    val showEditor: Boolean = false,
    val showTemplateDialog: Boolean = false,
    val templateName: String = "",
    val currentTemplateId: String = "",
    val currentTemplateName: String = "",
    val compositeBitmap: Bitmap? = null,
    val showPreview: Boolean = false,
    // 打印浓度 1~5，0 表示默认
    val thickness: Int = 1,
)

class CustomPrintViewModel(application: Application) : AndroidViewModel(application) {

    private val printerConnection = PrinterConnection.getInstance()
    private val templateRepo = TemplateRepository(application)
    private val historyRepo = HistoryRepository(application)

    private val _uiState = MutableStateFlow(CustomPrintUiState())
    val uiState: StateFlow<CustomPrintUiState> = _uiState.asStateFlow()

    val printerStatus: StateFlow<PrinterStatus> = PrinterStatusRepository.state

    private val _fontFamilies = MutableStateFlow(listOf("sans-serif", "serif", "monospace"))
    val fontFamilies: StateFlow<List<String>> = _fontFamilies.asStateFlow()

    init {
        restoreFromHistoryPayload()
    }

    fun loadFonts() {
        _fontFamilies.value = FontList.getSystemFonts(getApplication())
    }

    /** 元素在队列里的显示名：文字1、图片1、文字2…（按类型分别计数） */
    fun elementLabel(el: CanvasElement): String {
        val kindLabel = when (el.kind) {
            ElementKind.TEXT -> "文字"
            ElementKind.IMAGE -> "图片"
            ElementKind.CODE -> "条码"
        }
        val n = _uiState.value.doc.elements
            .takeWhile { it.id != el.id }
            .count { it.kind == el.kind } + 1
        return "$kindLabel$n"
    }

    /** 设置选中文字元素的字体 */
    fun setElementFontFamily(index: Int) {
        val doc = _uiState.value.doc
        val sel = doc.selected() ?: return
        if (sel.kind == ElementKind.TEXT) {
            val families = _fontFamilies.value
            if (index in families.indices) {
                sel.textOptions = sel.textOptions.copy(fontFamily = families[index])
                runRender(sel)
            }
        }
    }

    private fun restoreFromHistoryPayload() {
        val (type, payload) = HistoryPayloadHolder.consumePayload() ?: return
        if (type != HIST_TYPE_CUSTOM) {
            HistoryPayloadHolder.setPayload(type, payload)
            return
        }
        viewModelScope.launch {
            try {
                val obj = org.json.JSONObject(payload)
                val minLength = obj.optInt("minLength", 200)
                val elementsArr = obj.optJSONArray("elements") ?: return@launch
                val landscape = obj.optBoolean("landscape", false)
                val thickness = obj.optInt("thickness", 1)

                _uiState.value.doc.releaseAll()
                _uiState.value = _uiState.value.copy(
                    doc = CanvasDoc().apply {
                        this.minLength = minLength
                        this.landscape = landscape
                    },
                    thickness = thickness
                )

                for (i in 0 until elementsArr.length()) {
                    val elObj = elementsArr.getJSONObject(i)
                    val kind = when (elObj.optString("kind", "TEXT")) {
                        "TEXT" -> ElementKind.TEXT
                        "IMAGE" -> ElementKind.IMAGE
                        "CODE" -> ElementKind.CODE
                        else -> ElementKind.TEXT
                    }
                    val optsObj = elObj.optJSONObject("textOptions")
                    val textOptions = com.qring.printer.protocol.TextRenderOptions(
                        fontFamily = optsObj?.optString("fontFamily", "sans-serif") ?: "sans-serif",
                        fontSize = optsObj?.optDouble("fontSize", 24.0)?.toFloat() ?: 24f,
                        bold = optsObj?.optBoolean("bold", false) ?: false,
                        italic = optsObj?.optBoolean("italic", false) ?: false,
                        underline = optsObj?.optBoolean("underline", false) ?: false,
                        letterSpacing = optsObj?.optDouble("letterSpacing", 0.0)?.toFloat() ?: 0f,
                        lineSpacing = optsObj?.optDouble("lineSpacing", 6.0)?.toFloat() ?: 6f,
                        margin = optsObj?.optDouble("margin", 8.0)?.toFloat() ?: 8f,
                        alignment = com.qring.printer.protocol.TextAlignment.entries
                            .getOrElse(optsObj?.optInt("alignment", 0) ?: 0) { com.qring.printer.protocol.TextAlignment.LEFT }
                    )
                    val el = CanvasElement(kind = kind).apply {
                        dotX = elObj.optInt("dotX", 0)
                        dotY = elObj.optInt("dotY", 0)
                        dotW = elObj.optInt("dotW", 100)
                        dotH = elObj.optInt("dotH", 100)
                        aspect = elObj.optDouble("aspect", 1.0).toFloat()
                        geometryLocked = elObj.optBoolean("geometryLocked", false)
                        text = elObj.optString("text", "")
                        this.textOptions = textOptions
                        imageUri = elObj.optString("imageUri", "")
                        ditherMode = com.qring.printer.protocol.DitherMode.entries
                            .getOrElse(elObj.optInt("ditherMode", 1)) { com.qring.printer.protocol.DitherMode.FLOYD_STEINBERG }
                        ditherThreshold = elObj.optInt("ditherThreshold", 128)
                        codeContent = elObj.optString("codeContent", "")
                        codeTypeIndex = elObj.optInt("codeTypeIndex", 0)
                        rotation = elObj.optInt("rotation", 0)
                        flipH = elObj.optBoolean("flipH", false)
                        flipV = elObj.optBoolean("flipV", false)
                        invert = elObj.optBoolean("invert", false)
                    }
                    // 图片元素：保持保存时的几何（不重新按真实比例计算）
                    if (el.kind == ElementKind.IMAGE) el.geometryLocked = true
                    _uiState.value.doc.add(el)
                    runRender(el)
                }
                updateComposite()
            } catch (e: Exception) {
                Timber.tag("CustomVM").w(e, "restoreFromHistoryPayload failed")
            }
        }
    }

    // ── 元素插入 ──────────────────────────────────────────────

    fun insertText() {
        val state = _uiState.value
        if (state.busy || state.printing) return
        val el = CanvasElement(kind = ElementKind.TEXT).apply {
            text = "点击编辑文字"
            textOptions = TextRenderOptions()
            dotH = textOptions.fontSize.toInt() + textOptions.margin.toInt() * 2
            dotX = 0
            dotY = nextInsertY(state.doc)
        }
        state.doc.add(el)
        bump()
        runRender(el)
    }

    fun insertImage(uri: Uri) {
        val state = _uiState.value
        if (state.busy || state.printing) return
        val el = CanvasElement(kind = ElementKind.IMAGE).apply {
            imageUri = uri.toString()
            dotW = DEFAULT_IMAGE_WIDTH
            dotH = DEFAULT_IMAGE_WIDTH
            aspect = 1f
            dotX = maxOf(0, (WIDTH_DOTS - dotW) / 2)
            dotY = nextInsertY(state.doc)
        }
        state.doc.add(el)
        bump()
        runRender(el)
    }

    fun insertCode(codeTypeIndex: Int = 0) {
        val state = _uiState.value
        if (state.busy || state.printing) return
        val is1D = codeTypeIndex >= 0 &&
            codeTypeIndex < com.qring.printer.model.CODE_TYPES.size &&
            com.qring.printer.model.CODE_TYPES[codeTypeIndex].category == com.qring.printer.model.CodeCategory.ONE_D
        val el = CanvasElement(kind = ElementKind.CODE).apply {
            codeContent = if (is1D) "12345678" else "https://example.com"
            this.codeTypeIndex = codeTypeIndex
            if (is1D) {
                dotW = DEFAULT_CODE_1D_WIDTH
                dotH = (DEFAULT_CODE_1D_WIDTH / codeOneDAspect()).toInt()
                aspect = codeOneDAspect()
            } else {
                dotW = DEFAULT_CODE_2D_SIZE
                dotH = DEFAULT_CODE_2D_SIZE
                aspect = 1f
            }
            dotX = maxOf(0, (WIDTH_DOTS - dotW) / 2)
            dotY = nextInsertY(state.doc)
        }
        state.doc.add(el)
        bump()
        runRender(el)
    }

    // ── 元素操作 ──────────────────────────────────────────────

    fun selectElement(id: String) {
        _uiState.value.doc.selectedId = id
        bump()
    }

    fun deselect() {
        _uiState.value.doc.selectedId = ""
        bump()
    }

    fun deleteSelected() {
        val doc = _uiState.value.doc
        val sel = doc.selected() ?: return
        doc.remove(sel.id)
        bump()
        updateComposite()
    }

    fun moveSelected(dx: Int, dy: Int) {
        val doc = _uiState.value.doc
        val sel = doc.selected() ?: return
        if (doc.landscape) {
            // 横排：dotX 方向限制在 0..384（打印宽度方向），dotY 方向可无限延伸
            // 元素内容预旋转后正立，纵向占用是 dotH（而非 dotW）
            val maxX = maxOf(0, 384 - sel.dotH)
            sel.dotX = (sel.dotX + dx).coerceIn(0, maxX)
            sel.dotY = sel.dotY + dy
        } else {
            // 竖排：不限制移出左右、上边框，边框外的部分由 blitBinary 裁剪不渲染
            sel.dotX = sel.dotX + dx
            sel.dotY = sel.dotY + dy
        }
        bump()          // 选框实时跟手
        updateComposite() // 画布实时合成（取消旧任务防堆积）
    }

    fun resizeSelected(newW: Int, newH: Int) {
        val doc = _uiState.value.doc
        val sel = doc.selected() ?: return
        sel.dotW = maxOf(1, newW)
        sel.dotH = maxOf(1, newH)
        // 重新渲染
        runRender(sel)
    }

    fun updateSelectedText(text: String) {
        val doc = _uiState.value.doc
        val sel = doc.selected() ?: return
        if (sel.kind == ElementKind.TEXT) {
            sel.text = text
            bump()
            runRender(sel)
        }
    }

    fun updateSelectedCodeContent(content: String) {
        val doc = _uiState.value.doc
        val sel = doc.selected() ?: return
        if (sel.kind == ElementKind.CODE) {
            sel.codeContent = content
            bump()
            runRender(sel)
        }
    }

    fun updateSelectedCodeType(index: Int) {
        val doc = _uiState.value.doc
        val sel = doc.selected() ?: return
        if (sel.kind == ElementKind.CODE) {
            sel.codeTypeIndex = index
            // 更新尺寸
            val is1D = index < com.qring.printer.model.CODE_TYPES.size &&
                com.qring.printer.model.CODE_TYPES[index].category == com.qring.printer.model.CodeCategory.ONE_D
            if (is1D) {
                sel.dotW = DEFAULT_CODE_1D_WIDTH
                sel.dotH = (DEFAULT_CODE_1D_WIDTH / codeOneDAspect()).toInt()
                sel.aspect = codeOneDAspect()
            } else {
                sel.dotW = DEFAULT_CODE_2D_SIZE
                sel.dotH = DEFAULT_CODE_2D_SIZE
                sel.aspect = 1f
            }
            sel.dotX = maxOf(0, (WIDTH_DOTS - sel.dotW) / 2)
            bump()
            runRender(sel)
        }
    }

    fun updateSelectedTextOptions(options: TextRenderOptions) {
        val doc = _uiState.value.doc
        val sel = doc.selected() ?: return
        if (sel.kind == ElementKind.TEXT) {
            sel.textOptions = options
            bump()
            runRender(sel)
        }
    }

    fun updateSelectedDither(mode: com.qring.printer.protocol.DitherMode) {
        val doc = _uiState.value.doc
        val sel = doc.selected() ?: return
        if (sel.kind == ElementKind.IMAGE) {
            sel.ditherMode = mode
            bump()
            runRender(sel)
        }
    }

    fun setSelectedImageThreshold(threshold: Int) {
        val sel = _uiState.value.doc.selected() ?: return
        if (sel.kind == ElementKind.IMAGE) {
            sel.ditherThreshold = threshold.coerceIn(0, 255)
            bump()
            runRender(sel)
        }
    }

    /** 调整选中元素宽度（24~384），高度按宽高比自动算 */
    fun setSelectedSize(width: Int) {
        val sel = _uiState.value.doc.selected() ?: return
        val w = width.coerceIn(24, 384)
        val h = maxOf(1, Math.round(w / maxOf(0.01f, sel.aspect)))
        sel.dotW = w
        sel.dotH = h
        sel.geometryLocked = true
        bump()
        runRender(sel)
    }

    /** 图片缩放：百分比 10~150，基于 384 点宽 */
    fun setSelectedScale(pct: Int) {
        val sel = _uiState.value.doc.selected() ?: return
        val p = pct.coerceIn(10, 150)
        val w = Math.round(384f * p / 100f).coerceIn(24, 384)
        val h = maxOf(1, Math.round(w / maxOf(0.01f, sel.aspect)))
        sel.dotW = w
        sel.dotH = h
        sel.geometryLocked = true
        bump()
        runRender(sel)
    }

    /** 元素旋转：0~360 度 */
    fun setSelectedRotation(degrees: Int) {
        val sel = _uiState.value.doc.selected() ?: return
        sel.rotation = ((degrees % 360) + 360) % 360
        bump()
        runRender(sel)
    }

    /** 切换水平翻转 */
    fun toggleFlipH() {
        val sel = _uiState.value.doc.selected() ?: return
        sel.flipH = !sel.flipH
        bump()
        runRender(sel)
    }

    /** 切换垂直翻转 */
fun toggleFlipV() {
val sel = _uiState.value.doc.selected() ?: return
sel.flipV = !sel.flipV
bump()
runRender(sel)
}

/** 反色切换 */
fun toggleInvert() {
val sel = _uiState.value.doc.selected() ?: return
sel.invert = !sel.invert
bump()
runRender(sel)
}

    /** 画布竖排/横排 */
    fun setLandscape(landscape: Boolean) {
        val doc = _uiState.value.doc
        doc.landscape = landscape
        bump()
        updateComposite()
    }

    fun setThickness(thickness: Int?) {
        _uiState.value = _uiState.value.copy(thickness = thickness ?: 0)
    }

    fun swapSelectedImage(uri: Uri) {
        val doc = _uiState.value.doc
        val sel = doc.selected() ?: return
        if (sel.kind == ElementKind.IMAGE) {
            sel.imageUri = uri.toString()
            sel.sourceGray = null
            sel.geometryLocked = false
            runRender(sel)
        }
    }

    // ── 渲染 ──────────────────────────────────────────────────

    private fun runRender(el: CanvasElement) {
        viewModelScope.launch {
            try {
                val (binary, preview) = withContext(Dispatchers.Default) {
                    renderElementNow(getApplication(), el) ?: return@withContext null
                } ?: return@launch
                el.binary = binary
                el.preview?.recycle()
                el.preview = preview
                el.rendering = false
                bump()
                updateComposite()
            } catch (e: Exception) {
                el.rendering = false
                bump()
            }
        }
    }

    private var compositeJob: kotlinx.coroutines.Job? = null

    private fun updateComposite() {
        // 取消上一次合成，避免旧结果（旧横竖排/旧图层顺序）覆盖新状态
        compositeJob?.cancel()
        compositeJob = viewModelScope.launch {
            try {
                val composite = withContext(Dispatchers.Default) {
                    composeCanvas(_uiState.value.doc)
                }
                val bitmap = compositeToBitmap(composite)
                val old = _uiState.value.compositeBitmap
                _uiState.value = _uiState.value.copy(compositeBitmap = bitmap)
                old?.recycle()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 被新任务取消，忽略
            }
        }
    }

    private fun bump() {
        _uiState.value = _uiState.value.copy(
            revision = _uiState.value.revision + 1,
            doc = _uiState.value.doc
        )
    }

    // ── 模板操作 ──────────────────────────────────────────────

    fun loadTemplate(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, busyHint = "正在加载模板…")
            try {
                val (doc, record) = withContext(Dispatchers.IO) {
                    templateRepo.loadTemplateAsDoc(id)
                } ?: run {
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        resultOk = false,
                        resultMessage = "模板不存在"
                    )
                    return@launch
                }

                _uiState.value.doc.releaseAll()
                _uiState.value = _uiState.value.copy(
                    doc = doc,
                    currentTemplateId = id,
                    currentTemplateName = record.name,
                    busy = false
                )

                // 渲染所有元素
                doc.elements.forEach { runRender(it) }
                updateComposite()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    resultOk = false,
                    resultMessage = "加载模板失败"
                )
            }
        }
    }

    fun showSaveDialog() {
        val doc = _uiState.value.doc
        if (doc.elements.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "画布是空的，先插入点内容"
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            showEditor = false,
            showTemplateDialog = true,
            templateName = _uiState.value.currentTemplateName
        )
    }

    fun dismissSaveDialog() {
        _uiState.value = _uiState.value.copy(showTemplateDialog = false)
    }

    fun updateTemplateName(name: String) {
        _uiState.value = _uiState.value.copy(templateName = name)
    }

    fun confirmSave() {
        val name = _uiState.value.templateName.trim()
        if (name.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请输入模板名称"
            )
            return
        }
        viewModelScope.launch {
            try {
                val targetId = _uiState.value.currentTemplateId.ifEmpty { null }
                withContext(Dispatchers.IO) {
                    templateRepo.saveTemplate(name, _uiState.value.doc, targetId)
                }
                _uiState.value = _uiState.value.copy(
                    showTemplateDialog = false,
                    resultOk = true,
                    resultMessage = "模板「$name」已保存"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    resultOk = false,
                    resultMessage = "保存失败"
                )
            }
        }
    }

    fun confirmSaveAs() {
        val name = _uiState.value.templateName.trim()
        if (name.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "请输入模板名称"
            )
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    templateRepo.saveTemplate(name, _uiState.value.doc, null)
                }
                _uiState.value = _uiState.value.copy(
                    showTemplateDialog = false,
                    resultOk = true,
                    resultMessage = "模板「$name」已保存"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    resultOk = false,
                    resultMessage = "保存失败"
                )
            }
        }
    }

    fun clearCanvas() {
        val doc = _uiState.value.doc
        if (doc.elements.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                resultOk = false,
                resultMessage = "画布已经是空的"
            )
            return
        }
        doc.releaseAll()
        _uiState.value = _uiState.value.copy(
            doc = doc,
            resultOk = true,
            resultMessage = "画布已清空"
        )
        bump()
        updateComposite()
    }

    // ── 打印 ──────────────────────────────────────────────────

    fun print() {
        val state = _uiState.value
        if (state.printing || state.busy) return
        if (printerStatus.value.connState != ConnState.CONNECTED) {
            _uiState.value = state.copy(resultOk = false, resultMessage = "请先在首页连接打印机")
            return
        }
        if (state.doc.elements.isEmpty()) {
            _uiState.value = state.copy(resultOk = false, resultMessage = "画布是空的，先插入点内容")
            return
        }

        _uiState.value = state.copy(printing = true, resultMessage = "")

        viewModelScope.launch {
            try {
                val fault = withContext(Dispatchers.IO) { printerConnection.preflightCheck() }
                if (fault != null) {
                    _uiState.value = _uiState.value.copy(printing = false, resultOk = false, resultMessage = fault)
                    return@launch
                }

                val result = withContext(Dispatchers.Default) {
                    val composite = composeCanvas(_uiState.value.doc)
                    // 横排时 compositeToPrintRaster 会把 H×384 再旋转 90° CW → 384×H
                    // 确保打印机收到标准的 48 字节/行数据
                    val raster = compositeToPrintRaster(_uiState.value.doc, composite)

                    // 生成缩略图（用原始合成图，保持横排预览方向）
                    val fullBmp = compositeToBitmap(composite)
                    val thumbBmp = Bitmap.createScaledBitmap(fullBmp, 200, Math.round(200f * fullBmp.height / fullBmp.width), true)

                    val printResult = withContext(Dispatchers.IO) {
                        printerConnection.printRaster(raster, _uiState.value.thickness.takeIf { it > 0 })
                    }

                    // 打印成功后保存历史（完整字段，支持重打还原）
                    if (printResult.ok) {
                        try {
                            // IMAGE 元素：content:// 先拷贝到内部存储，避免重打时权限过期
                            val uriMap = mutableMapOf<String, String>()
                            _uiState.value.doc.elements
                                .filter { it.kind == ElementKind.IMAGE && it.imageUri.startsWith("content://") }
                                .forEach {
                                    val saved = historyRepo.saveImageToInternalStorage(it.imageUri)
                                    if (saved != null) uriMap[it.imageUri] = saved
                                }

                            val elementsData = _uiState.value.doc.elements.map { el ->
                                mapOf(
                                    "kind" to el.kind.name,
                                    "dotX" to el.dotX,
                                    "dotY" to el.dotY,
                                    "dotW" to el.dotW,
                                    "dotH" to el.dotH,
                                    "aspect" to el.aspect.toDouble(),
                                    "geometryLocked" to el.geometryLocked,
                                    "text" to el.text,
                                    "imageUri" to (uriMap[el.imageUri] ?: el.imageUri),
                                    "ditherMode" to el.ditherMode.code,
                                    "ditherThreshold" to el.ditherThreshold,
                                    "codeContent" to el.codeContent,
                                    "codeTypeIndex" to el.codeTypeIndex,
                                    "rotation" to el.rotation,
                                    "flipH" to el.flipH,
                                    "flipV" to el.flipV,
                                    "invert" to el.invert,
                                    "textOptions" to mapOf(
                                        "fontFamily" to el.textOptions.fontFamily,
                                        "fontSize" to el.textOptions.fontSize.toDouble(),
                                        "bold" to el.textOptions.bold,
                                        "italic" to el.textOptions.italic,
                                        "underline" to el.textOptions.underline,
                                        "letterSpacing" to el.textOptions.letterSpacing.toDouble(),
                                        "lineSpacing" to el.textOptions.lineSpacing.toDouble(),
                                        "margin" to el.textOptions.margin.toDouble(),
                                        "alignment" to el.textOptions.alignment.ordinal
                                    )
                                )
                            }
                            val payload = org.json.JSONObject().apply {
                                put("minLength", _uiState.value.doc.minLength)
                                put("landscape", _uiState.value.doc.landscape)
                                put("thickness", _uiState.value.thickness)
                                put("elements", org.json.JSONArray().apply {
                                    elementsData.forEach { put(org.json.JSONObject(it)) }
                                })
                            }.toString()
                            historyRepo.saveHistory(HIST_TYPE_CUSTOM, thumbBmp, payload)
                        } catch (e: Exception) {
                            Timber.tag("CustomVM").w(e, "saveHistory failed")
                        }
                    }

                    fullBmp.recycle()
                    printResult
                }

                _uiState.value = _uiState.value.copy(
                    printing = false,
                    resultOk = result.ok,
                    resultMessage = result.message
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    printing = false,
                    resultOk = false,
                    resultMessage = "打印失败：${e.message}"
                )
            }
        }
    }

    fun showPreview() {
        updateComposite()
        _uiState.value = _uiState.value.copy(showPreview = true)
    }

    fun dismissPreview() {
        _uiState.value = _uiState.value.copy(showPreview = false)
    }

    fun clearResult() {
        _uiState.value = _uiState.value.copy(resultMessage = "", resultOk = false)
    }

    fun showElementEditor() {
        _uiState.value = _uiState.value.copy(showEditor = true)
    }

    fun dismissElementEditor() {
        _uiState.value = _uiState.value.copy(showEditor = false)
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.doc.releaseAll()
        _uiState.value.compositeBitmap?.recycle()
    }
}

private fun nextInsertY(doc: CanvasDoc): Int {
    var bottom = 0
    for (el in doc.elements) {
        // 横排时元素预旋转 270°，纵向占用是 dotW 而非 dotH
        val elBottom = if (doc.landscape) el.dotY + el.dotW else el.dotY + el.dotH
        if (elBottom > bottom) bottom = elBottom
    }
    return if (bottom > 0) bottom + 8 else 8
}
