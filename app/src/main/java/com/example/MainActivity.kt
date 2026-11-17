package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.firestore.FaqqaFirestoreRepository
import com.example.data.local.FaqqaLocalDatabase
import com.example.security.configureAppCheck
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.signOutFaqqa
import com.example.ui.screens.AiProvidersScreen
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.GlossaryAndJobsScreen
import com.example.ui.screens.HomeLibraryScreen
import com.example.ui.screens.ImportCenterScreen
import com.example.ui.screens.PageEditorScreen
import com.example.ui.screens.ReaderScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.FaqqaTheme
import com.example.ui.viewmodel.FaqqaStudioViewModel
import com.example.ui.viewmodel.UiNotice
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow

object FaqqaRoutes {
    const val HOME = "home"
    const val BROWSER = "browser"
    const val IMPORT = "import"
    const val EDITOR = "editor"
    const val READER = "reader"
    const val GLOSSARY = "glossary"
    const val AI_PROVIDERS = "ai_providers"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {
    private val pendingSharedUris = MutableStateFlow<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Mandatory App Check configuration BEFORE Compose UI or ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        extractSharedImageUris(intent)

        setContent {
            val sharedUris by pendingSharedUris.collectAsStateWithLifecycle()
            FaqqaRootApp(
                sharedUris = sharedUris,
                onConsumeSharedUris = { pendingSharedUris.value = emptyList() }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedImageUris(intent)
    }

    private fun extractSharedImageUris(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    pendingSharedUris.value = listOf(uri)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) {
                    pendingSharedUris.value = uris
                }
            }
        }
    }
}

private fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

@Composable
private fun FaqqaRootApp(
    sharedUris: List<Uri>,
    onConsumeSharedUris: () -> Unit
) {
    val auth = Firebase.auth
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    val user = currentUser
    if (user == null) {
        FaqqaTheme(darkTheme = true) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AuthScreen(onAuthSuccess = {})
            }
        }
    } else {
        val uid = user.uid
        key(uid) {
            val viewModel: FaqqaStudioViewModel = viewModel(
                key = uid,
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY])
                        val firestoreRepo = FaqqaFirestoreRepository(app)
                        val localDb = FaqqaLocalDatabase.getInstance(app)
                        FaqqaStudioViewModel(
                            appContext = app.applicationContext,
                            currentUserId = uid,
                            displayName = user.displayName ?: user.email ?: "مترجم فقاعة",
                            firestoreRepo = firestoreRepo,
                            localDb = localDb
                        )
                    }
                }
            )
            FaqqaMainShell(
                viewModel = viewModel,
                userEmail = user.email.orEmpty(),
                sharedUris = sharedUris,
                onConsumeSharedUris = onConsumeSharedUris
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FaqqaMainShell(
    viewModel: FaqqaStudioViewModel,
    userEmail: String,
    sharedUris: List<Uri>,
    onConsumeSharedUris: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    val isArabicUi by viewModel.isArabicUi.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val activeProject by viewModel.activeProject.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val activeChapter by viewModel.activeChapter.collectAsStateWithLifecycle()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val activePage by viewModel.activePage.collectAsStateWithLifecycle()
    val regions by viewModel.activePageRegions.collectAsStateWithLifecycle()
    val glossary by viewModel.glossary.collectAsStateWithLifecycle()
    val jobs by viewModel.translationJobs.collectAsStateWithLifecycle()
    val sourceLogs by viewModel.sourceLogs.collectAsStateWithLifecycle()
    val providers by viewModel.providerConfigs.collectAsStateWithLifecycle()
    val receivedCount by viewModel.receivedBrowserImagesCount.collectAsStateWithLifecycle()
    val stagedImages by viewModel.browserStagedImages.collectAsStateWithLifecycle()
    val uiNotice by viewModel.uiNotice.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: FaqqaRoutes.HOME

    var importInitialTab by remember { mutableIntStateOf(0) }
    var browserStartUrl by remember { mutableStateOf("https://google.com") }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            viewModel.importSharedUrisFromExternalBrowser(sharedUris)
            onConsumeSharedUris()
            navController.navigate(FaqqaRoutes.EDITOR) { launchSingleTop = true }
        }
    }

    LaunchedEffect(uiNotice) {
        when (val n = uiNotice) {
            is UiNotice.Success -> snackbarHostState.showSnackbar(n.message)
            is UiNotice.Error -> snackbarHostState.showSnackbar("${n.code}: ${n.message}")
            else -> {}
        }
    }

    val navItems = listOf(
        Triple(FaqqaRoutes.HOME, "المكتبة", Icons.Default.Home),
        Triple(FaqqaRoutes.BROWSER, "المتصفح", Icons.Default.Language),
        Triple(FaqqaRoutes.IMPORT, "الاستيراد", Icons.Default.CloudDownload),
        Triple(FaqqaRoutes.EDITOR, "المحرر", Icons.Default.EditNote),
        Triple(FaqqaRoutes.READER, "القارئ", Icons.AutoMirrored.Filled.MenuBook)
    )

    FaqqaTheme(darkTheme = isDarkTheme) {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (isArabicUi) LayoutDirection.Rtl else LayoutDirection.Ltr
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useRail = maxWidth >= 600.dp

                Scaffold(
                    snackbarHost = {
                        SnackbarHost(snackbarHostState) { data ->
                            Snackbar(snackbarData = data)
                        }
                    },
                    topBar = {
                        if (currentRoute != FaqqaRoutes.BROWSER && currentRoute != FaqqaRoutes.READER) {
                            TopAppBar(
                                title = {
                                    Text(
                                        when (currentRoute) {
                                            FaqqaRoutes.HOME -> "فقاعة · Faqqa"
                                            FaqqaRoutes.IMPORT -> "استيراد الفصول"
                                            FaqqaRoutes.EDITOR -> "محرر الفقاعات"
                                            FaqqaRoutes.GLOSSARY -> "القاموس والسجل"
                                            FaqqaRoutes.AI_PROVIDERS -> "مزودو الذكاء الاصطناعي"
                                            FaqqaRoutes.SETTINGS -> "الإعدادات"
                                            else -> "فقاعة"
                                        }
                                    )
                                },
                                actions = {
                                    if (uiNotice is UiNotice.Loading) {
                                        Row(
                                            modifier = Modifier.padding(end = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier
                                                    .padding(end = 6.dp)
                                                    .size(20.dp),
                                                strokeWidth = 2.dp
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    },
                    bottomBar = {
                        if (!useRail) {
                            NavigationBar {
                                navItems.forEach { (route, label, icon) ->
                                    NavigationBarItem(
                                        selected = currentRoute == route,
                                        onClick = {
                                            navController.navigate(route) {
                                                popUpTo(FaqqaRoutes.HOME) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(icon, contentDescription = label) },
                                        label = { Text(label) },
                                        modifier = Modifier.testTag("nav_$route")
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (useRail) {
                            NavigationRail(modifier = Modifier.safeDrawingPadding()) {
                                navItems.forEach { (route, label, icon) ->
                                    NavigationRailItem(
                                        selected = currentRoute == route,
                                        onClick = {
                                            navController.navigate(route) {
                                                popUpTo(FaqqaRoutes.HOME) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(icon, contentDescription = label) },
                                        label = { Text(label) },
                                        modifier = Modifier.testTag("rail_$route")
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            NavHost(
                                navController = navController,
                                startDestination = FaqqaRoutes.HOME
                            ) {
                                composable(FaqqaRoutes.HOME) {
                                    HomeLibraryScreen(
                                        projects = projects,
                                        activeProject = activeProject,
                                        chapters = chapters,
                                        receivedBrowserImagesCount = receivedCount,
                                        onSelectProject = { viewModel.selectProject(it) },
                                        onCreateProject = { t, s, st, d, m, sfx, ctx ->
                                            viewModel.createProject(t, s, st, d, m, sfx, ctx)
                                        },
                                        onDeleteProject = { viewModel.deleteProject(it) },
                                        onOpenImport = { tab ->
                                            importInitialTab = tab
                                            navController.navigate(FaqqaRoutes.IMPORT)
                                        },
                                        onOpenBrowser = { navController.navigate(FaqqaRoutes.BROWSER) },
                                        onOpenEditor = { chapId ->
                                            viewModel.selectChapter(chapId)
                                            navController.navigate(FaqqaRoutes.EDITOR)
                                        },
                                        onOpenReader = { chapId ->
                                            viewModel.selectChapter(chapId)
                                            navController.navigate(FaqqaRoutes.READER)
                                        },
                                        onOpenGlossary = { navController.navigate(FaqqaRoutes.GLOSSARY) },
                                        onOpenAiProviders = { navController.navigate(FaqqaRoutes.AI_PROVIDERS) },
                                        onOpenSettings = { navController.navigate(FaqqaRoutes.SETTINGS) }
                                    )
                                }

                                composable(FaqqaRoutes.BROWSER) {
                                    BrowserScreen(
                                        initialUrl = browserStartUrl,
                                        receivedImagesCount = receivedCount,
                                        stagedImages = stagedImages,
                                        onRecordHistory = { url, title ->
                                            viewModel.recordBrowserHistory(url, title)
                                        },
                                        onImportChapterUrl = { url ->
                                            viewModel.importFromDirectUrl(url)
                                            importInitialTab = 0
                                            navController.navigate(FaqqaRoutes.IMPORT)
                                        },
                                        onCapturePageBitmap = { bmp, url ->
                                            viewModel.receiveBrowserCapturedBitmap(bmp, url)
                                        },
                                        onReceiveDataUrl = { dataUrl ->
                                            viewModel.receiveBrowserBase64OrBytes(dataUrl)
                                        },
                                        onReceiveImageBytes = { bytes ->
                                            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                            viewModel.receiveBrowserBase64OrBytes(b64)
                                        },
                                        onSaveAndTranslateStaged = { chapTitle, srcUrl ->
                                            viewModel.commitStagedBrowserImagesToChapter(chapTitle, srcUrl) {
                                                navController.navigate(FaqqaRoutes.EDITOR)
                                            }
                                        }
                                    )
                                }

                                composable(FaqqaRoutes.IMPORT) {
                                    ImportCenterScreen(
                                        initialTab = importInitialTab,
                                        receivedBrowserImagesCount = receivedCount,
                                        stagedBrowserImagesCount = stagedImages.size,
                                        uiNotice = uiNotice,
                                        sourceLogs = sourceLogs,
                                        onImportDirectUrl = { url, title ->
                                            viewModel.importFromDirectUrl(url, title)
                                        },
                                        onOpenBrowserWithUrl = { url ->
                                            browserStartUrl = url.ifBlank { "https://google.com" }
                                            navController.navigate(FaqqaRoutes.BROWSER)
                                        },
                                        onCommitStagedBrowserImages = {
                                            viewModel.commitStagedBrowserImagesToChapter("فصل من المتصفح", "") {
                                                navController.navigate(FaqqaRoutes.EDITOR)
                                            }
                                        },
                                        onImportFiles = { uris, title ->
                                            viewModel.importFilesFromPicker(uris, title) {
                                                navController.navigate(FaqqaRoutes.EDITOR)
                                            }
                                        }
                                    )
                                }

                                composable(FaqqaRoutes.EDITOR) {
                                    PageEditorScreen(
                                        chapter = activeChapter,
                                        pages = pages,
                                        activePage = activePage,
                                        regions = regions,
                                        onSelectPage = { viewModel.selectPage(it) },
                                        onAnalyzePage = { viewModel.analyzeAndTranslatePage(it) },
                                        onAnalyzeChapter = { viewModel.analyzeAndTranslateChapter(it) },
                                        onUpdateRegion = { viewModel.updateRegionAndRender(it) },
                                        onRetranslateRegion = { viewModel.retranslateSingleRegion(it) },
                                        onAddManualRegion = { l, t, r, b ->
                                            viewModel.addManualRegionAndRender(l, t, r, b)
                                        },
                                        onApplyBrushStroke = { pts -> viewModel.applyBrushStroke(pts) },
                                        onUndo = { viewModel.undoRegionEdit() },
                                        onRedo = { viewModel.redoRegionEdit() },
                                        onExportChapter = { fmt, uri ->
                                            viewModel.exportCurrentChapter(fmt, uri)
                                        }
                                    )
                                }

                                composable(FaqqaRoutes.READER) {
                                    ReaderScreen(
                                        project = activeProject,
                                        chapter = activeChapter,
                                        pages = pages,
                                        regionsForActivePage = regions,
                                        onPageFocused = { viewModel.selectPage(it) },
                                        onSaveLastReadIndex = { idx -> viewModel.updateLastReadPage(idx) }
                                    )
                                }

                                composable(FaqqaRoutes.GLOSSARY) {
                                    GlossaryAndJobsScreen(
                                        activeProject = activeProject,
                                        glossary = glossary,
                                        jobs = jobs,
                                        onAddGlossary = { s, t, c, n ->
                                            viewModel.addGlossaryEntry(s, t, c, n)
                                        },
                                        onDeleteGlossary = { viewModel.deleteGlossaryEntry(it) }
                                    )
                                }

                                composable(FaqqaRoutes.AI_PROVIDERS) {
                                    AiProvidersScreen(
                                        providers = providers,
                                        onSaveProvider = { p, key, url, model, active ->
                                            viewModel.saveProviderConfig(p, key, url, model, active)
                                        },
                                        onTestConnection = { p -> viewModel.testProviderConnection(p) }
                                    )
                                }

                                composable(FaqqaRoutes.SETTINGS) {
                                    SettingsScreen(
                                        userEmail = userEmail,
                                        activeProject = activeProject,
                                        isDarkTheme = isDarkTheme,
                                        isArabicUi = isArabicUi,
                                        onToggleTheme = { viewModel.toggleTheme() },
                                        onToggleLanguage = { viewModel.toggleUiLanguage() },
                                        onUpdateProject = { viewModel.updateProjectSettings(it) },
                                        onSignOut = {
                                            signOutFaqqa(credentialManager, scope)
                                        }
                                    )
                                }
                            }

                            if (uiNotice is UiNotice.Loading) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            modifier = Modifier
                                                .padding(end = 10.dp)
                                                .size(20.dp)
                                        )
                                        Text((uiNotice as UiNotice.Loading).message)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
