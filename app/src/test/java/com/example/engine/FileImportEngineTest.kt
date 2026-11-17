package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.engine.importpath.FileImportEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileImportEngineTest {

    private fun createValidPngBytes(color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun `importZipStreamSafely sorts page_2 before page_10 and filters non-image files`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val destDir = File(context.filesDir, "test_cbz_import").apply {
            deleteRecursively()
            mkdirs()
        }

        val zipBytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zos ->
                zos.putNextEntry(ZipEntry("chapter/page_10.png"))
                zos.write(createValidPngBytes(0xFFFF0000.toInt())) // Red = page 10
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("chapter/page_2.png"))
                zos.write(createValidPngBytes(0xFF00FF00.toInt())) // Green = page 2
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("chapter/page_1.png"))
                zos.write(createValidPngBytes(0xFF0000FF.toInt())) // Blue = page 1
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("chapter/fake.png"))
                zos.write("not an image".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()

        val imported = FileImportEngine.importZipStreamSafely(
            inputStream = ByteArrayInputStream(zipBytes),
            destDir = destDir,
            startOrder = 0
        )

        assertEquals(3, imported.size)
        assertEquals(0, imported[0].pageOrder)
        assertEquals(1, imported[1].pageOrder)
        assertEquals(2, imported[2].pageOrder)

        // Check natural sort preserved pixel order: Blue -> Green -> Red
        val b0 = android.graphics.BitmapFactory.decodeFile(imported[0].filePath)
        val b1 = android.graphics.BitmapFactory.decodeFile(imported[1].filePath)
        val b2 = android.graphics.BitmapFactory.decodeFile(imported[2].filePath)
        assertEquals(0xFF0000FF.toInt(), b0.getPixel(5, 5))
        assertEquals(0xFF00FF00.toInt(), b1.getPixel(5, 5))
        assertEquals(0xFFFF0000.toInt(), b2.getPixel(5, 5))
        b0.recycle()
        b1.recycle()
        b2.recycle()

        destDir.deleteRecursively()
    }

    @Test
    fun `importZipStreamSafely blocks Zip Slip path traversal entries`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val destDir = File(context.filesDir, "test_zipslip").apply {
            deleteRecursively()
            mkdirs()
        }

        val evilZipBytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zos ->
                zos.putNextEntry(ZipEntry("../../evil_escape.png"))
                zos.write(createValidPngBytes(0xFF112233.toInt()))
                zos.closeEntry()
            }
        }.toByteArray()

        try {
            FileImportEngine.importZipStreamSafely(
                inputStream = ByteArrayInputStream(evilZipBytes),
                destDir = destDir
            )
            fail("Expected SecurityException for Zip Slip entry")
        } catch (expected: SecurityException) {
            assertTrue(expected.message.orEmpty().contains("Zip Slip"))
        } finally {
            assertFalse(File(destDir.parentFile?.parentFile, "evil_escape.png").exists())
            destDir.deleteRecursively()
        }
    }
}
