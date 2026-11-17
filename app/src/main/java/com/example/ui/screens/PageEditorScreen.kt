package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.firestore.ChapterRecord
import com.example.data.local.LocalPageEntity
import com.example.data.local.LocalTextRegionEntity
import com.example.engine.export.ExportFormat
import java.io.File

enum class EditorViewMode(val label: String) {
    TRANSLATED("مترجم"),
    ORIGINAL("الأصلي"),
    COMPARE("مقارنة")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(
    chapter: ChapterRecord?,
    pages: List<LocalPageEntity>,
    activePage: LocalPageEntity?,
    regions: List<LocalTextRegionEntity>,
    onSelectPage: (String) -> Unit,
    onAnalyzePage: (LocalPageEntity) -> Unit,
    onAnalyzeChapter: (ChapterRecord) -> Unit,
    onUpdateRegion: (LocalTextRegionEntity) -> Unit,
    onRetranslateRegion: (LocalTextRegionEntity) -> Unit,
    onAddManualRegion: (Float, Float, Float, Float) -> Unit,
    onApplyBrushStroke: (List<Pair<Float, Float>>) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExportChapter: (ExportFormat, Uri) -> Unit
) {
    var viewMode by remember { mutableStateOf(EditorViewMode.TRANSLATED) }
    var showSelectionOverlay by remember { mutableStateOf(true) }
    var isBrushMode by remember { mutableStateOf(false) }
    var selectedRegion by remember { mutableStateOf<LocalTextRegionEntity?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingExportFormat by remember { mutableStateOf(ExportFormat.CBZ) }

    var scale by remember(activePage?.id) { mutableFloatStateOf(1f) }
    var offset by remember(activePage?.id) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        offset += panChange
    }

    val brushPoints = remember { mutableStateListOf<Pair<Float, Float>>() }

    val createExportDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            onExportChapter(pendingExportFormat, uri)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Action Bar
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chapter?.title ?: "محرر الصفحات",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Row {
                        IconButton(onClick = onUndo, modifier = Modifier.testTag("editor_undo")) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "تراجع")
                        }
                        IconButton(onClick = onRedo, modifier = Modifier.testTag("editor_redo")) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "إعادة")
                        }
                        IconButton(
                            onClick = { showExportDialog = true },
                            enabled = pages.isNotEmpty(),
                            modifier = Modifier.testTag("editor_export_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "تصدير")
                        }
                    }
                }

                // Mode toggles + Translate actions
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(EditorViewMode.entries.size) { idx ->
                        val m = EditorViewMode.entries[idx]
                        FilterChip(
                            selected = viewMode == m,
                            onClick = { viewMode = m },
                            label = { Text(m.label) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = isBrushMode,
                            onClick = { isBrushMode = !isBrushMode },
                            leadingIcon = { Icon(Icons.Default.Brush, null, modifier = Modifier.size(16.dp)) },
                            label = { Text("فرشاة تبييض") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = showSelectionOverlay,
                            onClick = { showSelectionOverlay = !showSelectionOverlay },
                            leadingIcon = { Icon(Icons.Default.Visibility, null, modifier = Modifier.size(16.dp)) },
                            label = { Text("تحديد الفقاعات") }
                        )
                    }
                    item {
                        OutlinedButton(
                            onClick = { onAddManualRegion(0.35f, 0.35f, 0.65f, 0.50f) },
                            enabled = activePage != null
                        ) {
                            Icon(Icons.Default.AddBox, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("+ فقاعة")
                        }
                    }
                }

                if (activePage != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onAnalyzePage(activePage) },
                            modifier = Modifier.weight(1f).height(44.dp).testTag("analyze_page_button")
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("ترجمة هذه الصفحة")
                        }
                        if (chapter != null && pages.size > 1) {
                            OutlinedButton(
                                onClick = { onAnalyzeChapter(chapter) },
                                modifier = Modifier.height(44.dp)
                            ) {
                                Text("الكل (${pages.size})")
                            }
                        }
                    }
                }
            }
        }

        // Main Canvas Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF0A0C1B)),
            contentAlignment = Alignment.Center
        ) {
            if (activePage == null) {
                Text(
                    text = "لا توجد صفحة محددة. استورد صورًا أو فصلًا للبدء.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val imgPath = when (viewMode) {
                    EditorViewMode.ORIGINAL -> activePage.originalImagePath
                    EditorViewMode.TRANSLATED -> activePage.renderedImagePath.ifBlank { activePage.originalImagePath }
                    EditorViewMode.COMPARE -> activePage.originalImagePath
                }

                val aspect = if (activePage.width > 0 && activePage.height > 0) {
                    activePage.width.toFloat() / activePage.height.toFloat()
                } else 0.7f

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(state = transformState, enabled = !isBrushMode),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                    ) {
                        // Clean Rendered or Original Image (NEVER contains burned-in debug boxes!)
                        val fileKey = "${imgPath}_${activePage.updatedAtMillis}"
                        AsyncImage(
                            model = File(imgPath),
                            contentDescription = "صفحة الكوميكس",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().testTag("editor_page_image")
                        )

                        if (viewMode == EditorViewMode.COMPARE && activePage.renderedImagePath.isNotBlank()) {
                            // Side-by-side split comparison: right half translated, left half original
                            Row(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f).fillMaxSize())
                                AsyncImage(
                                    model = File(activePage.renderedImagePath),
                                    contentDescription = "النسخة المترجمة للمقارنة",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).fillMaxSize()
                                )
                            }
                        }

                        // Interactive Editor Overlay (ONLY in editor when showSelectionOverlay == true)
                        if (viewMode == EditorViewMode.TRANSLATED && showSelectionOverlay && !isBrushMode) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(regions) {
                                        detectTapGestures { tapOffset ->
                                            val nx = tapOffset.x / size.width
                                            val ny = tapOffset.y / size.height
                                            val hit = regions.firstOrNull { r ->
                                                nx in r.leftNorm..r.rightNorm && ny in r.topNorm..r.bottomNorm
                                            }
                                            if (hit != null) {
                                                selectedRegion = hit
                                            }
                                        }
                                    }
                            ) {
                                regions.forEach { reg ->
                                    val isSelected = selectedRegion?.id == reg.id
                                    val strokeColor = when {
                                        isSelected -> Color(0xFFF5A623)
                                        reg.isApproved -> Color(0xFF10B981)
                                        else -> Color(0xFF22D3EE).copy(alpha = 0.55f)
                                    }
                                    drawRect(
                                        color = strokeColor,
                                        topLeft = Offset(reg.leftNorm * size.width, reg.topNorm * size.height),
                                        size = Size(
                                            (reg.rightNorm - reg.leftNorm) * size.width,
                                            (reg.bottomNorm - reg.topNorm) * size.height
                                        ),
                                        style = Stroke(width = if (isSelected) 4f else 2f)
                                    )
                                }
                            }
                        }

                        // Manual Brush Touch-up Layer
                        if (isBrushMode) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(activePage.id) {
                                        detectDragGestures(
                                            onDragStart = { start ->
                                                brushPoints.clear()
                                                brushPoints.add(start.x / size.width to start.y / size.height)
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                brushPoints.add(
                                                    (change.position.x / size.width).coerceIn(0f, 1f) to
                                                        (change.position.y / size.height).coerceIn(0f, 1f)
                                                )
                                            },
                                            onDragEnd = {
                                                if (brushPoints.isNotEmpty()) {
                                                    onApplyBrushStroke(brushPoints.toList())
                                                    brushPoints.clear()
                                                }
                                            }
                                        )
                                    }
                            ) {
                                if (brushPoints.size > 1) {
                                    val path = Path()
                                    brushPoints.forEachIndexed { i, (nx, ny) ->
                                        val px = nx * size.width
                                        val py = ny * size.height
                                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                                    }
                                    drawPath(path, color = Color.White.copy(alpha = 0.85f), style = Stroke(width = 18f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom Page Thumbnails Strip
        if (pages.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(pages, key = { _, p -> p.id }) { idx, pg ->
                        val isCurrent = activePage?.id == pg.id
                        Box(
                            modifier = Modifier
                                .size(width = 56.dp, height = 74.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (isCurrent) 2.dp else 1.dp,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelectPage(pg.id) }
                        ) {
                            AsyncImage(
                                model = File(pg.renderedImagePath.ifBlank { pg.originalImagePath }),
                                contentDescription = "صفحة ${idx + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                color = Color.Black.copy(alpha = 0.65f),
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            ) {
                                Text(
                                    text = "${idx + 1} ${if (pg.status == "translated") "✓" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Bubble Edit Sheet
    selectedRegion?.let { reg ->
        var sourceText by remember(reg.id) { mutableStateOf(reg.sourceText) }
        var translatedText by remember(reg.id) { mutableStateOf(reg.translatedText) }
        var fontFamily by remember(reg.id) { mutableStateOf(reg.fontFamily) }
        var fontSizeSp by remember(reg.id) { mutableFloatStateOf(reg.fontSizeSp) }
        var alignment by remember(reg.id) { mutableStateOf(reg.alignment) }
        var maskShrinkPx by remember(reg.id) { mutableFloatStateOf(reg.maskShrinkPx.toFloat()) }
        var isApproved by remember(reg.id) { mutableStateOf(reg.isApproved) }

        ModalBottomSheet(onDismissRequest = { selectedRegion = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("تحرير الفقاعة (${reg.regionType})", style = MaterialTheme.typography.titleLarge)

                OutlinedTextField(
                    value = sourceText,
                    onValueChange = { sourceText = it },
                    label = { Text("النص الأصلي (OCR)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = translatedText,
                    onValueChange = { translatedText = it },
                    label = { Text("الترجمة العربية (RTL)") },
                    modifier = Modifier.fillMaxWidth().testTag("bubble_translated_input")
                )

                Text("الخط العربي", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("CAIRO" to "Cairo", "RUBIK" to "Rubik", "NOTO_NASKH" to "Noto Naskh").forEach { (k, label) ->
                        FilterChip(
                            selected = fontFamily == k,
                            onClick = { fontFamily = k },
                            label = { Text(label) }
                        )
                    }
                }

                Text("المحاذاة", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("center" to "توسيط", "right" to "يمين", "left" to "يسار").forEach { (k, label) ->
                        FilterChip(
                            selected = alignment == k,
                            onClick = { alignment = k },
                            label = { Text(label) }
                        )
                    }
                }

                Text("حجم الخط: ${fontSizeSp.toInt()}sp", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = fontSizeSp,
                    onValueChange = { fontSizeSp = it },
                    valueRange = 10f..32f
                )

                Text("تقليص القناع لحماية الإطار الأسود: ${maskShrinkPx.toInt()}px", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = maskShrinkPx,
                    onValueChange = { maskShrinkPx = it },
                    valueRange = 1f..5f,
                    steps = 3
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("اعتماد الفقاعة")
                    Switch(checked = isApproved, onCheckedChange = { isApproved = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onRetranslateRegion(reg.copy(sourceText = sourceText))
                            selectedRegion = null
                        },
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("إعادة ترجمة")
                    }
                    Button(
                        onClick = {
                            onUpdateRegion(
                                reg.copy(
                                    sourceText = sourceText,
                                    translatedText = translatedText,
                                    fontFamily = fontFamily,
                                    fontSizeSp = fontSizeSp,
                                    alignment = alignment,
                                    maskShrinkPx = maskShrinkPx.toInt(),
                                    isApproved = isApproved
                                )
                            )
                            selectedRegion = null
                        },
                        modifier = Modifier.weight(1f).height(48.dp).testTag("save_bubble_button")
                    ) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("تطبيق ورسم")
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("تصدير الفصل النظيف") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "التصدير خالٍ تمامًا من أي مربعات تحديد أو أرقام Debug.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ExportFormat.entries.forEach { fmt ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pendingExportFormat = fmt
                                    showExportDialog = false
                                    val baseName = (chapter?.title ?: "faqqa_export").replace(" ", "_")
                                    createExportDocLauncher.launch("$baseName.${fmt.extension}")
                                },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text(
                                text = fmt.label,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("إغلاق") }
            }
        )
    }
}
