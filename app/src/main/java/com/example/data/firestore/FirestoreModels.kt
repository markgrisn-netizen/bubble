package com.example.data.firestore

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(exception: Throwable, operationType: OperationType, path: String?): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val auth = try {
        FirebaseAuth.getInstance()
    } catch (_: Exception) {
        null
    }
    val currentUser = auth?.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

enum class TranslationStyle(val wireValue: String, val arabicLabel: String) {
    NATURAL("natural", "احترافي طبيعي"),
    FAITHFUL("faithful", "أمين للنص"),
    CINEMATIC("cinematic", "سينمائي درامي"),
    LITERARY("literary", "أدبي"),
    SIMPLIFIED("simplified", "مبسط"),
    LITERAL("literal", "حرفي للمقارنة"),
    CHARACTER_VOICE("character_voice", "محافظ على نبرة الشخصيات");

    companion object {
        fun fromWire(value: String): TranslationStyle =
            entries.firstOrNull { it.wireValue == value } ?: NATURAL
    }
}

enum class SfxMode(val wireValue: String, val arabicLabel: String) {
    KEEP("keep", "الاحتفاظ بالأصل"),
    SIDE_NOTE("side_note", "ترجمة جانبية"),
    VISUAL_REPLACE("visual_replace", "استبدال مرئي"),
    ARTISTIC("artistic", "نص فني"),
    IGNORE("ignore", "تجاهل المؤثر");

    companion object {
        fun fromWire(value: String): SfxMode =
            entries.firstOrNull { it.wireValue == value } ?: SIDE_NOTE
    }
}

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val preferredTargetLang: String = "AR",
    val preferredStyle: String = TranslationStyle.NATURAL.wireValue,
    val preferredProvider: String = "firebase_ai",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "displayName" to displayName.take(80),
        "preferredTargetLang" to preferredTargetLang.take(16),
        "preferredStyle" to preferredStyle,
        "preferredProvider" to preferredProvider.take(40),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@IgnoreExtraProperties
data class ComicProject(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val sourceLang: String = "AUTO",
    val targetLang: String = "AR",
    val translationStyle: String = TranslationStyle.NATURAL.wireValue,
    val readingDirection: String = "rtl",
    val readerMode: String = "vertical_webtoon",
    val sfxMode: String = SfxMode.SIDE_NOTE.wireValue,
    val cleanBackgroundText: Boolean = false,
    val pageCount: Long = 0L,
    val contextNotes: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "title" to title.trim().take(120).ifEmpty { "مشروع جديد" },
        "sourceLang" to sourceLang.take(16).ifEmpty { "AUTO" },
        "targetLang" to targetLang.take(16).ifEmpty { "AR" },
        "translationStyle" to translationStyle,
        "readingDirection" to readingDirection,
        "readerMode" to readerMode,
        "sfxMode" to sfxMode,
        "cleanBackgroundText" to cleanBackgroundText,
        "pageCount" to pageCount.coerceIn(0L, 5000L),
        "contextNotes" to contextNotes.take(2000),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@IgnoreExtraProperties
data class ChapterRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val title: String = "",
    val chapterNumber: Long = 1L,
    val importSource: String = "file_import",
    val sourceUrl: String = "",
    val pageCount: Long = 0L,
    val lastReadPageIndex: Long = 0L,
    val isOfflineReady: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "projectId" to projectId,
        "title" to title.trim().take(120).ifEmpty { "الفصل ١" },
        "chapterNumber" to chapterNumber.coerceIn(0L, 100000L),
        "importSource" to importSource,
        "sourceUrl" to sourceUrl.take(1000),
        "pageCount" to pageCount.coerceIn(0L, 1000L),
        "lastReadPageIndex" to lastReadPageIndex.coerceIn(0L, 1000L),
        "isOfflineReady" to isOfflineReady,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@IgnoreExtraProperties
data class GlossaryEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val sourceTerm: String = "",
    val targetTerm: String = "",
    val category: String = "term",
    val notes: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "projectId" to projectId,
        "sourceTerm" to sourceTerm.trim().take(160),
        "targetTerm" to targetTerm.trim().take(160),
        "category" to category,
        "notes" to notes.take(500),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@IgnoreExtraProperties
data class TranslationJobRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val chapterId: String = "",
    val providerId: String = "firebase_ai",
    val status: String = "queued",
    val processedPages: Long = 0L,
    val totalPages: Long = 0L,
    val errorMessage: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "projectId" to projectId,
        "chapterId" to chapterId,
        "providerId" to providerId.take(64),
        "status" to status,
        "processedPages" to processedPages.coerceIn(0L, 1000L),
        "totalPages" to totalPages.coerceIn(0L, 1000L),
        "errorMessage" to errorMessage.take(500),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@IgnoreExtraProperties
data class SourceAdapterLogRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val url: String = "",
    val adapterName: String = "",
    val status: String = "success",
    val httpCode: Long = 200L,
    val extractedCount: Long = 0L,
    val message: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "url" to url.take(1000),
        "adapterName" to adapterName.take(100),
        "status" to status,
        "httpCode" to httpCode.coerceIn(0L, 999L),
        "extractedCount" to extractedCount.coerceIn(0L, 5000L),
        "message" to message.take(500),
        "createdAt" to FieldValue.serverTimestamp()
    )
}
