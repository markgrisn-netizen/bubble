package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.engine.importpath.ImportedImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File

data class DomImageCandidate(
    val url: String,
    val width: Int,
    val height: Int
)

private class FaqqaJsBridge(
    private val onImageDataUrl: (String) -> Unit,
    private val onDomImages: (List<DomImageCandidate>) -> Unit
) {
    @JavascriptInterface
    fun onImageCaptured(dataUrl: String) {
        if (dataUrl.isNotBlank()) {
            onImageDataUrl(dataUrl)
        }
    }

    @JavascriptInterface
    fun onDomImagesExtracted(jsonArrayStr: String) {
        try {
            val arr = JSONArray(jsonArrayStr)
            val list = mutableListOf<DomImageCandidate>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val url = obj.optString("src")
                val w = obj.optInt("w", 0)
                val h = obj.optInt("h", 0)
                if (url.isNotBlank()) {
                    list.add(DomImageCandidate(url, w, h))
                }
            }
            onDomImages(list)
        } catch (_: Exception) {
            onDomImages(emptyList())
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    initialUrl: String,
    receivedImagesCount: Int,
    stagedImages: List<ImportedImageFile>,
    onRecordHistory: (String, String) -> Unit,
    onImportChapterUrl: (String) -> Unit,
    onCapturePageBitmap: (Bitmap, String) -> Unit,
    onReceiveDataUrl: (String) -> Unit,
    onReceiveImageBytes: (ByteArray) -> Unit,
    onSaveAndTranslateStaged: (String, String) -> Unit
) {
    val homeUrl = "https://google.com"
    var urlInput by remember(initialUrl) { mutableStateOf(initialUrl.ifBlank { homeUrl }) }
    var currentUrl by remember { mutableStateOf(urlInput) }
    var pageTitle by remember { mutableStateOf("متصفح فقاعة") }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    var domCandidates by remember { mutableStateOf<List<DomImageCandidate>>(emptyList()) }
    var showDomPicker by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var chapterTitleInput by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val httpClient = remember { OkHttpClient() }

    val jsBridge = remember {
        FaqqaJsBridge(
            onImageDataUrl = { dataUrl ->
                scope.launch(Dispatchers.Main) {
                    onReceiveDataUrl(dataUrl)
                }
            },
            onDomImages = { candidates ->
                scope.launch(Dispatchers.Main) {
                    domCandidates = candidates
                    showDomPicker = true
                }
            }
        )
    }

    fun navigateTo(raw: String) {
        val target = raw.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it
            else "https://$it"
        }
        urlInput = target
        webViewRef?.loadUrl(target)
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Real Browser Address & Navigation Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { webViewRef?.goBack() },
                        enabled = canGoBack,
                        modifier = Modifier.testTag("browser_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                    IconButton(
                        onClick = { webViewRef?.goForward() },
                        enabled = canGoForward,
                        modifier = Modifier.testTag("browser_forward_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "تقدم")
                    }
                    IconButton(
                        onClick = { webViewRef?.reload() },
                        modifier = Modifier.testTag("browser_refresh_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                    }
                    IconButton(
                        onClick = { navigateTo(homeUrl) },
                        modifier = Modifier.testTag("browser_home_button")
                    ) {
                        Icon(Icons.Default.Home, contentDescription = "الرئيسية")
                    }

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        placeholder = { Text("https://...") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { navigateTo(urlInput) }),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("browser_url_input")
                    )

                    Button(
                        onClick = { navigateTo(urlInput) },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(48.dp).testTag("browser_go_button")
                    ) {
                        Text("Go")
                    }
                }

                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
        }

        // Native Android WebView
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                        addJavascriptInterface(jsBridge, "FaqqaBridge")
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val resolved = url.orEmpty()
                                currentUrl = resolved
                                urlInput = resolved
                                pageTitle = view?.title.orEmpty()
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                                if (resolved.isNotBlank()) {
                                    onRecordHistory(resolved, pageTitle)
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        setDownloadListener { downloadUrl, _, _, mimeType, _ ->
                            if (mimeType.orEmpty().startsWith("image/") ||
                                downloadUrl.matches(Regex(".*\\.(jpg|jpeg|png|webp)(\\?.*)?$", RegexOption.IGNORE_CASE))
                            ) {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val req = Request.Builder().url(downloadUrl).build()
                                        httpClient.newCall(req).execute().use { resp ->
                                            resp.body?.bytes()?.let { bytes ->
                                                withContext(Dispatchers.Main) {
                                                    onReceiveImageBytes(bytes)
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                        loadUrl(urlInput)
                        webViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize().testTag("native_webview")
            )

            // Floating Staged Counter Pill
            if (stagedImages.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "صور ملتقطة: ${stagedImages.size} (الإجمالي: $receivedImagesCount)",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        // Translation Toolbar Inside Browser
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    OutlinedButton(
                        onClick = { onImportChapterUrl(currentUrl) },
                        modifier = Modifier.height(48.dp).testTag("toolbar_import_chapter")
                    ) {
                        Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Import Chapter")
                    }
                }
                item {
                    Button(
                        onClick = {
                            val wv = webViewRef ?: return@Button
                            if (wv.width > 0 && wv.height > 0) {
                                val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bmp)
                                wv.draw(canvas)
                                onCapturePageBitmap(bmp, currentUrl)
                            }
                        },
                        modifier = Modifier.height(48.dp).testTag("toolbar_capture_page")
                    ) {
                        Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Capture Page")
                    }
                }
                item {
                    OutlinedButton(
                        onClick = {
                            val js = """
                                (function() {
                                  var imgs = Array.from(document.querySelectorAll('img')).map(function(img) {
                                    var src = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || img.currentSrc || img.src || '';
                                    return { src: src, w: img.naturalWidth || img.width || 0, h: img.naturalHeight || img.height || 0 };
                                  }).filter(function(item) {
                                    return item.src && !item.src.startsWith('data:image/svg') && (item.w === 0 || item.w >= 120);
                                  });
                                  FaqqaBridge.onDomImagesExtracted(JSON.stringify(imgs.slice(0, 60)));
                                })();
                            """.trimIndent()
                            webViewRef?.evaluateJavascript(js, null)
                        },
                        modifier = Modifier.height(48.dp).testTag("toolbar_select_image")
                    ) {
                        Icon(Icons.Default.ImageSearch, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Select Image")
                    }
                }
                item {
                    OutlinedButton(
                        onClick = {
                            chapterTitleInput = pageTitle.ifBlank { "فصل من المتصفح" }
                            showSaveDialog = true
                        },
                        enabled = stagedImages.isNotEmpty(),
                        modifier = Modifier.height(48.dp).testTag("toolbar_save_images")
                    ) {
                        Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save Images (${stagedImages.size})")
                    }
                }
                item {
                    Button(
                        onClick = {
                            onSaveAndTranslateStaged(
                                pageTitle.ifBlank { "فصل من المتصفح" },
                                currentUrl
                            )
                        },
                        enabled = stagedImages.isNotEmpty(),
                        modifier = Modifier.height(48.dp).testTag("toolbar_send_to_translator")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Send to Translator")
                    }
                }
            }
        }
    }

    if (showDomPicker) {
        AlertDialog(
            onDismissRequest = { showDomPicker = false },
            title = { Text("صور الصفحة (${domCandidates.size})") },
            text = {
                if (domCandidates.isEmpty()) {
                    Text("لم يتم العثور على صور كبيرة في DOM. استخدم زر Capture Page لالتقاط الصفحة مباشرة.")
                } else {
                    LazyColumn(
                        modifier = Modifier.height(340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(domCandidates) { candidate ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AsyncImage(
                                        model = candidate.url,
                                        contentDescription = "معاينة صورة DOM",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp))
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = candidate.url,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            text = "${candidate.width} × ${candidate.height}",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Button(onClick = {
                                        val escaped = candidate.url.replace("'", "\\'")
                                        // Extract directly via browser canvas first, fallback to direct bytes
                                        val captureJs = """
                                            (function(){
                                              try {
                                                var img = Array.from(document.images).find(i => (i.currentSrc || i.src || i.getAttribute('data-src')) === '$escaped');
                                                if (img && img.complete && img.naturalWidth > 0) {
                                                  var c = document.createElement('canvas');
                                                  c.width = img.naturalWidth; c.height = img.naturalHeight;
                                                  c.getContext('2d').drawImage(img, 0, 0);
                                                  FaqqaBridge.onImageCaptured(c.toDataURL('image/png'));
                                                  return 'CANVAS_OK';
                                                }
                                              } catch(e) { return 'CORS'; }
                                              return 'FALLBACK';
                                            })();
                                        """.trimIndent()
                                        webViewRef?.evaluateJavascript(captureJs) { res ->
                                            if (res?.contains("CANVAS_OK") != true) {
                                                scope.launch(Dispatchers.IO) {
                                                    try {
                                                        val req = Request.Builder()
                                                            .url(candidate.url)
                                                            .header("Referer", currentUrl)
                                                            .build()
                                                        httpClient.newCall(req).execute().use { resp ->
                                                            val bytes = resp.body?.bytes()
                                                            if (resp.isSuccessful && bytes != null) {
                                                                withContext(Dispatchers.Main) {
                                                                    onReceiveImageBytes(bytes)
                                                                }
                                                            }
                                                        }
                                                    } catch (_: Exception) {
                                                    }
                                                }
                                            }
                                        }
                                    }) {
                                        Text("التقاط")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDomPicker = false }) { Text("إغلاق") }
            }
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("حفظ الصور في المكتبة") },
            text = {
                OutlinedTextField(
                    value = chapterTitleInput,
                    onValueChange = { chapterTitleInput = it },
                    label = { Text("اسم الفصل") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    showSaveDialog = false
                    onSaveAndTranslateStaged(chapterTitleInput, currentUrl)
                }) {
                    Text("حفظ وفتح المحرر")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("إلغاء") }
            }
        )
    }
}
