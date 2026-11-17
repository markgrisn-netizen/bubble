package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LocalPageEntity
import com.example.engine.export.ExportEngine
import com.example.engine.export.ExportFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportEngineTest {

    @Test
    fun `exportChapterToFile creates valid CBZ, PDF, PNG, and JPG outputs`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dir = File(context.filesDir, "test_export").apply {
                deleteRecursively()
                mkdirs()
            }

            val pages = (0..1).map { idx ->
                val file = File(dir, "rendered_$idx.png")
                val bmp = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(if (idx == 0) Color.WHITE else Color.LTGRAY)
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                LocalPageEntity(
                    id = "p_$idx",
                    userId = "u1",
                    projectId = "proj1",
                    chapterId = "ch1",
                    pageOrder = idx,
                    originalImagePath = file.absolutePath,
                    renderedImagePath = file.absolutePath,
                    width = 120,
                    height = 180
                )
            }

            val cbzOut = File(dir, "chapter.cbz")
            assertTrue(ExportEngine.exportChapterToFile(pages, ExportFormat.CBZ, cbzOut).isSuccess)
            val entries = mutableListOf<String>()
            ZipInputStream(FileInputStream(cbzOut)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    entries.add(e.name)
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            assertEquals(listOf("page_001.png", "page_002.png"), entries)

            val pdfOut = File(dir, "chapter.pdf")
            val pdfRes = ExportEngine.exportChapterToFile(pages, ExportFormat.PDF, pdfOut)
            assertTrue("PDF export failed: ${pdfRes.exceptionOrNull()?.stackTraceToString()}", pdfRes.isSuccess)
            val pdfText = pdfOut.readText(Charsets.ISO_8859_1)
            assertTrue("Expected %PDF-1.4 header", pdfText.startsWith("%PDF-1.4"))
            assertTrue("Expected %%EOF trailer", pdfText.contains("%%EOF"))

            val pngOut = File(dir, "page.png")
            assertTrue(ExportEngine.exportChapterToFile(pages, ExportFormat.PNG, pngOut).isSuccess)
            assertTrue(pngOut.length() > 100)
            val pngBytes = pngOut.readBytes()
            assertEquals(0x89.toByte(), pngBytes[0])
            assertEquals(0x50.toByte(), pngBytes[1])

            val jpgOut = File(dir, "page.jpg")
            assertTrue(ExportEngine.exportChapterToFile(pages, ExportFormat.JPG, jpgOut).isSuccess)
            assertTrue(jpgOut.length() > 100)
            val jpgBytes = jpgOut.readBytes()
            assertEquals(0xFF.toByte(), jpgBytes[0])
            assertEquals(0xD8.toByte(), jpgBytes[1])

            dir.deleteRecursively()
        }
    }
}
