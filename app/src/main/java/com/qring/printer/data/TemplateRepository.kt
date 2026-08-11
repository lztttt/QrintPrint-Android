package com.qring.printer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.qring.printer.model.TemplateRecord
import com.qring.printer.model.TemplateElementData
import com.qring.printer.render.elementToTemplateData
import com.qring.printer.render.templateDataToElement
import com.qring.printer.model.CanvasDoc
import com.qring.printer.model.CanvasElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * 模板存储仓库。
 * 存 SharedPreferences JSON 索引 + 缩略图文件。
 */
class TemplateRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("qringprint_templates", Context.MODE_PRIVATE)
    private val thumbnailsDir = File(context.filesDir, "templates").apply { mkdirs() }
    private val imagesDir = File(context.filesDir, "template_images").apply { mkdirs() }

    /**
     * 保存图片到内部存储，返回内部存储路径
     */
    fun saveImageToInternalStorage(uriString: String): String? {
        return try {
            val uri = android.net.Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { input ->
                val id = UUID.randomUUID().toString().take(8) + ".jpg"
                val file = File(imagesDir, "tpl_img_$id")
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
                file.absolutePath
            }
        } catch (e: Exception) {
            Timber.tag("TemplateRepo").e(e, "failed to copy image to internal storage")
            null
        }
    }

    /**
     * 保存模板。返回模板 id。
     */
    suspend fun saveTemplate(
        name: String,
        doc: CanvasDoc,
        existingId: String? = null
    ): String = withContext(Dispatchers.IO) {
        val id = existingId ?: UUID.randomUUID().toString().take(8)
        
        // 处理图片元素的 URI，将 content:// 复制到内部存储
        val processedElements = doc.elements.map { el ->
            if (el.kind.name == "IMAGE" && el.imageUri.startsWith("content://")) {
                val savedPath = saveImageToInternalStorage(el.imageUri)
                if (savedPath != null) {
                    val newEl = el.copy(imageUri = savedPath)
                    elementToTemplateData(newEl)
                } else {
                    elementToTemplateData(el)
                }
            } else {
                elementToTemplateData(el)
            }
        }

        // 生成缩略图
        val thumbPath = try {
            val composite = com.qring.printer.render.composeCanvas(doc)
            val bmp = com.qring.printer.render.compositeToBitmap(composite)
            val scaled = Bitmap.createScaledBitmap(bmp, 300, 300, true)
            val thumbFile = File(thumbnailsDir, "tpl_$id.png")
            thumbFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 90, it) }
            thumbFile.absolutePath
        } catch (e: Exception) {
            Timber.tag("TemplateRepo").w(e, "thumbnail failed")
            ""
        }

        val record = TemplateRecord(
            id = id,
            name = name,
            minLength = doc.minLength,
            updatedAt = System.currentTimeMillis(),
            thumbnailPath = thumbPath,
            elements = processedElements,
            landscape = doc.landscape
        )

        // 保存 JSON 索引
        val json = templateRecordToJson(record)
        prefs.edit().putString("tpl_$id", json).apply()

        Timber.tag("TemplateRepo").d("saved template $id: $name")
        id
    }

    /**
     * 加载模板。
     */
    suspend fun loadTemplate(id: String): TemplateRecord? = withContext(Dispatchers.IO) {
        val json = prefs.getString("tpl_$id", null) ?: return@withContext null
        try {
            jsonToTemplateRecord(json)
        } catch (e: Exception) {
            Timber.tag("TemplateRepo").w(e, "parse template failed")
            null
        }
    }

    /**
     * 加载模板为 CanvasDoc。
     */
    suspend fun loadTemplateAsDoc(id: String): Pair<CanvasDoc, TemplateRecord>? = withContext(Dispatchers.IO) {
        val record = loadTemplate(id) ?: return@withContext null
        val doc = CanvasDoc()
        doc.minLength = record.minLength
        doc.landscape = record.landscape
        doc.elements = record.elements.map { templateDataToElement(it) }.toMutableList()
        Pair(doc, record)
    }

    /**
     * 列出所有模板。
     */
    suspend fun listTemplates(): List<TemplateRecord> = withContext(Dispatchers.IO) {
        val templates = mutableListOf<TemplateRecord>()
        prefs.all.forEach { (_, value) ->
            if (value is String) {
                try {
                    templates.add(jsonToTemplateRecord(value))
                } catch (e: Exception) { }
            }
        }
        templates.sortedByDescending { it.updatedAt }
    }

    /**
     * 删除模板。
     */
    suspend fun deleteTemplate(id: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove("tpl_$id").apply()
        File(thumbnailsDir, "tpl_$id.png").delete()
        // 删除模板关联的图片文件
        imagesDir.listFiles()?.filter { it.name.startsWith("tpl_img_") }?.forEach { it.delete() }
    }

    // ── JSON 序列化 ──────────────────────────────────────────

    private fun templateRecordToJson(record: TemplateRecord): String {
        val elementsArr = JSONArray()
        for (el in record.elements) {
            val elObj = JSONObject().apply {
                put("kind", el.kind)
                put("dotX", el.dotX)
                put("dotY", el.dotY)
                put("dotW", el.dotW)
                put("dotH", el.dotH)
                put("aspect", el.aspect.toDouble())
                put("geometryLocked", el.geometryLocked)
                put("text", el.text)
                put("imageUri", el.imageUri)
                put("ditherMode", el.ditherMode)
                put("ditherThreshold", el.ditherThreshold)
                put("codeContent", el.codeContent)
                put("codeTypeIndex", el.codeTypeIndex)
                put("rotation", el.rotation)
                put("flipH", el.flipH)
                put("flipV", el.flipV)
                put("invert", el.invert)
                // textOptions
                val opts = JSONObject().apply {
                    put("fontFamily", el.textOptions.fontFamily)
                    put("fontSize", el.textOptions.fontSize.toDouble())
                    put("bold", el.textOptions.bold)
                    put("italic", el.textOptions.italic)
                    put("underline", el.textOptions.underline)
                    put("letterSpacing", el.textOptions.letterSpacing.toDouble())
                    put("lineSpacing", el.textOptions.lineSpacing.toDouble())
                    put("margin", el.textOptions.margin.toDouble())
                }
                put("textOptions", opts)
            }
            elementsArr.put(elObj)
        }

        return JSONObject().apply {
            put("id", record.id)
            put("name", record.name)
            put("minLength", record.minLength)
            put("updatedAt", record.updatedAt)
            put("thumbnailPath", record.thumbnailPath)
            put("elements", elementsArr)
            put("landscape", record.landscape)
        }.toString()
    }

    private fun jsonToTemplateRecord(json: String): TemplateRecord {
        val obj = JSONObject(json)
        val id = obj.getString("id")
        val name = obj.getString("name")
        val minLength = obj.getInt("minLength")
        val updatedAt = obj.getLong("updatedAt")
        val thumbPath = obj.optString("thumbnailPath", "")

        val elementsArr = obj.getJSONArray("elements")
        val elements = mutableListOf<TemplateElementData>()
        for (i in 0 until elementsArr.length()) {
            val elObj = elementsArr.getJSONObject(i)
            val optsObj = elObj.optJSONObject("textOptions")
            val textOptions = com.qring.printer.protocol.TextRenderOptions(
                fontFamily = optsObj?.optString("fontFamily", "sans-serif") ?: "sans-serif",
                fontSize = optsObj?.optDouble("fontSize", 24.0)?.toFloat() ?: 24f,
                bold = optsObj?.optBoolean("bold", false) ?: false,
                italic = optsObj?.optBoolean("italic", false) ?: false,
                underline = optsObj?.optBoolean("underline", false) ?: false,
                letterSpacing = optsObj?.optDouble("letterSpacing", 0.0)?.toFloat() ?: 0f,
                lineSpacing = optsObj?.optDouble("lineSpacing", 6.0)?.toFloat() ?: 6f,
                margin = optsObj?.optDouble("margin", 8.0)?.toFloat() ?: 8f
            )
            elements.add(
                TemplateElementData(
                    kind = elObj.getString("kind"),
                    dotX = elObj.getInt("dotX"),
                    dotY = elObj.getInt("dotY"),
                    dotW = elObj.getInt("dotW"),
                    dotH = elObj.getInt("dotH"),
                    aspect = elObj.optDouble("aspect", 1.0).toFloat(),
                    geometryLocked = elObj.optBoolean("geometryLocked", false),
                    text = elObj.optString("text", ""),
                    textOptions = textOptions,
                    imageUri = elObj.optString("imageUri", ""),
                    ditherMode = elObj.optInt("ditherMode", 1),
                    ditherThreshold = elObj.optInt("ditherThreshold", 128),
                    codeContent = elObj.optString("codeContent", ""),
                    codeTypeIndex = elObj.optInt("codeTypeIndex", 0),
                    rotation = elObj.optInt("rotation", 0),
                    flipH = elObj.optBoolean("flipH", false),
                    flipV = elObj.optBoolean("flipV", false),
                    invert = elObj.optBoolean("invert", false)
                )
            )
        }

        return TemplateRecord(
            id, name, minLength, updatedAt, thumbPath, elements,
            landscape = obj.optBoolean("landscape", false)
        )
    }
}
