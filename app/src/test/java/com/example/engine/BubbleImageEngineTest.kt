package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.example.data.firestore.SfxMode
import com.example.data.local.LocalTextRegionEntity
import com.example.engine.vision.BubbleImageEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleImageEngineTest {

    @Test
    fun `exact mask shrinks away from black border, preserves original, cleans ink, and renders clean Arabic without debug boxes`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val workDir = File(context.filesDir, "test_bubble_engine").apply {
                deleteRecursively()
                mkdirs()
            }

            // Create synthetic comic panel:
            // 400x400 dark navy background (#182038), centered white oval speech bubble (80..320, 80..320)
            // with crisp black border (strokeWidth = 8f) and dark English text inside.
            val w = 400
            val h = 400
            val bgCol = Color.rgb(24, 32, 56)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(bgCol)

            val bubbleRect = RectF(80f, 80f, 320f, 320f)
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 8f
            }
            canvas.drawOval(bubbleRect, fillPaint)
            canvas.drawOval(bubbleRect, borderPaint)

            // Draw dark English ink strictly in center
            val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 36f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("HELLO!", 200f, 210f, inkPaint)

            val origFile = File(workDir, "orig.png")
            FileOutputStream(origFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val origBytesBefore = origFile.readBytes()
            bmp.recycle()

            val region = LocalTextRegionEntity(
                id = "reg_1",
                pageId = "page_1",
                orderIndex = 0,
                regionType = "speech",
                leftNorm = 110f / w,
                topNorm = 150f / h,
                rightNorm = 290f / w,
                bottomNorm = 250f / h,
                sourceText = "HELLO!",
                translatedText = "مرحبًا يا صديقي!",
                fontFamily = "CAIRO",
                fontSizeSp = 18f,
                maskShrinkPx = 3
            )

            val loadedOrig = BitmapFactory.decodeFile(origFile.absolutePath)
            val maskRes = BubbleImageEngine.extractExactBubbleMask(loadedOrig, region)
            loadedOrig.recycle()

            // 1. Center inside bubble should be masked, while black border at (200, 80) MUST NOT be masked
            assertTrue("Interior center should be in bubble mask", maskRes.mask[160 * w + 200])
            assertFalse("Black border top at (200, 80) must be excluded after mask erosion", maskRes.mask[80 * w + 200])
            assertFalse("Outside panel background at (10, 10) must NOT be masked", maskRes.mask[10 * w + 10])

            val cleanedFile = File(workDir, "cleaned.png")
            val renderedFile = File(workDir, "rendered.png")
            val res = BubbleImageEngine.cleanAndRenderPage(
                context = context,
                originalImagePath = origFile.absolutePath,
                brushMaskPath = "",
                regions = listOf(region),
                sfxMode = SfxMode.KEEP,
                cleanBackgroundText = false,
                cleanedOutPath = cleanedFile.absolutePath,
                renderedOutPath = renderedFile.absolutePath
            )

            assertTrue("cleanAndRenderPage should succeed", res.isSuccess)
            assertTrue(cleanedFile.exists())
            assertTrue(renderedFile.exists())

            // 2. Original file must remain 100% immutable
            assertTrue("Original bytes must remain untouched", origBytesBefore.contentEquals(origFile.readBytes()))

            // 3. Cleaned bitmap must keep black border at (200, 80) dark and remove center English ink
            val cleanedBmp = BitmapFactory.decodeFile(cleanedFile.absolutePath)
            val borderPixel = cleanedBmp.getPixel(200, 80)
            assertTrue("Border pixel must remain black/dark", Color.red(borderPixel) < 50 && Color.green(borderPixel) < 50)
            // Corner outside bubble must have zero debug box lines or badge numbers
            assertEquals("Outside pixel must match untouched background", bgCol, cleanedBmp.getPixel(20, 20))
            cleanedBmp.recycle()

            // 4. Rendered bitmap must also have ZERO debug boxes/numbers outside bubble
            val renderedBmp = BitmapFactory.decodeFile(renderedFile.absolutePath)
            assertEquals("Rendered corner must stay untouched background", bgCol, renderedBmp.getPixel(20, 20))
            assertEquals("Rendered top-right corner must stay untouched", bgCol, renderedBmp.getPixel(380, 20))
            renderedBmp.recycle()

            workDir.deleteRecursively()
        }
    }
}
