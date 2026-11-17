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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.firestore.ComicProject
import com.example.data.firestore.SfxMode
import com.example.data.local.LocalProviderConfigEntity

@Composable
fun AiProvidersScreen(
    providers: List<LocalProviderConfigEntity>,
    onSaveProvider: (LocalProviderConfigEntity, String?, String, String, Boolean) -> Unit,
    onTestConnection: (LocalProviderConfigEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "مزودو الذكاء الاصطناعي (مشفّر بـ AES-256-GCM)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "يعمل Google Gemini (Firebase AI Logic) مباشرة بدون مفتاح API. يمكنك أيضًا ضبط DeepSeek أو Groq أو Ollama (محلي) أو OpenAI أو Anthropic مع اختبار اتصال حقيقي.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        items(providers, key = { it.providerId }) { provider ->
            var baseUrl by remember(provider.providerId, provider.baseUrl) { mutableStateOf(provider.baseUrl) }
            var modelName by remember(provider.providerId, provider.modelName) { mutableStateOf(provider.modelName) }
            var apiKeyInput by remember(provider.providerId) { mutableStateOf("") }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                border = if (provider.isSelected) {
                    androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                } else null
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = provider.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "الحالة: ${provider.lastTestStatus} · ${if (provider.encryptedApiKey.isNotBlank()) "مفتاح محفوظ مشفّر" else if (!provider.requiresApiKey) "لا يتطلب مفتاحًا" else "بلا مفتاح"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = when (provider.lastTestStatus) {
                                    "success", "ready" -> MaterialTheme.colorScheme.tertiary
                                    "failed" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (provider.isSelected) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "المزود النشط",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (provider.providerId != "firebase_ai") {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Endpoint Base URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = modelName,
                            onValueChange = { modelName = it },
                            label = { Text("اسم النموذج") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (provider.requiresApiKey) {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("أدخل مفتاح API جديد (يُحفظ مشفّرًا)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("api_key_${provider.providerId}")
                        )
                    }

                    if (provider.providerId == "ollama") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "لتشغيل Ollama محليًا (Termux / حاسوب): نفّذ\nollama pull qwen2.5 && ollama serve",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    if (provider.lastTestMessage.isNotBlank()) {
                        Text(
                            text = provider.lastTestMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                onSaveProvider(provider, apiKeyInput.ifBlank { null }, baseUrl, modelName, false)
                                apiKeyInput = ""
                            },
                            modifier = Modifier.weight(1f).height(46.dp)
                        ) {
                            Text("حفظ")
                        }
                        OutlinedButton(
                            onClick = {
                                val updated = provider.copy(
                                    baseUrl = baseUrl,
                                    modelName = modelName,
                                    encryptedApiKey = if (apiKeyInput.isNotBlank()) {
                                        com.example.security.CryptoKeyStore.encrypt(apiKeyInput.trim())
                                    } else provider.encryptedApiKey
                                )
                                onSaveProvider(provider, apiKeyInput.ifBlank { null }, baseUrl, modelName, provider.isSelected)
                                onTestConnection(updated)
                            },
                            modifier = Modifier.weight(1f).height(46.dp).testTag("test_conn_${provider.providerId}")
                        ) {
                            Icon(Icons.Default.NetworkCheck, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Test Connection")
                        }
                        Button(
                            onClick = {
                                onSaveProvider(provider, apiKeyInput.ifBlank { null }, baseUrl, modelName, true)
                                apiKeyInput = ""
                            },
                            enabled = !provider.isSelected,
                            modifier = Modifier.height(46.dp)
                        ) {
                            Text(if (provider.isSelected) "نشط" else "تفعيل")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    userEmail: String,
    activeProject: ComicProject?,
    isDarkTheme: Boolean,
    isArabicUi: Boolean,
    onToggleTheme: () -> Unit,
    onToggleLanguage: () -> Unit,
    onUpdateProject: (ComicProject) -> Unit,
    onSignOut: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("تفضيلات الواجهة والحساب", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("الحساب النشط: $userEmail", style = MaterialTheme.typography.bodyMedium)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DarkMode, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("الوضع الداكن (Midnight Ink)")
                        }
                        Switch(checked = isDarkTheme, onCheckedChange = { onToggleTheme() })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("اتجاه الواجهة العربي (RTL)")
                        Switch(checked = isArabicUi, onCheckedChange = { onToggleLanguage() })
                    }

                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("sign_out_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("تسجيل الخروج")
                    }
                }
            }
        }

        if (activeProject != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("سلوك المعالجة لمشروع «${activeProject.title}»", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("وضع المؤثرات الصوتية (SFX)", style = MaterialTheme.typography.labelLarge)
                        SfxMode.entries.forEach { mode ->
                            FilterChip(
                                selected = activeProject.sfxMode == mode.wireValue,
                                onClick = { onUpdateProject(activeProject.copy(sfxMode = mode.wireValue)) },
                                label = { Text(mode.arabicLabel) }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("ترجمة ومسح نصوص الخلفية الزخرفية")
                            Switch(
                                checked = activeProject.cleanBackgroundText,
                                onCheckedChange = {
                                    onUpdateProject(activeProject.copy(cleanBackgroundText = it))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
