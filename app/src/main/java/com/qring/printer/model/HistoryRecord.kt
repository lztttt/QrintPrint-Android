package com.qring.printer.model

/**
 * 模板记录。
 * 存 SharedPreferences JSON 索引 + 缩略图文件。
 */
data class TemplateRecord(
    val id: String,
    val name: String,
    val minLength: Int,
    val updatedAt: Long,
    val thumbnailPath: String,
    val elements: List<TemplateElementData>,
    val landscape: Boolean = false
)

/**
 * 序列化中间态。CanvasElement 里的 Bitmap/GrayImage 不直接序列化，
 * 只存重建它所需的数据。
 */
data class TemplateElementData(
    val kind: String,
    val dotX: Int,
    val dotY: Int,
    val dotW: Int,
    val dotH: Int,
    val aspect: Float,
    val geometryLocked: Boolean,
    val text: String,
    val textOptions: com.qring.printer.protocol.TextRenderOptions,
    val imageUri: String,
    val ditherMode: Int,
    val ditherThreshold: Int = 128,
    val codeContent: String,
    val codeTypeIndex: Int,
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val invert: Boolean = false
)

/**
 * 历史打印记录。
 */
data class HistoryRecord(
    val id: String,
    val typeName: String,
    val thumbnailPath: String,
    val payload: String,
    val createdAt: Long
)

const val HIST_TYPE_TEXT = "text"
const val HIST_TYPE_IMAGE = "image"
const val HIST_TYPE_CODE = "code"
const val HIST_TYPE_CUSTOM = "custom"
const val HIST_TYPE_SCHEDULE = "schedule"
const val HIST_TYPE_TODO = "todo"
const val HIST_TYPE_TABLE = "table"
const val HIST_TYPE_MARKDOWN = "markdown"
const val HIST_TYPE_WRONGBOOK = "wrongbook"
