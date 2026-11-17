package com.example.engine.importpath

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.TimeUnit

sealed interface DirectImportResult {
    data class Success(
        val adapterName: String,
        val chapterTitle: String,
        val imageUrls: List<String>,
        val httpCode: Int
    ) : DirectImportResult

    data class BlockedBySource(
        val code: String = "BLOCKED_BY_SOURCE",
        val httpCode: Int,
        val adapterName: String,
        val message: String
    ) : DirectImportResult

    data class Error(
        val code: String,
        val httpCode: Int,
        val adapterName: String,
        val message: String
    ) : DirectImportResult
}

interface SourceAdapter {
    val name: String
    fun canHandle(url: String): Boolean
    fun parsePages(html: String, baseUrl: String): List<String>
    fun orderPages(urls: List<String>): List<String> = urls.distinct()
}

class MangaDomSourceAdapter : SourceAdapter {
    override val name: String = "MangaDomLazyImageAdapter"
    override fun canHandle(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    override fun parsePages(html: String, baseUrl: String): List<String> {
        val doc = Jsoup.parse(html, baseUrl)
        val candidates = mutableListOf<String>()

        // 1. Check standard comic/manga reader containers first
        val imgs = doc.select("img")
        for (img in imgs) {
            val rawSrc = sequenceOf(
                img.attr("data-src"),
                img.attr("data-lazy-src"),
                img.attr("data-original"),
                extractFirstSrcsetUrl(img.attr("srcset")),
                img.attr("src")
            ).firstOrNull { it.isNotBlank() && !it.startsWith("data:image/svg") }
                ?: continue

            val absUrl = resolveUrl(baseUrl, rawSrc.trim())
            if (isLikelyComicPageUrl(absUrl)) {
                candidates.add(absUrl)
            }
        }

        // 2. Fallback to og:image meta tags if no reader <img> tags found
        if (candidates.isEmpty()) {
            doc.select("meta[property=og:image], meta[name=twitter:image]").forEach { meta ->
                val content = meta.attr("content").trim()
                if (content.isNotBlank()) {
                    candidates.add(resolveUrl(baseUrl, content))
                }
            }
        }

        return orderPages(candidates)
    }

    private fun extractFirstSrcsetUrl(srcset: String): String {
        if (srcset.isBlank()) return ""
        return srcset.split(",")
            .lastOrNull()
            ?.trim()
            ?.substringBefore(" ")
            .orEmpty()
    }

    private fun resolveUrl(baseUrl: String, ref: String): String {
        return try {
            URI(baseUrl).resolve(ref).toString()
        } catch (_: Exception) {
            ref
        }
    }

    private fun isLikelyComicPageUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("favicon") || lower.contains("avatar") || lower.contains("logo") ||
            lower.contains("emoji") || lower.contains("badge") || lower.endsWith(".svg")
        ) return false
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}

class SitemapSourceAdapter : SourceAdapter {
    override val name: String = "SitemapXmlAdapter"
    override fun canHandle(url: String): Boolean = url.lowercase().endsWith(".xml")

    override fun parsePages(html: String, baseUrl: String): List<String> {
        val doc = Jsoup.parse(html, baseUrl, org.jsoup.parser.Parser.xmlParser())
        return orderPages(doc.select("image|loc, loc").mapNotNull { el ->
            val t = el.text().trim()
            if (t.matches(Regex(".*\\.(jpg|jpeg|png|webp)(\\?.*)?$", RegexOption.IGNORE_CASE))) t else null
        })
    }
}

class SourceAdapterRegistry {
    private val adapters: List<SourceAdapter> = listOf(
        SitemapSourceAdapter(),
        MangaDomSourceAdapter()
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fetchChapter(url: String): DirectImportResult {
        val normalizedUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            url.trim()
        } else {
            "https://${url.trim()}"
        }
        val adapter = adapters.firstOrNull { it.canHandle(normalizedUrl) } ?: MangaDomSourceAdapter()

        return try {
            val req = Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", "FaqqaReader/1.0 (Android; ComicTranslationStudio)")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val cfRay = resp.header("cf-mitigated")
                val body = resp.body?.string().orEmpty()

                val isChallenge = code == 403 || code == 429 || code == 503 ||
                    !cfRay.isNullOrBlank() ||
                    body.contains("Attention Required! | Cloudflare", ignoreCase = true) ||
                    body.contains("cf-challenge", ignoreCase = true) ||
                    body.contains("g-recaptcha", ignoreCase = true)

                if (isChallenge) {
                    return DirectImportResult.BlockedBySource(
                        code = "BLOCKED_BY_SOURCE",
                        httpCode = code,
                        adapterName = adapter.name,
                        message = "المصدر محمي (HTTP $code / Cloudflare / CAPTCHA). استخدم المسار B (المتصفح المدمج أو Share Target) أو المسار C (استيراد الملفات) دون تجاوز."
                    )
                }

                if (!resp.isSuccessful) {
                    return DirectImportResult.Error(
                        code = "HTTP_ERROR",
                        httpCode = code,
                        adapterName = adapter.name,
                        message = "تعذر جلب الرابط المباشر (HTTP $code)."
                    )
                }

                val pages = adapter.parsePages(body, normalizedUrl)
                val title = try {
                    Jsoup.parse(body).title().ifBlank { "فصل مستورد" }
                } catch (_: Exception) {
                    "فصل مستورد"
                }

                if (pages.isEmpty()) {
                    DirectImportResult.Error(
                        code = "NO_IMAGES_FOUND",
                        httpCode = code,
                        adapterName = adapter.name,
                        message = "لم يُعثر على صور صفحات في HTML الثابت. افتح الرابط في المتصفح المدمج لالتقاط الصور الديناميكية."
                    )
                } else {
                    DirectImportResult.Success(
                        adapterName = adapter.name,
                        chapterTitle = title,
                        imageUrls = pages,
                        httpCode = code
                    )
                }
            }
        } catch (e: Exception) {
            DirectImportResult.Error(
                code = "NETWORK_ERROR",
                httpCode = 0,
                adapterName = adapter.name,
                message = "خطأ شبكة أثناء جلب الرابط: ${e.localizedMessage}"
            )
        }
    }

    fun downloadImageBytes(imageUrl: String, referer: String? = null): ByteArray? {
        return try {
            val builder = Request.Builder()
                .url(imageUrl)
                .header("User-Agent", "FaqqaReader/1.0 (Android)")
            if (!referer.isNullOrBlank()) {
                builder.header("Referer", referer)
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }
}
