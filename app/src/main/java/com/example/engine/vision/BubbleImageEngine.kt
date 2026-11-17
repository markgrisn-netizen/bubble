package com.example.engine.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.data.firestore.SfxMode
import com.example.data.local.LocalTextRegionEntity
import com.example.engine.ai.DetectedBubbleAnalysis
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

data class BubbleMaskResult(
    val width: Int,
    val height: Int,
    val mask: BooleanArray,
    val dominantFillColor: Int,
    val safeTextRect: Rect
)

object BubbleImageEngine {

    /**
     * Offline / Local ML Kit OCR fallback when user uses Ollama or non-vision providers.
     */
    suspend fun detectRegionsOfflineMlKit(bitmap: Bitmap): List<DetectedBubbleAnalysis> =
        withContext(Dispatchers.IO) {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val input = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(input).await()
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)
            result.textBlocks.mapIndexedNotNull { idx, block ->
                val box = block.boundingBox ?: return@mapIndexedNotNull null
                val text = block.text.trim()
                if (text.isBlank()) return@mapIndexedNotNull null
                DetectedBubbleAnalysis(
                    orderIndex = idx,
                    regionType = "speech",
                    leftNorm = (box.left / w).coerceIn(0f, 0.98f),
                    topNorm = (box.top / h).coerceIn(0f, 0.98f),
                    rightNorm = (box.right / w).coerceIn(0.02f, 1f),
                    bottomNorm = (box.bottom / h).coerceIn(0.02f, 1f),
                    sourceText = text,
                    detectedLang = "EN",
                    translatedText = "",
                    confidence = 0.82f
                )
            }
        }

    /**
     * Extracts an exact organic pixel mask of the speech bubble containing [region],
     * NOT a crude rectangle, then erodes the mask by [shrinkPx] (2-3px) so the black
     * outline, tail, and character edges are preserved.
     */
    fun extractExactBubbleMask(
        bitmap: Bitmap,
        region: LocalTextRegionEntity
    ): BubbleMaskResult {
        val w = bitmap.width
        val h = bitmap.height
        val left = (region.leftNorm * w).toInt().coerceIn(0, w - 1)
        val top = (region.topNorm * h).toInt().coerceIn(0, h - 1)
        val right = (region.rightNorm * w).toInt().coerceIn(left + 1, w)
        val bottom = (region.bottomNorm * h).toInt().coerceIn(top + 1, h)

        val boxW = right - left
        val boxH = bottom - top
        val searchLeft = (left - (boxW * 0.28f).toInt()).coerceAtLeast(0)
        val searchTop = (top - (boxH * 0.28f).toInt()).coerceAtLeast(0)
        val searchRight = (right + (boxW * 0.28f).toInt()).coerceAtMost(w)
        val searchBottom = (bottom + (boxH * 0.28f).toInt()).coerceAtMost(h)
        val sw = searchRight - searchLeft
        val sh = searchBottom - searchTop

        val pixels = IntArray(sw * sh)
        bitmap.getPixels(pixels, 0, sw, searchLeft, searchTop, sw, sh)

        // Find brightest interior seed near the text box center (avoiding dark glyph ink)
        val relCenterX = ((left + right) / 2) - searchLeft
        val relCenterY = ((top + bottom) / 2) - searchTop
        var bestSeedIdx = relCenterY * sw + relCenterX
        var bestLum = -1
        val step = max(1, min(boxW, boxH) / 10)
        for (y in (top - searchTop)..(bottom - searchTop - 1) step step) {
            for (x in (left - searchLeft)..(right - searchLeft - 1) step step) {
                if (x in 0 until sw && y in 0 until sh) {
                    val c = pixels[y * sw + x]
                    val lum = luminance(c)
                    if (lum > bestLum) {
                        bestLum = lum
                        bestSeedIdx = y * sw + x
                    }
                }
            }
        }

        val seedColor = pixels[bestSeedIdx]
        val seedLum = luminance(seedColor)
        val localMask = BooleanArray(sw * sh)
        var touchesSearchBoundary = false

        // Flood-fill ONLY if interior is bright/uniform bubble background
        if (seedLum >= 165) {
            val queue = IntArray(sw * sh)
            var head = 0
            var tail = 0
            queue[tail++] = bestSeedIdx
            localMask[bestSeedIdx] = true

            val maxDarkInkLum = 115
            val colorTolerance = 55

            while (head < tail) {
                val curr = queue[head++]
                val cx = curr % sw
                val cy = curr / sw
                if (cx == 0 || cy == 0 || cx == sw - 1 || cy == sh - 1) {
                    touchesSearchBoundary = true
                }

                // 4-neighbor expansion
                if (cx > 0) {
                    val n = curr - 1
                    if (!localMask[n] && isBubbleInterior(pixels[n], seedColor, maxDarkInkLum, colorTolerance)) {
                        localMask[n] = true
                        queue[tail++] = n
                    }
                }
                if (cx + 1 < sw) {
                    val n = curr + 1
                    if (!localMask[n] && isBubbleInterior(pixels[n], seedColor, maxDarkInkLum, colorTolerance)) {
                        localMask[n] = true
                        queue[tail++] = n
                    }
                }
                if (cy > 0) {
                    val n = curr - sw
                    if (!localMask[n] && isBubbleInterior(pixels[n], seedColor, maxDarkInkLum, colorTolerance)) {
                        localMask[n] = true
                        queue[tail++] = n
                    }
                }
                if (cy + 1 < sh) {
                    val n = curr + sw
                    if (!localMask[n] && isBubbleInterior(pixels[n], seedColor, maxDarkInkLum, colorTolerance)) {
                        localMask[n] = true
                        queue[tail++] = n
                    }
                }
            }

            // Fill isolated dark text glyphs completely surrounded by bubble interior
            fillEnclosedTextHoles(localMask, pixels, sw, sh, left - searchLeft, top - searchTop, right - searchLeft, bottom - searchTop)
        }

        val fullMask = BooleanArray(w * h)
        val maskCount = localMask.count { it }
        val useFloodMask = seedLum >= 165 && !touchesSearchBoundary && maskCount > (boxW * boxH * 0.25f)

        if (useFloodMask) {
            for (y in 0 until sh) {
                val fy = searchTop + y
                for (x in 0 until sw) {
                    if (localMask[y * sw + x]) {
                        fullMask[fy * w + (searchLeft + x)] = true
                    }
                }
            }
        } else {
            // Non-enclosed or gradient region: fit a tight organic superellipse/oval mask around text box
            val rx = (boxW * 0.52f).coerceAtLeast(4f)
            val ry = (boxH * 0.52f).coerceAtLeast(4f)
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            for (y in top until bottom) {
                for (x in left until right) {
                    val dx = (x - cx) / rx
                    val dy = (y - cy) / ry
                    // Rounded superellipse (power 3.2) matches comic bubbles & rounded boxes
                    if (kotlin.math.abs(dx * dx * dx) + kotlin.math.abs(dy * dy * dy) <= 1.05f) {
                        fullMask[y * w + x] = true
                    }
                }
            }
        }

        // Morphological erosion (shrink mask by 2-3px) to protect black bubble border & tail
        val shrink = region.maskShrinkPx.coerceIn(1, 5)
        val erodedMask = erodeMask(fullMask, w, h, shrink, left, top, right, bottom, searchLeft, searchTop, searchRight, searchBottom)
        val safeRect = computeInscribedSafeRect(erodedMask, w, h, left, top, right, bottom)
        val fillColor = if (seedLum >= 195) Color.WHITE else seedColor

        return BubbleMaskResult(
            width = w,
            height = h,
            mask = erodedMask,
            dominantFillColor = fillColor,
            safeTextRect = safeRect
        )
    }

    private fun isBubbleInterior(pixel: Int, seedColor: Int, maxDarkLum: Int, tolerance: Int): Boolean {
        val lum = luminance(pixel)
        if (lum <= maxDarkLum) return false
        val dr = kotlin.math.abs(Color.red(pixel) - Color.red(seedColor))
        val dg = kotlin.math.abs(Color.green(pixel) - Color.green(seedColor))
        val db = kotlin.math.abs(Color.blue(pixel) - Color.blue(seedColor))
        return dr <= tolerance && dg <= tolerance && db <= tolerance
    }

    private fun fillEnclosedTextHoles(
        mask: BooleanArray,
        pixels: IntArray,
        sw: Int,
        sh: Int,
        relLeft: Int,
        relTop: Int,
        relRight: Int,
        relBottom: Int
    ) {
        val l = relLeft.coerceIn(1, sw - 2)
        val t = relTop.coerceIn(1, sh - 2)
        val r = relRight.coerceIn(l + 1, sw - 1)
        val b = relBottom.coerceIn(t + 1, sh - 1)
        // Any dark text pixel inside the detected text box whose row & column are bounded by mask pixels
        for (y in t until b) {
            var firstX = -1
            var lastX = -1
            for (x in 0 until sw) {
                if (mask[y * sw + x]) {
                    if (firstX == -1) firstX = x
                    lastX = x
                }
            }
            if (firstX != -1 && lastX > firstX) {
                val spanStart = max(l, firstX + 2)
                val spanEnd = min(r, lastX - 2)
                for (x in spanStart until spanEnd) {
                    if (!mask[y * sw + x] && luminance(pixels[y * sw + x]) < 190) {
                        mask[y * sw + x] = true
                    }
                }
            }
        }
    }

    private fun erodeMask(
        mask: BooleanArray,
        w: Int,
        h: Int,
        radius: Int,
        bLeft: Int,
        bTop: Int,
        bRight: Int,
        bBottom: Int,
        sLeft: Int,
        sTop: Int,
        sRight: Int,
        sBottom: Int
    ): BooleanArray {
        val out = mask.copyOf()
        val x0 = min(bLeft, sLeft).coerceAtLeast(radius)
        val y0 = min(bTop, sTop).coerceAtLeast(radius)
        val x1 = max(bRight, sRight).coerceAtMost(w - radius)
        val y1 = max(bBottom, sBottom).coerceAtMost(h - radius)

        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val idx = y * w + x
                if (!mask[idx]) continue
                var keep = true
                outer@ for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        if (!mask[(y + dy) * w + (x + dx)]) {
                            keep = false
                            break@outer
                        }
                    }
                }
                if (!keep) out[idx] = false
            }
        }
        return out
    }

    private fun computeInscribedSafeRect(
        mask: BooleanArray,
        w: Int,
        h: Int,
        fallbackLeft: Int,
        fallbackTop: Int,
        fallbackRight: Int,
        fallbackBottom: Int
    ): Rect {
        var minY = h
        var maxY = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask[y * w + x]) {
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxY <= minY) {
            return Rect(fallbackLeft, fallbackTop, fallbackRight, fallbackBottom)
        }
        val midTop = minY + ((maxY - minY) * 0.22f).toInt()
        val midBottom = minY + ((maxY - minY) * 0.78f).toInt()
        var safeLeft = 0
        var safeRight = w
        for (y in midTop..midBottom) {
            var rowLeft = -1
            var rowRight = -1
            for (x in 0 until w) {
                if (mask[y * w + x]) {
                    if (rowLeft == -1) rowLeft = x
                    rowRight = x
                }
            }
            if (rowLeft != -1 && rowRight > rowLeft) {
                if (rowLeft > safeLeft) safeLeft = rowLeft
                if (rowRight < safeRight) safeRight = rowRight
            }
        }
        if (safeRight - safeLeft < (fallbackRight - fallbackLeft) * 0.45f) {
            return Rect(
                fallbackLeft + 4,
                fallbackTop + 4,
                (fallbackRight - 4).coerceAtLeast(fallbackLeft + 12),
                (fallbackBottom - 4).coerceAtLeast(fallbackTop + 12)
            )
        }
        val padX = ((safeRight - safeLeft) * 0.08f).toInt().coerceAtLeast(4)
        val padY = ((maxY - minY) * 0.10f).toInt().coerceAtLeast(4)
        return Rect(
            (safeLeft + padX).coerceAtMost(safeRight - 8),
            (minY + padY).coerceAtMost(maxY - 8),
            (safeRight - padX).coerceAtLeast(safeLeft + 8),
            (maxY - padY).coerceAtLeast(minY + 8)
        )
    }

    /**
     * Cleans/whitens ONLY inside the exact shrunk bubble mask + optional manual brush mask,
     * preserving the original immutable image file untouched.
     */
    fun cleanAndRenderPage(
        context: Context,
        originalImagePath: String,
        brushMaskPath: String,
        regions: List<LocalTextRegionEntity>,
        sfxMode: SfxMode,
        cleanBackgroundText: Boolean,
        cleanedOutPath: String,
        renderedOutPath: String
    ): Result<Pair<String, String>> {
        val original = BitmapFactory.decodeFile(originalImagePath)
            ?: return Result.failure(IllegalStateException("تعذر قراءة الصورة الأصلية من المسار المحلي."))

        val w = original.width
        val h = original.height
        val cleanedBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(w * h)
        cleanedBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val safeRects = mutableMapOf<String, Rect>()

        // 1. Clean each active region inside its exact shrunk bubble mask
        for (region in regions) {
            val shouldClean = when (region.regionType) {
                "speech", "narration" -> true
                "sfx" -> sfxMode == SfxMode.VISUAL_REPLACE || sfxMode == SfxMode.ARTISTIC
                "background_text" -> cleanBackgroundText
                else -> false
            }
            if (!shouldClean) {
                safeRects[region.id] = Rect(
                    (region.leftNorm * w).toInt(),
                    (region.topNorm * h).toInt(),
                    (region.rightNorm * w).toInt(),
                    (region.bottomNorm * h).toInt()
                )
                continue
            }

            val maskRes = extractExactBubbleMask(original, region)
            safeRects[region.id] = maskRes.safeTextRect
            val targetColor = if (region.bgColorArgb != 0) region.bgColorArgb else maskRes.dominantFillColor

            // Inpaint/fill inside shrunk mask with 1px smooth boundary feather
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val idx = y * w + x
                    if (maskRes.mask[idx]) {
                        val isEdge = !maskRes.mask[idx - 1] || !maskRes.mask[idx + 1] ||
                            !maskRes.mask[idx - w] || !maskRes.mask[idx + w]
                        pixels[idx] = if (isEdge) {
                            blendColors(pixels[idx], targetColor, 0.72f)
                        } else {
                            targetColor
                        }
                    }
                }
            }
        }

        cleanedBitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        // Apply optional manual brush touch-up mask (white strokes drawn by user)
        if (brushMaskPath.isNotBlank() && File(brushMaskPath).exists()) {
            BitmapFactory.decodeFile(brushMaskPath)?.let { brushBmp ->
                val canvas = Canvas(cleanedBitmap)
                canvas.drawBitmap(brushBmp, null, Rect(0, 0, w, h), null)
                brushBmp.recycle()
            }
        }

        saveBitmapPng(cleanedBitmap, cleanedOutPath)

        // 2. Render clean Arabic RTL typography onto final page (ZERO debug boxes or numbers!)
        val renderedBitmap = cleanedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(renderedBitmap)

        for (region in regions) {
            val translated = region.translatedText.trim()
            if (translated.isEmpty()) continue
            val shouldRender = when (region.regionType) {
                "speech", "narration" -> true
                "sfx" -> sfxMode != SfxMode.KEEP && sfxMode != SfxMode.IGNORE
                "background_text" -> cleanBackgroundText
                else -> false
            }
            if (!shouldRender) continue

            val rect = safeRects[region.id] ?: continue
            if (region.regionType == "sfx" && sfxMode == SfxMode.SIDE_NOTE) {
                drawSideNoteArabicTag(context, canvas, translated, rect, w, h)
            } else {
                drawArabicTextInsideSafeRect(context, canvas, region, translated, rect)
            }
        }

        saveBitmapPng(renderedBitmap, renderedOutPath)
        original.recycle()
        cleanedBitmap.recycle()
        renderedBitmap.recycle()
        return Result.success(cleanedOutPath to renderedOutPath)
    }

    private fun drawArabicTextInsideSafeRect(
        context: Context,
        canvas: Canvas,
        region: LocalTextRegionEntity,
        text: String,
        safeRect: Rect
    ) {
        val availWidth = safeRect.width().coerceAtLeast(24)
        val availHeight = safeRect.height().coerceAtLeast(24)

        val typeface = loadArabicTypeface(context, region.fontFamily)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = region.textColorArgb
            this.typeface = typeface
        }

        val alignment = when (region.alignment) {
            "right" -> Layout.Alignment.ALIGN_OPPOSITE
            "left" -> Layout.Alignment.ALIGN_NORMAL
            else -> Layout.Alignment.ALIGN_CENTER
        }

        val densityScale = (availWidth / 160f).coerceIn(0.8f, 2.6f)
        var sizePx = region.fontSizeSp * densityScale * 1.4f
        val minSizePx = (10f * densityScale).coerceAtLeast(14f)

        var layout: StaticLayout
        while (true) {
            textPaint.textSize = sizePx
            layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, availWidth)
                .setAlignment(alignment)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setLineSpacing(0f, 1.08f)
                .setIncludePad(false)
                .build()
            if (layout.height <= availHeight || sizePx <= minSizePx) {
                break
            }
            sizePx *= 0.88f
        }

        val dy = safeRect.top + ((availHeight - layout.height) / 2f).coerceAtLeast(0f)
        canvas.save()
        canvas.translate(safeRect.left.toFloat(), dy)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawSideNoteArabicTag(
        context: Context,
        canvas: Canvas,
        text: String,
        rect: Rect,
        pageW: Int,
        pageH: Int
    ) {
        val typeface = loadArabicTypeface(context, "CAIRO")
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (pageW * 0.022f).coerceIn(18f, 34f)
            this.typeface = typeface
        }
        val tagWidth = min((pageW * 0.35f).toInt(), textPaint.measureText(text).toInt() + 24)
            .coerceAtLeast(48)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, tagWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(false)
            .build()

        val tagLeft = rect.left.coerceIn(8, (pageW - tagWidth - 16).coerceAtLeast(8))
        val tagTop = (rect.bottom + 4).coerceIn(8, (pageH - layout.height - 16).coerceAtLeast(8))
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 14, 18, 43)
        }
        canvas.drawRoundRect(
            tagLeft - 8f,
            tagTop - 4f,
            tagLeft + tagWidth + 8f,
            tagTop + layout.height + 4f,
            10f,
            10f,
            bgPaint
        )
        canvas.save()
        canvas.translate(tagLeft.toFloat(), tagTop.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    private fun loadArabicTypeface(context: Context, fontKey: String): Typeface {
        val resId = when (fontKey) {
            "RUBIK" -> R.font.rubik
            "NOTO_NASKH" -> R.font.noto_naskh_arabic
            else -> R.font.cairo
        }
        return ResourcesCompat.getFont(context, resId) ?: Typeface.DEFAULT_BOLD
    }

    private fun luminance(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (299 * r + 587 * g + 114 * b) / 1000
    }

    private fun blendColors(c1: Int, c2: Int, ratioC2: Float): Int {
        val r1 = Color.red(c1)
        val g1 = Color.green(c1)
        val b1 = Color.blue(c1)
        val r2 = Color.red(c2)
        val g2 = Color.green(c2)
        val b2 = Color.blue(c2)
        val inv = 1f - ratioC2
        return Color.rgb(
            (r1 * inv + r2 * ratioC2).toInt().coerceIn(0, 255),
            (g1 * inv + g2 * ratioC2).toInt().coerceIn(0, 255),
            (b1 * inv + b2 * ratioC2).toInt().coerceIn(0, 255)
        )
    }

    private fun saveBitmapPng(bitmap: Bitmap, outPath: String) {
        val file = File(outPath)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    fun applyManualBrushStroke(
        brushMaskPath: String,
        width: Int,
        height: Int,
        pointsNorm: List<Pair<Float, Float>>,
        strokeWidthNorm: Float,
        fillColor: Int
    ) {
        if (pointsNorm.isEmpty()) return
        val file = File(brushMaskPath)
        file.parentFile?.mkdirs()
        val bmp = if (file.exists()) {
            BitmapFactory.decodeFile(brushMaskPath)?.copy(Bitmap.Config.ARGB_8888, true)
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = (strokeWidthNorm * width).coerceIn(8f, 96f)
        }
        val path = Path()
        pointsNorm.forEachIndexed { idx, (nx, ny) ->
            val px = nx * width
            val py = ny * height
            if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, paint)
        saveBitmapPng(bmp, brushMaskPath)
        bmp.recycle()
    }
}
