package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_pages")
data class LocalPageEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val projectId: String,
    val chapterId: String,
    val pageOrder: Int,
    val originalImagePath: String,
    val cleanedImagePath: String = "",
    val renderedImagePath: String = "",
    val brushMaskPath: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val status: String = "imported", // imported, analyzed, cleaned, translated, error
    val detectedLang: String = "AUTO",
    val errorMessage: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_text_regions")
data class LocalTextRegionEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val orderIndex: Int,
    val regionType: String = "speech", // speech, narration, sfx, background_text
    val leftNorm: Float,
    val topNorm: Float,
    val rightNorm: Float,
    val bottomNorm: Float,
    val polygonJson: String = "[]",
    val sourceText: String = "",
    val detectedLang: String = "EN",
    val translatedText: String = "",
    val fontFamily: String = "CAIRO", // CAIRO, RUBIK, NOTO_NASKH
    val fontSizeSp: Float = 16f,
    val textColorArgb: Int = 0xFF111827.toInt(),
    val bgColorArgb: Int = 0xFFFFFFFF.toInt(),
    val alignment: String = "center", // center, right, left
    val isApproved: Boolean = false,
    val confidence: Float = 0.9f,
    val maskShrinkPx: Int = 3
)

@Entity(tableName = "local_provider_configs")
data class LocalProviderConfigEntity(
    @PrimaryKey val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val modelName: String,
    val encryptedApiKey: String = "",
    val requiresApiKey: Boolean = true,
    val isSelected: Boolean = false,
    val lastTestStatus: String = "untested", // untested, success, failed
    val lastTestMessage: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_browser_history")
data class LocalBrowserHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAtMillis: Long = System.currentTimeMillis()
)
