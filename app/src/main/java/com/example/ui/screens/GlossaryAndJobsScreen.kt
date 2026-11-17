package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.firestore.ComicProject
import com.example.data.firestore.GlossaryEntry
import com.example.data.firestore.TranslationJobRecord
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun GlossaryAndJobsScreen(
    activeProject: ComicProject?,
    glossary: List<GlossaryEntry>,
    jobs: List<TranslationJobRecord>,
    onAddGlossary: (String, String, String, String) -> Unit,
    onDeleteGlossary: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var sourceTerm by remember { mutableStateOf("") }
    var targetTerm by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("character") }
    var notes by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("القاموس وذاكرة السياق (${glossary.size})") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("سجل الترجمة (${jobs.size})") }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (selectedTab == 0) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = activeProject?.let { "قاموس مشروع: ${it.title}" } ?: "اختر مشروعًا أولًا",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            OutlinedTextField(
                                value = sourceTerm,
                                onValueChange = { sourceTerm = it },
                                label = { Text("المصطلح / الاسم الأصلي") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("glossary_source_input")
                            )
                            OutlinedTextField(
                                value = targetTerm,
                                onValueChange = { targetTerm = it },
                                label = { Text("الترجمة العربية المعتمدة") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("glossary_target_input")
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    "character" to "شخصية",
                                    "place" to "مكان",
                                    "term" to "مصطلح",
                                    "speech_style" to "أسلوب"
                                ).forEach { (k, label) ->
                                    FilterChip(
                                        selected = category == k,
                                        onClick = { category = k },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = notes,
                                onValueChange = { notes = it },
                                label = { Text("ملاحظة علاقة / نبرة كلام") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (sourceTerm.isNotBlank() && targetTerm.isNotBlank()) {
                                            onAddGlossary(sourceTerm, targetTerm, category, notes)
                                            sourceTerm = ""
                                            targetTerm = ""
                                            notes = ""
                                        }
                                    },
                                    enabled = activeProject != null,
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("حفظ في القاموس")
                                }
                                OutlinedButton(
                                    onClick = {
                                        val arr = JSONArray()
                                        glossary.forEach { g ->
                                            arr.put(JSONObject().apply {
                                                put("sourceTerm", g.sourceTerm)
                                                put("targetTerm", g.targetTerm)
                                                put("category", g.category)
                                                put("notes", g.notes)
                                            })
                                        }
                                        clipboard.setText(AnnotatedString(arr.toString(2)))
                                    },
                                    enabled = glossary.isNotEmpty(),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("نسخ JSON")
                                }
                            }
                        }
                    }
                }

                items(glossary, key = { it.id }) { entry ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${entry.sourceTerm}  ←  ${entry.targetTerm}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${entry.category} ${if (entry.notes.isNotBlank()) "· ${entry.notes}" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onDeleteGlossary(entry.id) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "حذف المصطلح")
                            }
                        }
                    }
                }
            } else {
                if (jobs.isEmpty()) {
                    item {
                        Text(
                            "لا توجد مهام ترجمة مسجلة بعد.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(jobs, key = { it.id }) { job ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(
                                    text = "المزود: ${job.providerId} · الحالة: ${job.status}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = when (job.status) {
                                        "completed" -> MaterialTheme.colorScheme.tertiary
                                        "failed" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )
                                Text(
                                    text = "الصفحات: ${job.processedPages} / ${job.totalPages}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (job.errorMessage.isNotBlank()) {
                                    Text(
                                        text = job.errorMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
