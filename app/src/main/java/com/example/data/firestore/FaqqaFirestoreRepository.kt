package com.example.data.firestore

import android.content.Context
import com.example.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FaqqaFirestoreRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("يجب تسجيل الدخول بحساب Google أولًا.")

    private fun generateSafeId(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(16)}"

    private fun <T> Flow<T>.logFirestoreErrors(
        uid: String,
        op: OperationType,
        path: String
    ): Flow<T> = catch { error ->
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, op, path)
        }
        throw error
    }

    suspend fun ensureUserProfile(displayName: String): Result<UserProfile> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        return try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                val existing = snap.toObject(
                    UserProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                if (existing != null) return Result.success(existing)
            }
            val profile = UserProfile(
                id = uid,
                userId = uid,
                displayName = displayName.ifBlank { "مترجم فقاعة" }.take(80)
            )
            docRef.set(profile.toCreateMap()).await()
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> {
        val path = "users/$userId"
        return db.collection("users").document(userId)
            .snapshots()
            .map { snap ->
                if (snap.exists()) {
                    snap.toObject(
                        UserProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else null
            }
            .logFirestoreErrors(userId, OperationType.GET, path)
    }

    suspend fun updateUserPreferences(
        displayName: String,
        targetLang: String,
        preferredStyle: String,
        preferredProvider: String
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        return try {
            docRef.update(
                mapOf(
                    "displayName" to displayName.take(80),
                    "preferredTargetLang" to targetLang.take(16),
                    "preferredStyle" to preferredStyle,
                    "preferredProvider" to preferredProvider.take(40),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeProjects(userId: String): Flow<List<ComicProject>> {
        val path = "users/$userId/projects"
        return db.collection("users").document(userId).collection("projects")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snap ->
                snap.toObjects(
                    ComicProject::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .logFirestoreErrors(userId, OperationType.LIST, path)
    }

    suspend fun createProject(project: ComicProject): Result<ComicProject> {
        val uid = requireUserId()
        val docId = project.id.ifBlank { generateSafeId("proj") }
        val docRef = db.collection("users").document(uid).collection("projects").document(docId)
        val payload = project.copy(id = docId, userId = uid)
        return try {
            docRef.set(payload.toCreateMap()).await()
            Result.success(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateProject(project: ComicProject): Result<Unit> {
        val uid = requireUserId()
        require(project.id.isNotBlank()) { "Project ID is required" }
        val docRef = db.collection("users").document(uid)
            .collection("projects").document(project.id)
        return try {
            docRef.update(
                mapOf(
                    "title" to project.title.trim().take(120),
                    "sourceLang" to project.sourceLang.take(16),
                    "targetLang" to project.targetLang.take(16),
                    "translationStyle" to project.translationStyle,
                    "readingDirection" to project.readingDirection,
                    "readerMode" to project.readerMode,
                    "sfxMode" to project.sfxMode,
                    "cleanBackgroundText" to project.cleanBackgroundText,
                    "pageCount" to project.pageCount.coerceIn(0L, 5000L),
                    "contextNotes" to project.contextNotes.take(2000),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteProject(projectId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("projects").document(projectId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeChapters(userId: String, projectId: String): Flow<List<ChapterRecord>> {
        val path = "users/$userId/projects/$projectId/chapters"
        return db.collection("users").document(userId)
            .collection("projects").document(projectId)
            .collection("chapters")
            .orderBy("chapterNumber", Query.Direction.ASCENDING)
            .snapshots()
            .map { snap ->
                snap.toObjects(
                    ChapterRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .logFirestoreErrors(userId, OperationType.LIST, path)
    }

    suspend fun createChapter(chapter: ChapterRecord): Result<ChapterRecord> {
        val uid = requireUserId()
        require(chapter.projectId.isNotBlank()) { "Project ID is required for chapter" }
        val docId = chapter.id.ifBlank { generateSafeId("chap") }
        val docRef = db.collection("users").document(uid)
            .collection("projects").document(chapter.projectId)
            .collection("chapters").document(docId)
        val payload = chapter.copy(id = docId, userId = uid)
        return try {
            docRef.set(payload.toCreateMap()).await()
            Result.success(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateChapterProgress(
        projectId: String,
        chapterId: String,
        pageCount: Long,
        lastReadPageIndex: Long,
        isOfflineReady: Boolean
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("projects").document(projectId)
            .collection("chapters").document(chapterId)
        return try {
            docRef.update(
                mapOf(
                    "pageCount" to pageCount.coerceIn(0L, 1000L),
                    "lastReadPageIndex" to lastReadPageIndex.coerceIn(0L, 1000L),
                    "isOfflineReady" to isOfflineReady,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeGlossary(userId: String, projectId: String): Flow<List<GlossaryEntry>> {
        val path = "users/$userId/projects/$projectId/glossary"
        return db.collection("users").document(userId)
            .collection("projects").document(projectId)
            .collection("glossary")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snap ->
                snap.toObjects(
                    GlossaryEntry::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .logFirestoreErrors(userId, OperationType.LIST, path)
    }

    suspend fun addGlossaryEntry(entry: GlossaryEntry): Result<GlossaryEntry> {
        val uid = requireUserId()
        require(entry.projectId.isNotBlank()) { "Project ID is required for glossary entry" }
        val docId = entry.id.ifBlank { generateSafeId("glos") }
        val docRef = db.collection("users").document(uid)
            .collection("projects").document(entry.projectId)
            .collection("glossary").document(docId)
        val payload = entry.copy(id = docId, userId = uid)
        return try {
            docRef.set(payload.toCreateMap()).await()
            Result.success(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteGlossaryEntry(projectId: String, entryId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("projects").document(projectId)
            .collection("glossary").document(entryId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeTranslationJobs(userId: String): Flow<List<TranslationJobRecord>> {
        val path = "users/$userId/translationJobs"
        return db.collection("users").document(userId)
            .collection("translationJobs")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .snapshots()
            .map { snap ->
                snap.toObjects(
                    TranslationJobRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .logFirestoreErrors(userId, OperationType.LIST, path)
    }

    suspend fun createTranslationJob(job: TranslationJobRecord): Result<TranslationJobRecord> {
        val uid = requireUserId()
        val docId = job.id.ifBlank { generateSafeId("job") }
        val docRef = db.collection("users").document(uid)
            .collection("translationJobs").document(docId)
        val payload = job.copy(id = docId, userId = uid)
        return try {
            docRef.set(payload.toCreateMap()).await()
            Result.success(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateTranslationJob(
        jobId: String,
        status: String,
        processedPages: Long,
        totalPages: Long,
        errorMessage: String = ""
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("translationJobs").document(jobId)
        return try {
            docRef.update(
                mapOf(
                    "status" to status,
                    "processedPages" to processedPages.coerceIn(0L, 1000L),
                    "totalPages" to totalPages.coerceIn(0L, 1000L),
                    "errorMessage" to errorMessage.take(500),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun logSourceAdapterAttempt(log: SourceAdapterLogRecord): Result<Unit> {
        val uid = requireUserId()
        val docId = log.id.ifBlank { generateSafeId("slog") }
        val docRef = db.collection("users").document(uid)
            .collection("sourceLogs").document(docId)
        val payload = log.copy(id = docId, userId = uid)
        return try {
            docRef.set(payload.toCreateMap()).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeSourceLogs(userId: String): Flow<List<SourceAdapterLogRecord>> {
        val path = "users/$userId/sourceLogs"
        return db.collection("users").document(userId)
            .collection("sourceLogs")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(30)
            .snapshots()
            .map { snap ->
                snap.toObjects(
                    SourceAdapterLogRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .logFirestoreErrors(userId, OperationType.LIST, path)
    }
}
