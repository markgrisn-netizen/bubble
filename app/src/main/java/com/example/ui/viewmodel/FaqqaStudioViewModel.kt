package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.firestore.ChapterRecord
import com.example.data.firestore.ComicProject
import com.example.data.firestore.FaqqaFirestoreRepository
import com.example.data.firestore.GlossaryEntry
import com.example.data.firestore.SfxMode
import com.example.data.firestore.SourceAdapterLogRecord
import com.example.data.firestore.TranslationJobRecord
import com.example.data.firestore.TranslationStyle
import com.example.data.firestore.UserProfile
import com.example.data.local.BrowserHistoryDao
import com.example.data.local.FaqqaLocalDatabase
import com.example.data.local.LocalBrowserHistoryEntity
import com.example.data.local.LocalPageEntity
import com.example.data.local.LocalProviderConfigEntity
import com.example.data.local.LocalTextRegionEntity
import com.example.data.local.PageDao
import com.example.data.local.ProviderConfigDao
import com.example.data.local.TextRegionDao
import com.example.engine.ai.ProviderCatalog
import com.example.engine.ai.TranslationInputItem
import com.example.engine.ai.TranslationProviderRegistry
import com.example.engine.export.ExportEngine
import com.example.engine.export.ExportFormat
import com.example.engine.importpath.DirectImportResult
import com.example.engine.importpath.FileImportEngine
import com.example.engine.importpath.ImportedImageFile
import com.example.engine.importpath.SourceAdapterRegistry
import com.example.engine.vision.BubbleImageEngine
import com.example.security.CryptoKeyStore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface UiNotice {
    data object Idle : UiNotice
    data class Loading(val message: String) : UiNotice
    data class Success(val message: String) : UiNotice
    data class Error(val code: String, val message: String, val suggestPathB: Boolean = false) : UiNotice
}

@OptIn(ExperimentalCoroutinesApi::class)
class FaqqaStudioViewModel(
    private val appContext: Context,
    private val currentUserId: String,
    private val displayName: String,
    private val firestoreRepo: FaqqaFirestoreRepository,
    localDb: FaqqaLocalDatabase
) : ViewModel() {

    private val pageDao: PageDao = localDb.pageDao()
    private val regionDao: TextRegionDao = localDb.textRegionDao()
    private val providerDao: ProviderConfigDao = localDb.providerConfigDao()
    private val browserHistoryDao: BrowserHistoryDao = localDb.browserHistoryDao()

    private val sourceAdapterRegistry = SourceAdapterRegistry()
    private val providerRegistry = TranslationProviderRegistry()

    private val _selectedProjectId = MutableStateFlow<String?>(null)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()

    private val _selectedChapterId = MutableStateFlow<String?>(null)
    val selectedChapterId: StateFlow<String?> = _selectedChapterId.asStateFlow()

    private val _selectedPageId = MutableStateFlow<String?>(null)
    val selectedPageId: StateFlow<String?> = _selectedPageId.asStateFlow()

    private val _receivedBrowserImagesCount = MutableStateFlow(0)
    val receivedBrowserImagesCount: StateFlow<Int> = _receivedBrowserImagesCount.asStateFlow()

    private val _browserStagedImages = MutableStateFlow<List<ImportedImageFile>>(emptyList())
    val browserStagedImages: StateFlow<List<ImportedImageFile>> = _browserStagedImages.asStateFlow()

    private val _uiNotice = MutableStateFlow<UiNotice>(UiNotice.Idle)
    val uiNotice: StateFlow<UiNotice> = _uiNotice.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _isArabicUi = MutableStateFlow(true)
    val isArabicUi: StateFlow<Boolean> = _isArabicUi.asStateFlow()

    // Undo / Redo stacks per active page editing session
    private val undoStack = ArrayDeque<List<LocalTextRegionEntity>>()
    private val redoStack = ArrayDeque<List<LocalTextRegionEntity>>()

    val userProfile: StateFlow<UserProfile?> = firestoreRepo.observeUserProfile(currentUserId)
        .catch { err ->
            if (err is CancellationException && err.cause !is FirebaseFirestoreException) throw err
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val projects: StateFlow<List<ComicProject>> = firestoreRepo.observeProjects(currentUserId)
        .catch { err ->
            if (err is CancellationException && err.cause !is FirebaseFirestoreException) throw err
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val activeProject: StateFlow<ComicProject?> = kotlinx.coroutines.flow.combine(
        projects,
        _selectedProjectId
    ) { list, selId ->
        list.firstOrNull { it.id == selId } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val chapters: StateFlow<List<ChapterRecord>> = activeProject
        .flatMapLatest { proj ->
            if (proj == null) flowOf(emptyList())
            else firestoreRepo.observeChapters(currentUserId, proj.id)
                .catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val activeChapter: StateFlow<ChapterRecord?> = kotlinx.coroutines.flow.combine(
        chapters,
        _selectedChapterId
    ) { list, selId ->
        list.firstOrNull { it.id == selId } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val pages: StateFlow<List<LocalPageEntity>> = activeChapter
        .flatMapLatest { chap ->
            if (chap == null) flowOf(emptyList())
            else pageDao.observePagesForChapter(chap.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val activePage: StateFlow<LocalPageEntity?> = kotlinx.coroutines.flow.combine(
        pages,
        _selectedPageId
    ) { list, selId ->
        list.firstOrNull { it.id == selId } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val activePageRegions: StateFlow<List<LocalTextRegionEntity>> = activePage
        .flatMapLatest { page ->
            if (page == null) flowOf(emptyList())
            else regionDao.observeRegionsForPage(page.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val glossary: StateFlow<List<GlossaryEntry>> = activeProject
        .flatMapLatest { proj ->
            if (proj == null) flowOf(emptyList())
            else firestoreRepo.observeGlossary(currentUserId, proj.id)
                .catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val translationJobs: StateFlow<List<TranslationJobRecord>> =
        firestoreRepo.observeTranslationJobs(currentUserId)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val sourceLogs: StateFlow<List<SourceAdapterLogRecord>> =
        firestoreRepo.observeSourceLogs(currentUserId)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val providerConfigs: StateFlow<List<LocalProviderConfigEntity>> = providerDao.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val browserHistory: StateFlow<List<LocalBrowserHistoryEntity>> = browserHistoryDao.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    init {
        viewModelScope.launch {
            firestoreRepo.ensureUserProfile(displayName)
            val existingProviders = providerDao.getProvidersOnce()
            if (existingProviders.isEmpty()) {
                providerDao.upsertAll(ProviderCatalog.defaultProviders())
            }
        }
    }

    fun toggleTheme() { _isDarkTheme.value = !_isDarkTheme.value }
    fun toggleUiLanguage() { _isArabicUi.value = !_isArabicUi.value }
    fun clearNotice() { _uiNotice.value = UiNotice.Idle }

    fun selectProject(projectId: String) {
        _selectedProjectId.value = projectId
        _selectedChapterId.value = null
        _selectedPageId.value = null
    }

    fun selectChapter(chapterId: String) {
        _selectedChapterId.value = chapterId
        _selectedPageId.value = null
    }

    fun selectPage(pageId: String) {
        _selectedPageId.value = pageId
        undoStack.clear()
        redoStack.clear()
    }

    fun createProject(
        title: String,
        sourceLang: String,
        style: TranslationStyle,
        readingDirection: String,
        readerMode: String,
        sfxMode: SfxMode,
        contextNotes: String,
        onCreated: (ComicProject) -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiNotice.value = UiNotice.Loading("جارٍ إنشاء المشروع...")
            val project = ComicProject(
                title = title.ifBlank { "مشروع مانغا جديد" },
                sourceLang = sourceLang,
                targetLang = "AR",
                translationStyle = style.wireValue,
                readingDirection = readingDirection,
                readerMode = readerMode,
                sfxMode = sfxMode.wireValue,
                cleanBackgroundText = false,
                contextNotes = contextNotes
            )
            firestoreRepo.createProject(project).fold(
                onSuccess = { created ->
                    _selectedProjectId.value = created.id
                    _uiNotice.value = UiNotice.Success("تم إنشاء مشروع «${created.title}»")
                    onCreated(created)
                },
                onFailure = { e ->
                    _uiNotice.value = UiNotice.Error("CREATE_PROJECT_FAILED", e.localizedMessage ?: "فشل إنشاء المشروع")
                }
            )
        }
    }

    fun updateProjectSettings(updated: ComicProject) {
        viewModelScope.launch {
            firestoreRepo.updateProject(updated).fold(
                onSuccess = { _uiNotice.value = UiNotice.Success("تم حفظ إعدادات المشروع") },
                onFailure = { e -> _uiNotice.value = UiNotice.Error("UPDATE_FAILED", e.localizedMessage ?: "تعذر الحفظ") }
            )
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            pageDao.deletePagesForProject(projectId)
            File(appContext.filesDir, "faqqa_pages/$projectId").deleteRecursively()
            firestoreRepo.deleteProject(projectId).fold(
                onSuccess = {
                    if (_selectedProjectId.value == projectId) _selectedProjectId.value = null
                    _uiNotice.value = UiNotice.Success("تم حذف المشروع")
                },
                onFailure = { e ->
                    _uiNotice.value = UiNotice.Error("DELETE_FAILED", e.localizedMessage ?: "تعذر الحذف")
                }
            )
        }
    }

    private suspend fun ensureTargetProject(defaultTitle: String): ComicProject? {
        val current = activeProject.value
        if (current != null) return current
        val created = firestoreRepo.createProject(
            ComicProject(title = defaultTitle, sourceLang = "AUTO", targetLang = "AR")
        ).getOrNull() ?: return null
        _selectedProjectId.value = created.id
        return created
    }

    /**
     * Path A: Direct URL Import via SourceAdapterRegistry.
     * Explicitly returns BLOCKED_BY_SOURCE on 403/Cloudflare/CAPTCHA without bypassing.
     */
    fun importFromDirectUrl(url: String, chapterTitle: String = "") {
        viewModelScope.launch {
            if (url.isBlank()) {
                _uiNotice.value = UiNotice.Error("INVALID_URL", "يرجى إدخال رابط صحيح.")
                return@launch
            }
            _uiNotice.value = UiNotice.Loading("جارٍ تحليل الرابط عبر SourceAdapter...")
            val result = withContext(Dispatchers.IO) {
                sourceAdapterRegistry.fetchChapter(url)
            }
            when (result) {
                is DirectImportResult.BlockedBySource -> {
                    firestoreRepo.logSourceAdapterAttempt(
                        SourceAdapterLogRecord(
                            url = url,
                            adapterName = result.adapterName,
                            status = "blocked_by_source",
                            httpCode = result.httpCode.toLong(),
                            extractedCount = 0L,
                            message = result.message
                        )
                    )
                    _uiNotice.value = UiNotice.Error(
                        code = result.code,
                        message = result.message,
                        suggestPathB = true
                    )
                }
                is DirectImportResult.Error -> {
                    firestoreRepo.logSourceAdapterAttempt(
                        SourceAdapterLogRecord(
                            url = url,
                            adapterName = result.adapterName,
                            status = "error",
                            httpCode = result.httpCode.toLong(),
                            extractedCount = 0L,
                            message = result.message
                        )
                    )
                    _uiNotice.value = UiNotice.Error(
                        code = result.code,
                        message = result.message,
                        suggestPathB = true
                    )
                }
                is DirectImportResult.Success -> {
                    val project = ensureTargetProject(result.chapterTitle)
                    if (project == null) {
                        _uiNotice.value = UiNotice.Error("PROJECT_ERROR", "تعذر إنشاء المشروع.")
                        return@launch
                    }
                    val chapDir = File(appContext.filesDir, "faqqa_pages/${project.id}/direct_${System.currentTimeMillis()}")
                    val downloaded = withContext(Dispatchers.IO) {
                        val list = mutableListOf<ImportedImageFile>()
                        result.imageUrls.take(60).forEachIndexed { idx, imgUrl ->
                            val bytes = sourceAdapterRegistry.downloadImageBytes(imgUrl, referer = url)
                            if (bytes != null) {
                                FileImportEngine.saveBytesAsImmutableOriginal(bytes, chapDir, idx)?.let {
                                    list.add(it)
                                }
                            }
                        }
                        list
                    }
                    firestoreRepo.logSourceAdapterAttempt(
                        SourceAdapterLogRecord(
                            url = url,
                            adapterName = result.adapterName,
                            status = if (downloaded.isNotEmpty()) "success" else "error",
                            httpCode = result.httpCode.toLong(),
                            extractedCount = downloaded.size.toLong(),
                            message = "تم استخراج ${downloaded.size} صورة من ${result.imageUrls.size} رابط"
                        )
                    )
                    if (downloaded.isEmpty()) {
                        _uiNotice.value = UiNotice.Error(
                            code = "IMAGE_DOWNLOAD_BLOCKED",
                            message = "رفض المصدر تحميل ملفات الصور (Hotlink/403). افتح الرابط في المتصفح المدمج لالتقاط الصور مباشرة.",
                            suggestPathB = true
                        )
                    } else {
                        saveImportedChapter(
                            project = project,
                            title = chapterTitle.ifBlank { result.chapterTitle },
                            importSource = "direct_url",
                            sourceUrl = url,
                            files = downloaded
                        )
                    }
                }
            }
        }
    }

    /**
     * Path B (NO source_fetch!): Receives images directly from Android WebView (Capture/DOM/JSBridge)
     * or Android Share Target (ACTION_SEND / ACTION_SEND_MULTIPLE).
     */
    fun recordBrowserHistory(url: String, title: String) {
        viewModelScope.launch {
            browserHistoryDao.insert(LocalBrowserHistoryEntity(url = url, title = title.ifBlank { url }))
        }
    }

    fun receiveBrowserCapturedBitmap(bitmap: Bitmap, sourceUrl: String = "") {
        viewModelScope.launch {
            val stagingDir = File(appContext.filesDir, "faqqa_staging")
            val imported = withContext(Dispatchers.IO) {
                FileImportEngine.saveBitmapAsImmutableOriginal(
                    bitmap = bitmap,
                    projectDir = stagingDir,
                    pageOrder = _browserStagedImages.value.size
                )
            }
            _browserStagedImages.value = _browserStagedImages.value + imported
            _receivedBrowserImagesCount.value += 1
            _uiNotice.value = UiNotice.Success("تم التقاط الصورة من المتصفح (العداد: ${_receivedBrowserImagesCount.value})")
        }
    }

    fun receiveBrowserBase64OrBytes(dataUrlOrBase64: String) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    val pure = if (dataUrlOrBase64.contains(",")) {
                        dataUrlOrBase64.substringAfter(",")
                    } else dataUrlOrBase64
                    Base64.decode(pure, Base64.DEFAULT)
                } catch (_: Exception) {
                    null
                }
            }
            if (bytes == null) {
                _uiNotice.value = UiNotice.Error("INVALID_BROWSER_IMAGE", "تعذر فك ترميز الصورة الملتقطة من DOM.")
                return@launch
            }
            val stagingDir = File(appContext.filesDir, "faqqa_staging")
            val imported = withContext(Dispatchers.IO) {
                FileImportEngine.saveBytesAsImmutableOriginal(bytes, stagingDir, _browserStagedImages.value.size)
            }
            if (imported != null) {
                _browserStagedImages.value = _browserStagedImages.value + imported
                _receivedBrowserImagesCount.value += 1
                _uiNotice.value = UiNotice.Success("تم استلام صورة DOM (العداد: ${_receivedBrowserImagesCount.value})")
            } else {
                _uiNotice.value = UiNotice.Error("UNSUPPORTED_IMAGE", "الصورة غير صالحة أو محمية بـ CORS في Canvas.")
            }
        }
    }

    fun commitStagedBrowserImagesToChapter(chapterTitle: String, sourceUrl: String, onReady: () -> Unit) {
        viewModelScope.launch {
            val staged = _browserStagedImages.value
            if (staged.isEmpty()) {
                _uiNotice.value = UiNotice.Error("NO_STAGED_IMAGES", "التقط أو اختر صورة واحدة على الأقل أولًا.")
                return@launch
            }
            val project = ensureTargetProject("استيراد المتصفح")
            if (project == null) {
                _uiNotice.value = UiNotice.Error("PROJECT_ERROR", "تعذر إنشاء المشروع.")
                return@launch
            }
            saveImportedChapter(
                project = project,
                title = chapterTitle.ifBlank { "فصل من المتصفح" },
                importSource = "browser_webview",
                sourceUrl = sourceUrl,
                files = staged
            )
            _browserStagedImages.value = emptyList()
            onReady()
        }
    }

    fun importSharedUrisFromExternalBrowser(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiNotice.value = UiNotice.Loading("جارٍ استقبال ${uris.size} صورة عبر Share Target...")
            val project = ensureTargetProject("استيراد Share Target") ?: return@launch
            val destDir = File(appContext.filesDir, "faqqa_pages/${project.id}/share_${System.currentTimeMillis()}")
            val res = FileImportEngine.importUriList(appContext, uris, destDir)
            res.fold(
                onSuccess = { files ->
                    _receivedBrowserImagesCount.value += files.size
                    saveImportedChapter(
                        project = project,
                        title = "فصل مستلم (${files.size} ص)",
                        importSource = "share_target",
                        sourceUrl = "",
                        files = files
                    )
                },
                onFailure = { e ->
                    _uiNotice.value = UiNotice.Error("SHARE_IMPORT_FAILED", e.localizedMessage ?: "فشل استقبال الصور.")
                }
            )
        }
    }

    /**
     * Path C: File Import (JPG/PNG/WEBP/PDF/ZIP/CBZ).
     */
    fun importFilesFromPicker(uris: List<Uri>, chapterTitle: String = "", onReady: () -> Unit = {}) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiNotice.value = UiNotice.Loading("جارٍ معالجة وفك الملفات بأمان...")
            val project = ensureTargetProject(chapterTitle.ifBlank { "فصل مستورد" }) ?: return@launch
            val destDir = File(appContext.filesDir, "faqqa_pages/${project.id}/file_${System.currentTimeMillis()}")
            FileImportEngine.importUriList(appContext, uris, destDir).fold(
                onSuccess = { files ->
                    saveImportedChapter(
                        project = project,
                        title = chapterTitle.ifBlank { "الفصل ${chapters.value.size + 1}" },
                        importSource = "file_import",
                        sourceUrl = "",
                        files = files
                    )
                    onReady()
                },
                onFailure = { e ->
                    _uiNotice.value = UiNotice.Error("FILE_IMPORT_FAILED", e.localizedMessage ?: "فشل استيراد الملفات.")
                }
            )
        }
    }

    private suspend fun saveImportedChapter(
        project: ComicProject,
        title: String,
        importSource: String,
        sourceUrl: String,
        files: List<ImportedImageFile>
    ) {
        val nextNumber = (chapters.value.maxOfOrNull { it.chapterNumber } ?: 0L) + 1L
        val chapRes = firestoreRepo.createChapter(
            ChapterRecord(
                projectId = project.id,
                title = title,
                chapterNumber = nextNumber,
                importSource = importSource,
                sourceUrl = sourceUrl,
                pageCount = files.size.toLong(),
                lastReadPageIndex = 0L,
                isOfflineReady = true
            )
        )
        chapRes.fold(
            onSuccess = { chap ->
                val pageEntities = files.mapIndexed { idx, img ->
                    LocalPageEntity(
                        id = "pg_${UUID.randomUUID().toString().replace("-", "").take(14)}",
                        userId = currentUserId,
                        projectId = project.id,
                        chapterId = chap.id,
                        pageOrder = idx,
                        originalImagePath = img.filePath,
                        width = img.width,
                        height = img.height,
                        status = "imported"
                    )
                }
                pageDao.insertPages(pageEntities)
                firestoreRepo.updateProject(
                    project.copy(pageCount = project.pageCount + pageEntities.size)
                )
                _selectedChapterId.value = chap.id
                _selectedPageId.value = pageEntities.firstOrNull()?.id
                _uiNotice.value = UiNotice.Success("تم استيراد ${pageEntities.size} صفحة في «${chap.title}»")
            },
            onFailure = { e ->
                _uiNotice.value = UiNotice.Error("CHAPTER_SAVE_FAILED", e.localizedMessage ?: "فشل حفظ الفصل")
            }
        )
    }

    /**
     * Full Pipeline: OCR + Bubble Detection -> Translation -> Exact Mask + Shrink + Inpaint -> Clean Arabic Render
     */
    fun analyzeAndTranslatePage(page: LocalPageEntity) {
        viewModelScope.launch {
            val project = activeProject.value ?: return@launch
            val providers = providerConfigs.value
            val selectedProvider = providers.firstOrNull { it.isSelected }
                ?: providers.firstOrNull { it.providerId == "firebase_ai" }
                ?: ProviderCatalog.defaultProviders().first()

            _uiNotice.value = UiNotice.Loading("جارٍ تحليل الفقاعات والترجمة عبر ${selectedProvider.displayName}...")
            val job = firestoreRepo.createTranslationJob(
                TranslationJobRecord(
                    projectId = project.id,
                    chapterId = page.chapterId,
                    providerId = selectedProvider.providerId,
                    status = "running",
                    processedPages = 0L,
                    totalPages = 1L
                )
            ).getOrNull()

            try {
                val bitmap = withContext(Dispatchers.IO) {
                    android.graphics.BitmapFactory.decodeFile(page.originalImagePath)
                } ?: throw IllegalStateException("تعذر فتح الصورة الأصلية.")

                val style = TranslationStyle.fromWire(project.translationStyle)
                val sfxMode = SfxMode.fromWire(project.sfxMode)
                val projectGlossary = glossary.value

                val detected = if (selectedProvider.providerId == "firebase_ai") {
                    providerRegistry.analyzeAndTranslatePageMultimodal(
                        bitmap = bitmap,
                        readingDirection = project.readingDirection,
                        targetLang = project.targetLang,
                        style = style,
                        glossary = projectGlossary,
                        projectContext = project.contextNotes
                    ).getOrThrow()
                } else {
                    // Use offline ML Kit OCR for bounding boxes & text, then translate via user's selected provider!
                    val ocrRegions = BubbleImageEngine.detectRegionsOfflineMlKit(bitmap)
                    if (ocrRegions.isEmpty()) {
                        emptyList()
                    } else {
                        val inputs = ocrRegions.mapIndexed { idx, r ->
                            TranslationInputItem("reg_$idx", r.sourceText, r.regionType)
                        }
                        val translated = providerRegistry.translateBatch(
                            items = inputs,
                            sourceLang = project.sourceLang,
                            targetLang = project.targetLang,
                            style = style,
                            glossary = projectGlossary,
                            projectContext = project.contextNotes,
                            config = selectedProvider
                        ).getOrThrow().associateBy { it.regionId }

                        ocrRegions.mapIndexed { idx, r ->
                            r.copy(translatedText = translated["reg_$idx"]?.translatedText.orEmpty())
                        }
                    }
                }
                bitmap.recycle()

                val entities = detected.mapIndexed { idx, d ->
                    LocalTextRegionEntity(
                        id = "reg_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                        pageId = page.id,
                        orderIndex = idx,
                        regionType = d.regionType,
                        leftNorm = d.leftNorm,
                        topNorm = d.topNorm,
                        rightNorm = d.rightNorm,
                        bottomNorm = d.bottomNorm,
                        sourceText = d.sourceText,
                        detectedLang = d.detectedLang,
                        translatedText = d.translatedText,
                        confidence = d.confidence
                    )
                }

                regionDao.deleteRegionsForPage(page.id)
                regionDao.insertRegions(entities)

                val pageDir = File(appContext.filesDir, "faqqa_pages/${project.id}/${page.chapterId}")
                val cleanedPath = File(pageDir, "clean_${page.id}.png").absolutePath
                val renderedPath = File(pageDir, "render_${page.id}.png").absolutePath

                withContext(Dispatchers.IO) {
                    BubbleImageEngine.cleanAndRenderPage(
                        context = appContext,
                        originalImagePath = page.originalImagePath,
                        brushMaskPath = page.brushMaskPath,
                        regions = entities,
                        sfxMode = sfxMode,
                        cleanBackgroundText = project.cleanBackgroundText,
                        cleanedOutPath = cleanedPath,
                        renderedOutPath = renderedPath
                    ).getOrThrow()
                }

                pageDao.upsertPage(
                    page.copy(
                        cleanedImagePath = cleanedPath,
                        renderedImagePath = renderedPath,
                        status = "translated",
                        detectedLang = detected.firstOrNull()?.detectedLang ?: project.sourceLang,
                        errorMessage = "",
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )

                if (job != null) {
                    firestoreRepo.updateTranslationJob(job.id, "completed", 1L, 1L)
                }
                _uiNotice.value = UiNotice.Success("تم اكتشاف ${entities.size} فقاعة وتبييضها وترجمتها بدقة")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pageDao.upsertPage(page.copy(status = "error", errorMessage = e.localizedMessage.orEmpty()))
                if (job != null) {
                    firestoreRepo.updateTranslationJob(job.id, "failed", 0L, 1L, e.localizedMessage.orEmpty())
                }
                _uiNotice.value = UiNotice.Error("PIPELINE_ERROR", e.localizedMessage ?: "فشلت معالجة الصفحة")
            }
        }
    }

    fun analyzeAndTranslateChapter(chapter: ChapterRecord) {
        viewModelScope.launch {
            val chapPages = pageDao.getPagesForChapterOnce(chapter.id)
            if (chapPages.isEmpty()) {
                _uiNotice.value = UiNotice.Error("EMPTY_CHAPTER", "لا توجد صفحات في هذا الفصل.")
                return@launch
            }
            for (pg in chapPages) {
                analyzeAndTranslatePage(pg)
            }
        }
    }

    fun updateRegionAndRender(updatedRegion: LocalTextRegionEntity) {
        viewModelScope.launch {
            val currentList = activePageRegions.value
            if (currentList.isNotEmpty()) {
                undoStack.addLast(currentList)
                redoStack.clear()
            }
            regionDao.updateRegion(updatedRegion)
            rerenderCurrentPage()
        }
    }

    fun addManualRegionAndRender(leftNorm: Float, topNorm: Float, rightNorm: Float, bottomNorm: Float) {
        viewModelScope.launch {
            val page = activePage.value ?: return@launch
            val currentList = activePageRegions.value
            undoStack.addLast(currentList)
            redoStack.clear()

            val newRegion = LocalTextRegionEntity(
                id = "reg_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                pageId = page.id,
                orderIndex = currentList.size,
                regionType = "speech",
                leftNorm = leftNorm,
                topNorm = topNorm,
                rightNorm = rightNorm,
                bottomNorm = bottomNorm,
                sourceText = "",
                translatedText = "نص جديد"
            )
            regionDao.insertRegions(listOf(newRegion))
            rerenderCurrentPage()
        }
    }

    fun retranslateSingleRegion(region: LocalTextRegionEntity) {
        viewModelScope.launch {
            val project = activeProject.value ?: return@launch
            val provider = providerConfigs.value.firstOrNull { it.isSelected }
                ?: ProviderCatalog.defaultProviders().first()
            if (region.sourceText.isBlank()) {
                _uiNotice.value = UiNotice.Error("NO_SOURCE_TEXT", "لا يوجد نص أصلي لإعادة ترجمته.")
                return@launch
            }
            _uiNotice.value = UiNotice.Loading("جارٍ إعادة الترجمة عبر ${provider.displayName}...")
            providerRegistry.translateBatch(
                items = listOf(TranslationInputItem(region.id, region.sourceText, region.regionType)),
                sourceLang = project.sourceLang,
                targetLang = project.targetLang,
                style = TranslationStyle.fromWire(project.translationStyle),
                glossary = glossary.value,
                projectContext = project.contextNotes,
                config = provider
            ).fold(
                onSuccess = { out ->
                    val newText = out.firstOrNull()?.translatedText ?: region.translatedText
                    updateRegionAndRender(region.copy(translatedText = newText))
                    _uiNotice.value = UiNotice.Success("تمت إعادة الترجمة")
                },
                onFailure = { e ->
                    _uiNotice.value = UiNotice.Error("RETRANSLATE_FAILED", e.localizedMessage ?: "فشلت إعادة الترجمة")
                }
            )
        }
    }

    fun applyBrushStroke(pointsNorm: List<Pair<Float, Float>>, strokeWidthNorm: Float = 0.03f) {
        viewModelScope.launch {
            val page = activePage.value ?: return@launch
            val project = activeProject.value ?: return@launch
            val pageDir = File(appContext.filesDir, "faqqa_pages/${project.id}/${page.chapterId}")
            val brushPath = page.brushMaskPath.ifBlank {
                File(pageDir, "brush_${page.id}.png").absolutePath
            }
            withContext(Dispatchers.IO) {
                BubbleImageEngine.applyManualBrushStroke(
                    brushMaskPath = brushPath,
                    width = page.width.coerceAtLeast(400),
                    height = page.height.coerceAtLeast(400),
                    pointsNorm = pointsNorm,
                    strokeWidthNorm = strokeWidthNorm,
                    fillColor = android.graphics.Color.WHITE
                )
            }
            pageDao.upsertPage(page.copy(brushMaskPath = brushPath))
            rerenderCurrentPage(page.copy(brushMaskPath = brushPath))
        }
    }

    fun undoRegionEdit() {
        viewModelScope.launch {
            val page = activePage.value ?: return@launch
            val prev = undoStack.removeLastOrNull() ?: return@launch
            redoStack.addLast(activePageRegions.value)
            regionDao.deleteRegionsForPage(page.id)
            regionDao.insertRegions(prev)
            rerenderCurrentPage()
        }
    }

    fun redoRegionEdit() {
        viewModelScope.launch {
            val page = activePage.value ?: return@launch
            val next = redoStack.removeLastOrNull() ?: return@launch
            undoStack.addLast(activePageRegions.value)
            regionDao.deleteRegionsForPage(page.id)
            regionDao.insertRegions(next)
            rerenderCurrentPage()
        }
    }

    private suspend fun rerenderCurrentPage(overridePage: LocalPageEntity? = null) {
        val page = overridePage ?: activePage.value ?: return
        val project = activeProject.value ?: return
        val regions = regionDao.getRegionsForPageOnce(page.id)
        val pageDir = File(appContext.filesDir, "faqqa_pages/${project.id}/${page.chapterId}")
        val cleanedPath = page.cleanedImagePath.ifBlank { File(pageDir, "clean_${page.id}.png").absolutePath }
        val renderedPath = page.renderedImagePath.ifBlank { File(pageDir, "render_${page.id}.png").absolutePath }

        withContext(Dispatchers.IO) {
            BubbleImageEngine.cleanAndRenderPage(
                context = appContext,
                originalImagePath = page.originalImagePath,
                brushMaskPath = page.brushMaskPath,
                regions = regions,
                sfxMode = SfxMode.fromWire(project.sfxMode),
                cleanBackgroundText = project.cleanBackgroundText,
                cleanedOutPath = cleanedPath,
                renderedOutPath = renderedPath
            )
        }
        pageDao.upsertPage(
            page.copy(
                cleanedImagePath = cleanedPath,
                renderedImagePath = renderedPath,
                status = "translated",
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    fun updateLastReadPage(index: Int) {
        val chap = activeChapter.value ?: return
        viewModelScope.launch {
            firestoreRepo.updateChapterProgress(
                projectId = chap.projectId,
                chapterId = chap.id,
                pageCount = pages.value.size.toLong(),
                lastReadPageIndex = index.toLong(),
                isOfflineReady = true
            )
        }
    }

    fun addGlossaryEntry(source: String, target: String, category: String, notes: String) {
        val proj = activeProject.value ?: return
        viewModelScope.launch {
            firestoreRepo.addGlossaryEntry(
                GlossaryEntry(
                    projectId = proj.id,
                    sourceTerm = source,
                    targetTerm = target,
                    category = category,
                    notes = notes
                )
            ).fold(
                onSuccess = { _uiNotice.value = UiNotice.Success("تمت إضافة المصطلح إلى القاموس") },
                onFailure = { e -> _uiNotice.value = UiNotice.Error("GLOSSARY_ERROR", e.localizedMessage ?: "فشل الحفظ") }
            )
        }
    }

    fun deleteGlossaryEntry(entryId: String) {
        val proj = activeProject.value ?: return
        viewModelScope.launch {
            firestoreRepo.deleteGlossaryEntry(proj.id, entryId)
        }
    }

    fun saveProviderConfig(
        provider: LocalProviderConfigEntity,
        plainApiKey: String?,
        newBaseUrl: String,
        newModel: String,
        selectAsActive: Boolean
    ) {
        viewModelScope.launch {
            val all = providerDao.getProvidersOnce()
            if (selectAsActive) {
                all.forEach { p ->
                    if (p.isSelected && p.providerId != provider.providerId) {
                        providerDao.upsertProvider(p.copy(isSelected = false))
                    }
                }
            }
            val encrypted = if (!plainApiKey.isNullOrBlank()) {
                CryptoKeyStore.encrypt(plainApiKey.trim())
            } else {
                provider.encryptedApiKey
            }
            val updated = provider.copy(
                baseUrl = newBaseUrl.trim().ifBlank { provider.baseUrl },
                modelName = newModel.trim().ifBlank { provider.modelName },
                encryptedApiKey = encrypted,
                isSelected = selectAsActive || provider.isSelected,
                updatedAtMillis = System.currentTimeMillis()
            )
            providerDao.upsertProvider(updated)
            _uiNotice.value = UiNotice.Success("تم حفظ إعدادات ${updated.displayName} بتشفير AES-256-GCM")
        }
    }

    fun testProviderConnection(provider: LocalProviderConfigEntity) {
        viewModelScope.launch {
            _uiNotice.value = UiNotice.Loading("جارٍ اختبار الاتصال الحقيقي بـ ${provider.displayName}...")
            val res = providerRegistry.testConnection(provider)
            res.fold(
                onSuccess = { msg ->
                    providerDao.upsertProvider(
                        provider.copy(
                            lastTestStatus = "success",
                            lastTestMessage = msg,
                            updatedAtMillis = System.currentTimeMillis()
                        )
                    )
                    _uiNotice.value = UiNotice.Success(msg)
                },
                onFailure = { e ->
                    val errMsg = e.localizedMessage ?: "فشل الاتصال"
                    providerDao.upsertProvider(
                        provider.copy(
                            lastTestStatus = "failed",
                            lastTestMessage = errMsg,
                            updatedAtMillis = System.currentTimeMillis()
                        )
                    )
                    _uiNotice.value = UiNotice.Error("PROVIDER_TEST_FAILED", errMsg)
                }
            )
        }
    }

    fun exportCurrentChapter(format: ExportFormat, targetUri: Uri) {
        viewModelScope.launch {
            val chapPages = pages.value
            _uiNotice.value = UiNotice.Loading("جارٍ تصدير الفصل بصيغة ${format.name}...")
            ExportEngine.exportChapterToUri(appContext, chapPages, format, targetUri).fold(
                onSuccess = { msg -> _uiNotice.value = UiNotice.Success(msg) },
                onFailure = { e -> _uiNotice.value = UiNotice.Error("EXPORT_FAILED", e.localizedMessage ?: "فشل التصدير") }
            )
        }
    }
}
