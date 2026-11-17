package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.firestore.SourceAdapterLogRecord
import com.example.ui.viewmodel.UiNotice

@Composable
fun ImportCenterScreen(
    initialTab: Int,
    receivedBrowserImagesCount: Int,
    stagedBrowserImagesCount: Int,
    uiNotice: UiNotice,
    sourceLogs: List<SourceAdapterLogRecord>,
    onImportDirectUrl: (String, String) -> Unit,
    onOpenBrowserWithUrl: (String) -> Unit,
    onCommitStagedBrowserImages: () -> Unit,
    onImportFiles: (List<Uri>, String) -> Unit
) {
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var directUrl by remember { mutableStateOf("") }
    var chapterTitle by remember { mutableStateOf("") }

    // Path C: Zero-permission Android Photo Picker for JPG/PNG/WEBP
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportFiles(uris, chapterTitle)
        }
    }

    // Path C: SAF Document Picker for PDF / ZIP / CBZ / Images
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportFiles(uris, chapterTitle)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("A: رابط مباشر") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("B: المتصفح") }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("C: ملفات") }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (selectedTab) {
                // Path A: Direct URL Import (SourceAdapterRegistry)
                0 -> {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "المسار A — استيراد رابط مباشر (SourceAdapter)",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    "يستخرج صور الصفحات بالترتيب من وسوم og:image وimg (بما فيها data-src وsrcset) للمصادر المسموح بها. عند وجود HTTP 403 أو Cloudflare أو CAPTCHA يُعيد خطأ BLOCKED_BY_SOURCE صريحًا دون تجاوز.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = directUrl,
                                    onValueChange = { directUrl = it },
                                    label = { Text("رابط الفصل أو الصفحة (https://...)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("direct_url_input")
                                )
                                OutlinedTextField(
                                    value = chapterTitle,
                                    onValueChange = { chapterTitle = it },
                                    label = { Text("اسم الفصل (اختياري)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = { onImportDirectUrl(directUrl, chapterTitle) },
                                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("direct_url_import_button")
                                ) {
                                    Text("تحليل واستيراد من الرابط")
                                }
                            }
                        }
                    }

                    if (uiNotice is UiNotice.Error && uiNotice.suggestPathB) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "رمز الخطأ: ${uiNotice.code}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    Text(uiNotice.message, style = MaterialTheme.typography.bodyMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { onOpenBrowserWithUrl(directUrl) }) {
                                            Text("فتح في المتصفح (المسار B)")
                                        }
                                        OutlinedButton(onClick = { selectedTab = 2 }) {
                                            Text("رفع ملفات (المسار C)")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (sourceLogs.isNotEmpty()) {
                        item {
                            Text("سجل SourceAdapter", style = MaterialTheme.typography.titleMedium)
                        }
                        items(sourceLogs, key = { it.id }) { log ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                                    Text(
                                        text = "${log.status.uppercase()} (HTTP ${log.httpCode}) · ${log.adapterName}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (log.status == "success") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                                    )
                                    Text(log.url, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                    Text(log.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                // Path B: Browser Import & Android Share Target (NO source_fetch)
                1 -> {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.secondary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "المسار B — استقبال المتصفح وShare Target",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    "هذا المسار لا يستدعي source_fetch مطلقًا. يستقبل الصور مباشرة من متصفح Android WebView المدمج عبر JSBridge أو عبر مشاركة الصور من Chrome إلى تطبيق «فقاعة» (Share Target).",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "عداد الصور المستلمة (Received Images Counter): $receivedBrowserImagesCount",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("received_images_counter")
                                )
                                Text(
                                    text = "صور جاهزة في قائمة الانتظار: $stagedBrowserImagesCount",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { onOpenBrowserWithUrl("https://google.com") },
                                        modifier = Modifier.weight(1f).height(48.dp)
                                    ) {
                                        Text("فتح المتصفح المدمج")
                                    }
                                    if (stagedBrowserImagesCount > 0) {
                                        OutlinedButton(
                                            onClick = onCommitStagedBrowserImages,
                                            modifier = Modifier.weight(1f).height(48.dp)
                                        ) {
                                            Text("حفظ الصور كفصل")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Share, null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("ملاحظة عن Browser Companion على Android", style = MaterialTheme.typography.titleSmall)
                                }
                                Text(
                                    "إضافات Chrome غير مدعومة على Android، والبديل الرسمي الفعّال هو Android Share Target: في Chrome أو أي متصفح خارجي اضغط مطولًا على صورة الصفحة ← مشاركة (Share) ← اختر «فقاعة (Faqqa)».",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                // Path C: File Import (JPG/PNG/WEBP/PDF/ZIP/CBZ)
                2 -> {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.FolderZip, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "المسار C — استيراد ملفات (صور / PDF / ZIP / CBZ)",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    "يفك أرشيفات ZIP/CBZ بأمان (مع منع Zip Slip، التحقق من صيغة الصور، والترتيب الطبيعي للصفحات)، ويستخرج صفحات PDF بدقة عالية.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = chapterTitle,
                                    onValueChange = { chapterTitle = it },
                                    label = { Text("اسم الفصل المستورد (اختياري)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("pick_photos_button")
                                ) {
                                    Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("اختيار صور (JPG / PNG / WEBP)")
                                }
                                OutlinedButton(
                                    onClick = {
                                        documentPickerLauncher.launch(
                                            arrayOf(
                                                "application/pdf",
                                                "application/zip",
                                                "application/x-zip-compressed",
                                                "application/vnd.comicbook+zip",
                                                "application/octet-stream",
                                                "image/*"
                                            )
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("pick_archive_pdf_button")
                                ) {
                                    Icon(Icons.Default.FolderZip, null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("استيراد PDF / ZIP / CBZ")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
