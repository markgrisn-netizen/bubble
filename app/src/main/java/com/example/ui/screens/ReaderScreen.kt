package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.firestore.ChapterRecord
import com.example.data.firestore.ComicProject
import com.example.data.local.LocalPageEntity
import com.example.data.local.LocalTextRegionEntity
import java.io.File

@Composable
fun ReaderScreen(
    project: ComicProject?,
    chapter: ChapterRecord?,
    pages: List<LocalPageEntity>,
    regionsForActivePage: List<LocalTextRegionEntity>,
    onPageFocused: (String) -> Unit,
    onSaveLastReadIndex: (Int) -> Unit
) {
    var isVerticalWebtoon by remember(project?.id) {
        mutableStateOf((project?.readerMode ?: "vertical_webtoon") == "vertical_webtoon")
    }
    var isRtl by remember(project?.id) {
        mutableStateOf((project?.readingDirection ?: "rtl") == "rtl")
    }
    var showOriginal by remember { mutableStateOf(false) }
    var peekPage by remember { mutableStateOf<LocalPageEntity?>(null) }

    val initialIdx = (chapter?.lastReadPageIndex?.toInt() ?: 0)
        .coerceIn(0, (pages.size - 1).coerceAtLeast(0))

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Clean Reader Controls Bar
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = !showOriginal,
                    onClick = { showOriginal = !showOriginal },
                    leadingIcon = { Icon(Icons.Default.Translate, null, modifier = Modifier.size(16.dp)) },
                    label = { Text(if (showOriginal) "الأصلي" else "المترجم") },
                    modifier = Modifier.testTag("reader_toggle_original")
                )
                FilterChip(
                    selected = isVerticalWebtoon,
                    onClick = { isVerticalWebtoon = !isVerticalWebtoon },
                    leadingIcon = {
                        Icon(
                            if (isVerticalWebtoon) Icons.Default.ViewStream else Icons.Default.ViewCarousel,
                            null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text(if (isVerticalWebtoon) "عمودي" else "أفقي") }
                )
                FilterChip(
                    selected = isRtl,
                    onClick = { isRtl = !isRtl },
                    leadingIcon = { Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(16.dp)) },
                    label = { Text(if (isRtl) "RTL" else "LTR") }
                )
                Spacer(Modifier.weight(1f))
                AssistChip(
                    onClick = {},
                    leadingIcon = { Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(15.dp)) },
                    label = { Text("محلي") }
                )
            }
        }

        if (pages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "لا توجد صفحات في هذا الفصل للقراءة.",
                    color = Color.White
                )
            }
        } else if (isVerticalWebtoon) {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIdx)
            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex }
                    .collect { idx ->
                        onSaveLastReadIndex(idx)
                        pages.getOrNull(idx)?.let { onPageFocused(it.id) }
                    }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("reader_vertical_list"),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(pages, key = { _, p -> p.id }) { idx, pg ->
                    val path = if (showOriginal) pg.originalImagePath
                    else pg.renderedImagePath.ifBlank { pg.originalImagePath }
                    // ZERO debug rectangles or numbers rendered here!
                    AsyncImage(
                        model = File(path),
                        contentDescription = "صفحة ${idx + 1}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPageFocused(pg.id)
                                peekPage = pg
                            }
                    )
                }
            }
        } else {
            val pagerState = rememberPagerState(initialPage = initialIdx) { pages.size }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }
                    .collect { idx ->
                        onSaveLastReadIndex(idx)
                        pages.getOrNull(idx)?.let { onPageFocused(it.id) }
                    }
            }
            val layoutDir = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().testTag("reader_horizontal_pager")
                ) { idx ->
                    val pg = pages[idx]
                    val path = if (showOriginal) pg.originalImagePath
                    else pg.renderedImagePath.ifBlank { pg.originalImagePath }
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = File(path),
                            contentDescription = "صفحة ${idx + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    onPageFocused(pg.id)
                                    peekPage = pg
                                }
                        )
                    }
                }
            }
        }
    }

    peekPage?.let { pg ->
        AlertDialog(
            onDismissRequest = { peekPage = null },
            title = { Text("النص الأصلي لفقاعات الصفحة ${pg.pageOrder + 1}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (regionsForActivePage.isEmpty()) {
                        Text("اضغط مطولًا أو افتح المحرر لتحليل هذه الصفحة.")
                    } else {
                        regionsForActivePage.forEach { reg ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = reg.sourceText.ifBlank { "(بدون نص أصلي)" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = reg.translatedText,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { peekPage = null }) { Text("إغلاق") }
            }
        )
    }
}
