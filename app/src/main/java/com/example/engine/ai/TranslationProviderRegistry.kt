package com.example.engine.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.firestore.GlossaryEntry
import com.example.data.firestore.TranslationStyle
import com.example.data.local.LocalProviderConfigEntity
import com.example.security.CryptoKeyStore
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DetectedBubbleAnalysis(
    val orderIndex: Int,
    val regionType: String, // speech, narration, sfx, background_text
    val leftNorm: Float,
    val topNorm: Float,
    val rightNorm: Float,
    val bottomNorm: Float,
    val sourceText: String,
    val detectedLang: String,
    val translatedText: String,
    val confidence: Float
)

data class TranslationInputItem(
    val regionId: String,
    val sourceText: String,
    val regionType: String
)

data class TranslationOutputItem(
    val regionId: String,
    val translatedText: String
)

object ProviderCatalog {
    fun defaultProviders(): List<LocalProviderConfigEntity> = listOf(
        LocalProviderConfigEntity(
            providerId = "firebase_ai",
            displayName = "Google Gemini (Firebase AI Logic)",
            baseUrl = "firebase://ai-logic",
            modelName = "gemini-3.8-flash",
            requiresApiKey = false,
            isSelected = true,
            lastTestStatus = "ready",
            lastTestMessage = "متصل عبر Firebase AI Logic + App Check"
        ),
        LocalProviderConfigEntity(
            providerId = "deepseek",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            modelName = "deepseek-chat",
            requiresApiKey = true
        ),
        LocalProviderConfigEntity(
            providerId = "groq",
            displayName = "Groq (Llama 3.3 70B)",
            baseUrl = "https://api.groq.com/openai/v1",
            modelName = "llama-3.3-70b-versatile",
            requiresApiKey = true
        ),
        LocalProviderConfigEntity(
            providerId = "gemini_direct",
            displayName = "Google Gemini (مفتاح شخصي)",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            modelName = "gemini-3.8-flash",
            requiresApiKey = true
        ),
        LocalProviderConfigEntity(
            providerId = "ollama",
            displayName = "Ollama (محلي بلا إنترنت)",
            baseUrl = "http://127.0.0.1:11434",
            modelName = "qwen2.5",
            requiresApiKey = false
        ),
        LocalProviderConfigEntity(
            providerId = "openai",
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            modelName = "gpt-4o-mini",
            requiresApiKey = true
        ),
        LocalProviderConfigEntity(
            providerId = "anthropic",
            displayName = "Anthropic Claude",
            baseUrl = "https://api.anthropic.com/v1",
            modelName = "claude-3-5-haiku-latest",
            requiresApiKey = true
        )
    )
}

interface TranslationProvider {
    val providerId: String
    suspend fun testConnection(config: LocalProviderConfigEntity): Result<String>
    suspend fun translateBatch(
        items: List<TranslationInputItem>,
        sourceLang: String,
        targetLang: String,
        style: TranslationStyle,
        glossary: List<GlossaryEntry>,
        projectContext: String,
        config: LocalProviderConfigEntity
    ): Result<List<TranslationOutputItem>>
}

class TranslationProviderRegistry {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val visionSchema = Schema.obj(
        properties = mapOf(
            "pageLanguage" to Schema.string(description = "Detected primary language code like JA, KO, ZH, EN, FR, ES"),
            "regions" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "orderIndex" to Schema.integer(description = "Reading order starting from 0"),
                        "regionType" to Schema.enumeration(
                            values = listOf("speech", "narration", "sfx", "background_text"),
                            description = "Comic region type"
                        ),
                        "xmin" to Schema.integer(description = "Left coordinate 0..1000"),
                        "ymin" to Schema.integer(description = "Top coordinate 0..1000"),
                        "xmax" to Schema.integer(description = "Right coordinate 0..1000"),
                        "ymax" to Schema.integer(description = "Bottom coordinate 0..1000"),
                        "sourceText" to Schema.string(description = "Exact OCR text inside the bubble or box"),
                        "translatedText" to Schema.string(description = "Natural Arabic translation following style & glossary"),
                        "confidence" to Schema.double(description = "Confidence 0.0 to 1.0")
                    )
                ),
                description = "Detected comic speech bubbles, narration boxes, and SFX in reading order"
            )
        )
    )

    private val visionModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = visionSchema
                temperature = 0.2f
            }
        )
    }

    private val textSchema = Schema.obj(
        properties = mapOf(
            "translations" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "regionId" to Schema.string(),
                        "translatedText" to Schema.string()
                    )
                )
            )
        )
    )

    private val firebaseTextModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = textSchema
                temperature = 0.3f
            }
        )
    }

    private val firebaseTestModel by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    suspend fun analyzeAndTranslatePageMultimodal(
        bitmap: Bitmap,
        readingDirection: String,
        targetLang: String,
        style: TranslationStyle,
        glossary: List<GlossaryEntry>,
        projectContext: String
    ): Result<List<DetectedBubbleAnalysis>> = withContext(Dispatchers.IO) {
        try {
            val scaledBitmap = scaleBitmapForVision(bitmap, maxSide = 1600)
            val glossaryText = buildGlossaryPrompt(glossary)
            val orderRule = if (readingDirection == "rtl") {
                "Sort regions in Manga RTL reading order (top-to-bottom panels, right-to-left within each row)."
            } else {
                "Sort regions in Western/Webtoon LTR reading order (top-to-bottom, left-to-right)."
            }
            val prompt = """
                You are an expert manga/manhwa/comic OCR and Arabic localization engine.
                Inspect this comic page carefully.
                1. Detect every dialogue speech bubble ("speech"), narration caption box ("narration"), sound effect ("sfx"), and important background text ("background_text").
                2. Coordinates xmin, ymin, xmax, ymax MUST be normalized integers 0..1000 tightly wrapping the text inside each bubble.
                3. $orderRule
                4. Extract exact original OCR text in "sourceText".
                5. Translate into $targetLang (Arabic) using style: ${style.arabicLabel} (${style.wireValue}).
                6. Keep dialogue concise so it fits cleanly inside comic speech bubbles.
                Project context: ${projectContext.ifBlank { "General comic dialogue" }}
                $glossaryText
            """.trimIndent()

            val response = withTimeout(60_000L) {
                visionModel.generateContent(
                    content {
                        image(scaledBitmap)
                        text(prompt)
                    }
                )
            }
            val raw = response.text
                ?: return@withContext Result.failure(IllegalStateException("أعاد نموذج الرؤية استجابة فارغة."))

            val json = JSONObject(raw)
            val regionsArray = json.optJSONArray("regions") ?: JSONArray()
            val pageLang = json.optString("pageLanguage", "EN")
            val result = mutableListOf<DetectedBubbleAnalysis>()
            for (i in 0 until regionsArray.length()) {
                val obj = regionsArray.getJSONObject(i)
                val xmin = (obj.optDouble("xmin", 0.0) / 1000.0).toFloat().coerceIn(0f, 0.98f)
                val ymin = (obj.optDouble("ymin", 0.0) / 1000.0).toFloat().coerceIn(0f, 0.98f)
                val xmax = (obj.optDouble("xmax", 100.0) / 1000.0).toFloat().coerceIn(xmin + 0.02f, 1f)
                val ymax = (obj.optDouble("ymax", 100.0) / 1000.0).toFloat().coerceIn(ymin + 0.02f, 1f)
                val src = obj.optString("sourceText", "").trim()
                val dst = obj.optString("translatedText", "").trim()
                if (src.isNotBlank() || dst.isNotBlank()) {
                    result.add(
                        DetectedBubbleAnalysis(
                            orderIndex = obj.optInt("orderIndex", i),
                            regionType = obj.optString("regionType", "speech"),
                            leftNorm = xmin,
                            topNorm = ymin,
                            rightNorm = xmax,
                            bottomNorm = ymax,
                            sourceText = src,
                            detectedLang = pageLang,
                            translatedText = dst,
                            confidence = obj.optDouble("confidence", 0.9).toFloat().coerceIn(0f, 1f)
                        )
                    )
                }
            }
            Result.success(result.sortedBy { it.orderIndex })
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Multimodal vision timed out", e)
            Result.failure(IllegalStateException("انتهت مهلة تحليل الصفحة بالرؤية الذكية (60 ثانية)."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Multimodal vision failed", e)
            Result.failure(IllegalStateException("فشل تحليل الصفحة عبر Firebase AI Logic: ${e.localizedMessage}"))
        }
    }

    suspend fun testConnection(config: LocalProviderConfigEntity): Result<String> = withContext(Dispatchers.IO) {
        try {
            when (config.providerId) {
                "firebase_ai" -> {
                    val res = withTimeout(30_000L) {
                        firebaseTestModel.generateContent("Reply with strictly: OK")
                    }
                    val txt = res.text?.trim().orEmpty()
                    if (txt.isNotBlank()) {
                        Result.success("متصل بنجاح (${config.modelName}): $txt")
                    } else {
                        Result.failure(IllegalStateException("استجابة فارغة من Firebase AI Logic"))
                    }
                }
                "ollama" -> {
                    val base = config.baseUrl.trimEnd('/')
                    val req = Request.Builder().url("$base/api/tags").get().build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(
                                IllegalStateException("Ollama HTTP ${resp.code}: تأكد من تشغيل ollama serve")
                            )
                        }
                        val body = resp.body?.string().orEmpty()
                        Result.success("متصل بـ Ollama المحلي (${body.take(80)})")
                    }
                }
                "gemini_direct" -> {
                    val key = CryptoKeyStore.decrypt(config.encryptedApiKey)
                    if (key.isBlank()) {
                        return@withContext Result.failure(IllegalStateException("لم يتم ضبط مفتاح API لهذا المزود."))
                    }
                    val url = "${config.baseUrl.trimEnd('/')}/models/${config.modelName}:generateContent?key=$key"
                    val jsonBody = JSONObject().apply {
                        put("contents", JSONArray().put(JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().put("text", "Reply OK")))
                        }))
                    }.toString()
                    val req = Request.Builder()
                        .url(url)
                        .post(jsonBody.toRequestBody(JSON_MEDIA))
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(IllegalStateException("HTTP ${resp.code}: ${bodyStr.take(160)}"))
                        }
                        Result.success("اتصال Gemini Direct ناجح (HTTP ${resp.code})")
                    }
                }
                "anthropic" -> {
                    val key = CryptoKeyStore.decrypt(config.encryptedApiKey)
                    if (key.isBlank()) {
                        return@withContext Result.failure(IllegalStateException("لم يتم ضبط مفتاح API لـ Anthropic."))
                    }
                    val jsonBody = JSONObject().apply {
                        put("model", config.modelName)
                        put("max_tokens", 16)
                        put("messages", JSONArray().put(JSONObject().apply {
                            put("role", "user")
                            put("content", "Reply OK")
                        }))
                    }.toString()
                    val req = Request.Builder()
                        .url("${config.baseUrl.trimEnd('/')}/messages")
                        .addHeader("x-api-key", key)
                        .addHeader("anthropic-version", "2023-06-01")
                        .post(jsonBody.toRequestBody(JSON_MEDIA))
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(IllegalStateException("HTTP ${resp.code}: ${bodyStr.take(160)}"))
                        }
                        Result.success("اتصال Anthropic ناجح (HTTP ${resp.code})")
                    }
                }
                else -> {
                    // OpenAI-compatible: DeepSeek, Groq, OpenAI
                    val key = CryptoKeyStore.decrypt(config.encryptedApiKey)
                    if (key.isBlank()) {
                        return@withContext Result.failure(IllegalStateException("لم يتم ضبط مفتاح API لـ ${config.displayName}."))
                    }
                    val req = Request.Builder()
                        .url("${config.baseUrl.trimEnd('/')}/models")
                        .addHeader("Authorization", "Bearer $key")
                        .get()
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(IllegalStateException("HTTP ${resp.code}: ${bodyStr.take(160)}"))
                        }
                        Result.success("اتصال ${config.displayName} ناجح (HTTP ${resp.code})")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "testConnection failed for ${config.providerId}", e)
            Result.failure(IllegalStateException("فشل الاتصال: ${e.localizedMessage}"))
        }
    }

    suspend fun translateBatch(
        items: List<TranslationInputItem>,
        sourceLang: String,
        targetLang: String,
        style: TranslationStyle,
        glossary: List<GlossaryEntry>,
        projectContext: String,
        config: LocalProviderConfigEntity
    ): Result<List<TranslationOutputItem>> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result.success(emptyList())
        try {
            val prompt = buildBatchTranslationPrompt(
                items = items,
                sourceLang = sourceLang,
                targetLang = targetLang,
                style = style,
                glossary = glossary,
                projectContext = projectContext
            )

            when (config.providerId) {
                "firebase_ai" -> {
                    val response = withTimeout(60_000L) {
                        firebaseTextModel.generateContent(prompt)
                    }
                    val jsonStr = response.text
                        ?: return@withContext Result.failure(IllegalStateException("استجابة فارغة من Firebase AI"))
                    parseBatchJson(jsonStr, items)
                }
                "ollama" -> {
                    val url = "${config.baseUrl.trimEnd('/')}/api/chat"
                    val payload = JSONObject().apply {
                        put("model", config.modelName)
                        put("stream", false)
                        put("format", "json")
                        put("messages", JSONArray().put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        }))
                    }.toString()
                    val req = Request.Builder().url(url).post(payload.toRequestBody(JSON_MEDIA)).build()
                    httpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(IllegalStateException("Ollama HTTP ${resp.code}: ${body.take(180)}"))
                        }
                        val contentText = JSONObject(body).optJSONObject("message")?.optString("content").orEmpty()
                        parseBatchJson(extractJsonObject(contentText), items)
                    }
                }
                "gemini_direct" -> {
                    val key = CryptoKeyStore.decrypt(config.encryptedApiKey)
                    if (key.isBlank()) {
                        return@withContext Result.failure(IllegalStateException("مفتاح Gemini الشخصي غير مضبوط."))
                    }
                    val url = "${config.baseUrl.trimEnd('/')}/models/${config.modelName}:generateContent?key=$key"
                    val payload = JSONObject().apply {
                        put("contents", JSONArray().put(JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                        }))
                        put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                    }.toString()
                    val req = Request.Builder().url(url).post(payload.toRequestBody(JSON_MEDIA)).build()
                    httpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(IllegalStateException("Gemini HTTP ${resp.code}: ${body.take(180)}"))
                        }
                        val text = JSONObject(body)
                            .getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts")
                            .getJSONObject(0).getString("text")
                        parseBatchJson(extractJsonObject(text), items)
                    }
                }
                "anthropic" -> {
                    val key = CryptoKeyStore.decrypt(config.encryptedApiKey)
                    if (key.isBlank()) {
                        return@withContext Result.failure(IllegalStateException("مفتاح Anthropic غير مضبوط."))
                    }
                    val payload = JSONObject().apply {
                        put("model", config.modelName)
                        put("max_tokens", 2048)
                        put("messages", JSONArray().put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        }))
                    }.toString()
                    val req = Request.Builder()
                        .url("${config.baseUrl.trimEnd('/')}/messages")
                        .addHeader("x-api-key", key)
                        .addHeader("anthropic-version", "2023-06-01")
                        .post(payload.toRequestBody(JSON_MEDIA))
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(IllegalStateException("Anthropic HTTP ${resp.code}: ${body.take(180)}"))
                        }
                        val text = JSONObject(body).getJSONArray("content").getJSONObject(0).getString("text")
                        parseBatchJson(extractJsonObject(text), items)
                    }
                }
                else -> {
                    // DeepSeek, Groq, OpenAI
                    val key = CryptoKeyStore.decrypt(config.encryptedApiKey)
                    if (key.isBlank()) {
                        return@withContext Result.failure(
                            IllegalStateException("مفتاح API غير مضبوط لمزود ${config.displayName}. أضف المفتاح في إعدادات AI.")
                        )
                    }
                    val payload = JSONObject().apply {
                        put("model", config.modelName)
                        put("temperature", 0.3)
                        put("response_format", JSONObject().put("type", "json_object"))
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "system")
                                put("content", "You are a professional manga & comic translator into Arabic. Return strict JSON only.")
                            })
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", prompt)
                            })
                        })
                    }.toString()
                    val req = Request.Builder()
                        .url("${config.baseUrl.trimEnd('/')}/chat/completions")
                        .addHeader("Authorization", "Bearer $key")
                        .post(payload.toRequestBody(JSON_MEDIA))
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(
                                IllegalStateException("${config.displayName} HTTP ${resp.code}: ${body.take(180)}")
                            )
                        }
                        val text = JSONObject(body)
                            .getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content")
                        parseBatchJson(extractJsonObject(text), items)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("انتهت مهلة الترجمة (60 ثانية)."))
        } catch (e: Exception) {
            Log.e(TAG, "translateBatch failed", e)
            Result.failure(IllegalStateException("فشل طلب الترجمة: ${e.localizedMessage}"))
        }
    }

    private fun buildGlossaryPrompt(glossary: List<GlossaryEntry>): String {
        if (glossary.isEmpty()) return ""
        val lines = glossary.take(60).joinToString("\n") {
            "- ${it.sourceTerm} => ${it.targetTerm} [${it.category}] ${if (it.notes.isNotBlank()) "(${it.notes})" else ""}"
        }
        return "\nMandatory Glossary:\n$lines"
    }

    private fun buildBatchTranslationPrompt(
        items: List<TranslationInputItem>,
        sourceLang: String,
        targetLang: String,
        style: TranslationStyle,
        glossary: List<GlossaryEntry>,
        projectContext: String
    ): String {
        val itemsArray = JSONArray()
        items.forEach { item ->
            itemsArray.put(JSONObject().apply {
                put("regionId", item.regionId)
                put("regionType", item.regionType)
                put("sourceText", item.sourceText)
            })
        }
        return """
            Translate each comic text region from $sourceLang to $targetLang (Arabic).
            Style: ${style.arabicLabel} (${style.wireValue}).
            Keep dialogue natural, punchy, and compact for comic speech bubbles.
            Project context: ${projectContext.ifBlank { "None" }}
            ${buildGlossaryPrompt(glossary)}

            Input JSON:
            $itemsArray

            Return strictly valid JSON with schema:
            { "translations": [ { "regionId": "...", "translatedText": "..." } ] }
        """.trimIndent()
    }

    private fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val first = trimmed.indexOf('{')
        val last = trimmed.lastIndexOf('}')
        return if (first in 0 until last) trimmed.substring(first, last + 1) else trimmed
    }

    private fun parseBatchJson(
        jsonStr: String,
        fallbackItems: List<TranslationInputItem>
    ): Result<List<TranslationOutputItem>> {
        val obj = JSONObject(jsonStr)
        val arr = obj.optJSONArray("translations") ?: JSONArray()
        val map = mutableMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val id = item.optString("regionId")
            val tr = item.optString("translatedText")
            if (id.isNotBlank() && tr.isNotBlank()) {
                map[id] = tr
            }
        }
        val outputs = fallbackItems.mapNotNull { input ->
            map[input.regionId]?.let { TranslationOutputItem(input.regionId, it) }
        }
        return if (outputs.isEmpty()) {
            Result.failure(IllegalStateException("لم يُعد المزود أي نصوص مترجمة صالحة."))
        } else {
            Result.success(outputs)
        }
    }

    private fun scaleBitmapForVision(bitmap: Bitmap, maxSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSide && h <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(
            bitmap,
            (w * ratio).toInt().coerceAtLeast(1),
            (h * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private companion object {
        const val TAG = "TranslationProviders"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
