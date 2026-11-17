package com.example.engine.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import com.example.data.local.LocalPageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    PNG("png", "image/png", "PNG (جودة كاملة)"),
    JPG("jpg", "image/jpeg", "JPG (حجم أصغر)"),
    WEBP("webp", "image/webp", "WEBP (حديث)"),
    PDF("pdf", "application/pdf", "PDF (ملف قراءة)"),
    CBZ("cbz", "application/vnd.comicbook+zip", "CBZ (أرشيف كوميكس)")
}

object ExportEngine {

    /**
     * Exports clean translated pages (NEVER debug boxes or bubble numbers) to the target SAF URI.
     */
    suspend fun exportChapterToUri(
        context: Context,
        pages: List<LocalPageEntity>,
        format: ExportFormat,
        targetUri: Uri
    ): Result<String> = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("لا توجد صفحات للتصدير."))
        }
        try {
            val resolvedPaths = pages.sortedBy { it.pageOrder }.map { page ->
                when {
                    page.renderedImagePath.isNotBlank() && File(page.renderedImagePath).exists() -> page.renderedImagePath
                    page.cleanedImagePath.isNotBlank() && File(page.cleanedImagePath).exists() -> page.cleanedImagePath
                    else -> page.originalImagePath
                }
            }

            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                when (format) {
                    ExportFormat.PNG, ExportFormat.JPG, ExportFormat.WEBP -> {
                        val bmp = BitmapFactory.decodeFile(resolvedPaths.first())
                            ?: return@withContext Result.failure(IllegalStateException("تعذر قراءة الصفحة."))
                        val compressFmt = when (format) {
                            ExportFormat.PNG -> Bitmap.CompressFormat.PNG
                            ExportFormat.JPG -> Bitmap.CompressFormat.JPEG
                            ExportFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                Bitmap.CompressFormat.WEBP_LOSSY
                            } else {
                                @Suppress("DEPRECATION")
                                Bitmap.CompressFormat.WEBP
                            }
                            else -> Bitmap.CompressFormat.PNG
                        }
                        bmp.compress(compressFmt, 95, out)
                        bmp.recycle()
                    }
                    ExportFormat.PDF -> {
                        writePdfDocumentOrFallback(resolvedPaths, out)
                    }
                    ExportFormat.CBZ -> {
                        ZipOutputStream(out).use { zos ->
                            resolvedPaths.forEachIndexed { index, path ->
                                val bmp = BitmapFactory.decodeFile(path) ?: return@forEachIndexed
                                val entryName = String.format("page_%03d.png", index + 1)
                                zos.putNextEntry(ZipEntry(entryName))
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, zos)
                                zos.closeEntry()
                                bmp.recycle()
                            }
                        }
                    }
                }
            } ?: return@withContext Result.failure(IllegalStateException("تعذر فتح ملف التصدير للكتابة."))

            Result.success("تم التصدير بنجاح بصيغة ${format.name} (${pages.size} صفحة)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Helper to also export directly to a local File for testing or internal sharing.
     */
    suspend fun exportChapterToFile(
        pages: List<LocalPageEntity>,
        format: ExportFormat,
        outFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            outFile.parentFile?.mkdirs()
            val resolvedPaths = pages.sortedBy { it.pageOrder }.map { page ->
                when {
                    page.renderedImagePath.isNotBlank() && File(page.renderedImagePath).exists() -> page.renderedImagePath
                    page.cleanedImagePath.isNotBlank() && File(page.cleanedImagePath).exists() -> page.cleanedImagePath
                    else -> page.originalImagePath
                }
            }
            FileOutputStream(outFile).use { out ->
                when (format) {
                    ExportFormat.CBZ -> {
                        ZipOutputStream(out).use { zos ->
                            resolvedPaths.forEachIndexed { idx, p ->
                                val bmp = BitmapFactory.decodeFile(p) ?: return@forEachIndexed
                                zos.putNextEntry(ZipEntry(String.format("page_%03d.png", idx + 1)))
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, zos)
                                zos.closeEntry()
                                bmp.recycle()
                            }
                        }
                    }
                    ExportFormat.PDF -> {
                        writePdfDocumentOrFallback(resolvedPaths, out)
                    }
                    else -> {
                        val bmp = BitmapFactory.decodeFile(resolvedPaths.first())
                            ?: return@withContext Result.failure(IllegalStateException("Missing image"))
                        val fmt = if (format == ExportFormat.JPG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                        bmp.compress(fmt, 95, out)
                        bmp.recycle()
                    }
                }
            }
            Result.success(outFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun writePdfDocumentOrFallback(
        resolvedPaths: List<String>,
        out: java.io.OutputStream
    ) {
        val nativeWorked = try {
            val pdf = PdfDocument()
            resolvedPaths.forEachIndexed { index, path ->
                val bmp = BitmapFactory.decodeFile(path) ?: return@forEachIndexed
                val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, index + 1).create()
                val pdfPage = pdf.startPage(pageInfo)
                pdfPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                pdf.finishPage(pdfPage)
                bmp.recycle()
            }
            pdf.writeTo(out)
            pdf.close()
            true
        } catch (_: Throwable) {
            false
        }
        if (!nativeWorked) {
            writePureKotlinPdf(resolvedPaths, out)
        }
    }

    private fun writePureKotlinPdf(
        imagePaths: List<String>,
        out: java.io.OutputStream
    ) {
        val pageEntries = mutableListOf<Triple<Int, Int, ByteArray>>()
        for (path in imagePaths) {
            val bmp = BitmapFactory.decodeFile(path) ?: continue
            val jpegBaos = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, jpegBaos)
            pageEntries.add(Triple(bmp.width, bmp.height, jpegBaos.toByteArray()))
            bmp.recycle()
        }
        if (pageEntries.isEmpty()) throw IllegalStateException("لا توجد صور صالحة لبناء PDF.")

        val baos = java.io.ByteArrayOutputStream()
        fun writeAscii(s: String) = baos.write(s.toByteArray(Charsets.US_ASCII))

        writeAscii("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        // Object 1: Catalog, Object 2: Pages
        val pageCount = pageEntries.size
        val pageObjIds = (0 until pageCount).map { 3 + it * 3 }

        offsets.add(baos.size())
        writeAscii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        offsets.add(baos.size())
        val kidsStr = pageObjIds.joinToString(" ") { "$it 0 R" }
        writeAscii("2 0 obj\n<< /Type /Pages /Kids [$kidsStr] /Count $pageCount >>\nendobj\n")

        pageEntries.forEachIndexed { idx, (w, h, jpegBytes) ->
            val pageObj = 3 + idx * 3
            val contentObj = pageObj + 1
            val imgObj = pageObj + 2

            offsets.add(baos.size())
            writeAscii(
                "$pageObj 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] " +
                    "/Resources << /XObject << /Im$idx $imgObj 0 R >> >> /Contents $contentObj 0 R >>\nendobj\n"
            )

            val contentStream = "q\n$w 0 0 $h 0 0 cm\n/Im$idx Do\nQ\n"
            offsets.add(baos.size())
            writeAscii(
                "$contentObj 0 obj\n<< /Length ${contentStream.length} >>\nstream\n$contentStream\nendstream\nendobj\n"
            )

            offsets.add(baos.size())
            writeAscii(
                "$imgObj 0 obj\n<< /Type /XObject /Subtype /Image /Width $w /Height $h " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpegBytes.size} >>\nstream\n"
            )
            baos.write(jpegBytes)
            writeAscii("\nendstream\nendobj\n")
        }

        val xrefPos = baos.size()
        val totalObjs = offsets.size + 1
        writeAscii("xref\n0 $totalObjs\n0000000000 65535 f \n")
        for (off in offsets) {
            writeAscii(String.format("%010d 00000 n \n", off))
        }
        writeAscii("trailer\n<< /Size $totalObjs /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF")
        out.write(baos.toByteArray())
    }
}
