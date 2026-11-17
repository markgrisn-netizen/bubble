package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.firestore.ChapterRecord
import com.example.data.firestore.ComicProject
import com.example.data.firestore.SfxMode
import com.example.data.firestore.TranslationStyle

@Composable
fun HomeLibraryScreen(
    projects: List<ComicProject>,
    activeProject: ComicProject?,
    chapters: List<ChapterRecord>,
    receivedBrowserImagesCount: Int,
    onSelectProject: (String) -> Unit,
    onCreateProject: (String, String, TranslationStyle, String, String, SfxMode, String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onOpenImport: (Int) -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenEditor: (String) -> Unit,
    onOpenReader: (String) -> Unit,
    onOpenGlossary: () -> Unit,
    onOpenAiProviders: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var showNewProjectDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewProjectDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("new_project_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "مشروع جديد")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Studio Header
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(22.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_hero_banner),
                        contentDescription = "استوديو فقاعة",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.45f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "فقاعة · PanelTranslate AI",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "استورد الفصول من المتصفح أو الملفات، نظّف الفقاعات بدقة، واقرأ بالعربية.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = onOpenBrowser,
                                label = { Text("صور المتصفح: $receivedBrowserImagesCount") },
                                leadingIcon = {
                                    Icon(Icons.Default.Language, null, modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                    }
                }
            }

            // Quick Actions Grid
            item {
                Text(
                    text = "إجراءات سريعة",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickActionCard(
                            icon = Icons.Default.Add,
                            label = "مشروع جديد",
                            modifier = Modifier.weight(1f).testTag("action_new_project"),
                            onClick = { showNewProjectDialog = true }
                        )
                        QuickActionCard(
                            icon = Icons.Default.Link,
                            label = "استيراد رابط",
                            modifier = Modifier.weight(1f).testTag("action_import_url"),
                            onClick = { onOpenImport(0) }
                        )
                        QuickActionCard(
                            icon = Icons.Default.UploadFile,
                            label = "رفع صور/فصل",
                            modifier = Modifier.weight(1f).testTag("action_import_files"),
                            onClick = { onOpenImport(2) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickActionCard(
                            icon = Icons.Default.Language,
                            label = "المتصفح",
                            modifier = Modifier.weight(1f).testTag("action_browser"),
                            onClick = onOpenBrowser
                        )
                        QuickActionCard(
                            icon = Icons.Default.Book,
                            label = "القاموس",
                            modifier = Modifier.weight(1f).testTag("action_glossary"),
                            onClick = onOpenGlossary
                        )
                        QuickActionCard(
                            icon = Icons.Default.Psychology,
                            label = "إعدادات AI",
                            modifier = Modifier.weight(1f).testTag("action_ai_providers"),
                            onClick = onOpenAiProviders
                        )
                        QuickActionCard(
                            icon = Icons.Default.Settings,
                            label = "الإعدادات",
                            modifier = Modifier.weight(1f).testTag("action_settings"),
                            onClick = onOpenSettings
                        )
                    }
                }
            }

            // My Projects
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "مكتبتي والمشاريع (${projects.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { onOpenImport(1) }) {
                        Icon(Icons.Default.History, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("سجل الترجمة")
                    }
                }
            }

            if (projects.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(42.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text("مكتبتك فارغة حاليًا", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "أنشئ مشروعًا جديدًا أو استورد صور مانغا/PDF/CBZ أو التقط صفحات من المتصفح الحقيقي.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = { showNewProjectDialog = true }) {
                                Text("ابدأ مشروعك الأول")
                            }
                        }
                    }
                }
            } else {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(projects, key = { it.id }) { proj ->
                            val selected = activeProject?.id == proj.id
                            Card(
                                modifier = Modifier
                                    .width(220.dp)
                                    .clickable { onSelectProject(proj.id) }
                                    .testTag("project_card_${proj.id}"),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                ),
                                border = if (selected) {
                                    androidx.compose.foundation.BorderStroke(
                                        1.5.dp,
                                        MaterialTheme.colorScheme.primary
                                    )
                                } else null
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = proj.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = { onDeleteProject(proj.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "حذف المشروع",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${proj.sourceLang} ← ${proj.targetLang} · ${proj.readingDirection.uppercase()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Text(
                                        text = "${proj.pageCount} صفحة · ${TranslationStyle.fromWire(proj.translationStyle).arabicLabel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Chapters of Active Project
            if (activeProject != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "فصول «${activeProject.title}» (${chapters.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedButton(onClick = { onOpenImport(2) }) {
                            Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("إضافة فصل")
                        }
                    }
                }

                if (chapters.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "لا توجد فصول في هذا المشروع بعد. استورد فصلًا من الرابط المباشر أو المتصفح أو الملفات.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(chapters, key = { it.id }) { chap ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = chap.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${chap.pageCount} صفحة · المصدر: ${chap.importSource} · جاهز دون إنترنت",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { onOpenEditor(chap.id) },
                                        modifier = Modifier.testTag("open_editor_${chap.id}")
                                    ) {
                                        Icon(Icons.Default.EditNote, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("تحرير")
                                    }
                                    Button(
                                        onClick = { onOpenReader(chap.id) },
                                        modifier = Modifier.testTag("open_reader_${chap.id}")
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("قراءة")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewProjectDialog) {
        NewProjectDialog(
            onDismiss = { showNewProjectDialog = false },
            onCreate = { title, srcLang, style, dir, mode, sfx, notes ->
                onCreateProject(title, srcLang, style, dir, mode, sfx, notes)
                showNewProjectDialog = false
            }
        )
    }
}

@Composable
private fun QuickActionCard(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(78.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, TranslationStyle, String, String, SfxMode, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var sourceLang by remember { mutableStateOf("AUTO") }
    var readingDirection by remember { mutableStateOf("rtl") }
    var readerMode by remember { mutableStateOf("vertical_webtoon") }
    var style by remember { mutableStateOf(TranslationStyle.NATURAL) }
    var sfxMode by remember { mutableStateOf(SfxMode.SIDE_NOTE) }
    var contextNotes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إنشاء مشروع ترجمة جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("اسم العمل (مانغا / مانهوا / كوميكس)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_project_title_input")
                )
                Text("اللغة المصدر", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("AUTO", "JA", "KO", "ZH-S", "EN", "FR")) { lang ->
                        FilterChip(
                            selected = sourceLang == lang,
                            onClick = { sourceLang = lang },
                            label = { Text(lang) }
                        )
                    }
                }
                Text("اتجاه القراءة ونمط العرض", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = readingDirection == "rtl",
                        onClick = { readingDirection = "rtl" },
                        label = { Text("RTL (مانغا)") }
                    )
                    FilterChip(
                        selected = readingDirection == "ltr",
                        onClick = { readingDirection = "ltr" },
                        label = { Text("LTR (كوميكس)") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = readerMode == "vertical_webtoon",
                        onClick = { readerMode = "vertical_webtoon" },
                        label = { Text("عمودي Webtoon") }
                    )
                    FilterChip(
                        selected = readerMode == "horizontal_manga",
                        onClick = { readerMode = "horizontal_manga" },
                        label = { Text("أفقي صفحات") }
                    )
                }
                Text("أسلوب الترجمة", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(TranslationStyle.entries) { st ->
                        FilterChip(
                            selected = style == st,
                            onClick = { style = st },
                            label = { Text(st.arabicLabel) }
                        )
                    }
                }
                OutlinedTextField(
                    value = contextNotes,
                    onValueChange = { contextNotes = it },
                    label = { Text("ملاحظات السياق ونبرة الشخصيات") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(title, sourceLang, style, readingDirection, readerMode, sfxMode, contextNotes)
                },
                modifier = Modifier.testTag("confirm_create_project_button")
            ) {
                Text("إنشاء")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}
