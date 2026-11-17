package com.example.engine.importpath

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class ImportedImageFile(
    val pageOrder: Int,
    val filePath: String,
    val width: Int,
    val height: Int
)

object FileImportEngine {
    private const val MAX_ARCHIVE_UNCOMPRESSED_BYTES = 150L * 1024L * 1024L
    private const val MAX_SINGLE_IMAGE_BYTES = 25L * 1024L * 1024L

    suspend fun importUriList(
        context: Context,
        uris: List<Uri>,
        projectDir: File
    ): Result<List<ImportedImageFile>> = withContext(Dispatchers.IO) {
        try {
            projectDir.mkdirs()
            val results = mutableListOf<ImportedImageFile>()
            var orderCounter = 0

            for (uri in uris) {
                val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
                val fileName = queryFileName(context, uri).lowercase()

                when {
                    mime == "application/pdf" || fileName.endsWith(".pdf") -> {
                        val pdfPages = importPdfUri(context, uri, projectDir, orderCounter)
                        results.addAll(pdfPages)
                        orderCounter += pdfPages.size
                    }
                    mime.contains("zip") || mime.contains("cbz") ||
                        fileName.endsWith(".zip") || fileName.endsWith(".cbz") -> {
                        val zipPages = importZipOrCbzUri(context, uri, projectDir, orderCounter)
                        results.addAll(zipPages)
                        orderCounter += zipPages.size
                    }
                    else -> {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val bytes = readBoundedBytes(input, MAX_SINGLE_IMAGE_BYTES)
                            if (isValidImageMagicBytes(bytes)) {
                                saveImmutableOriginal(bytes, projectDir, orderCounter)?.let {
                                    results.add(it)
                                    orderCounter++
                                }
                            }
                        }
                    }
                }
            }

            if (results.isEmpty()) {
                Result.failure(IllegalArgumentException("لم يتم العثور على صور صالحة (JPG / PNG / WEBP / PDF / ZIP / CBZ)."))
            } else {
                Result.success(results)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun importZipStreamSafely(
        inputStream: InputStream,
        destDir: File,
        startOrder: Int = 0
    ): List<ImportedImageFile> {
        destDir.mkdirs()
        val canonicalDest = destDir.canonicalFile
        val tempExtractDir = File(destDir, "tmp_${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        val extractedEntries = mutableListOf<Pair<String, File>>()
        var totalUncompressed = 0L

        try {
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = File(tempExtractDir, entry.name)
                        // Strict Zip Slip prevention
                        val canonicalOut = outFile.canonicalPath
                        val canonicalTemp = tempExtractDir.canonicalPath + File.separator
                        if (!canonicalOut.startsWith(canonicalTemp)) {
                            throw SecurityException("Zip Slip blocked: ${entry.name}")
                        }

                        outFile.parentFile?.mkdirs()
                        val buffer = ByteArray(8192)
                        var entryBytes = 0L
                        val baos = java.io.ByteArrayOutputStream()
                        while (true) {
                            val read = zis.read(buffer)
                            if (read <= 0) break
                            entryBytes += read
                            totalUncompressed += read
                            if (totalUncompressed > MAX_ARCHIVE_UNCOMPRESSED_BYTES) {
                                throw IllegalStateException("تجاوز الأرشيف الحد الأقصى المسموح (150MB).")
                            }
                            baos.write(buffer, 0, read)
                        }
                        val bytes = baos.toByteArray()
                        if (isValidImageMagicBytes(bytes)) {
                            FileOutputStream(outFile).use { it.write(bytes) }
                            extractedEntries.add(entry.name to outFile)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Natural numeric sort for comic page filenames (page_2 before page_10)
            val sorted = extractedEntries.sortedWith(compareBy { naturalSortKey(it.first) })
            val finalPages = mutableListOf<ImportedImageFile>()
            sorted.forEachIndexed { idx, (_, file) ->
                val bytes = file.readBytes()
                saveImmutableOriginal(bytes, destDir, startOrder + idx)?.let {
                    finalPages.add(it)
                }
            }
            return finalPages
        } finally {
            tempExtractDir.deleteRecursively()
        }
    }

    private fun importZipOrCbzUri(
        context: Context,
        uri: Uri,
        projectDir: File,
        startOrder: Int
    ): List<ImportedImageFile> {
        val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
        return input.use { importZipStreamSafely(it, projectDir, startOrder) }
    }

    private fun importPdfUri(
        context: Context,
        uri: Uri,
        projectDir: File,
        startOrder: Int
    ): List<ImportedImageFile> {
        val tempPdf = File(projectDir, "import_${UUID.randomUUID().toString().take(8)}.pdf")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempPdf).use { out -> input.copyTo(out) }
            } ?: return emptyList()

            val pfd = ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val pages = mutableListOf<ImportedImageFile>()
                    for (i in 0 until renderer.pageCount.coerceAtMost(200)) {
                        renderer.openPage(i).use { page ->
                            val scale = 2
                            val w = (page.width * scale).coerceAtMost(2200)
                            val h = (page.height * scale).coerceAtMost(3600)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                            val outFile = File(projectDir, "orig_${UUID.randomUUID().toString().take(12)}.png")
                            FileOutputStream(outFile).use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            bmp.recycle()
                            pages.add(
                                ImportedImageFile(
                                    pageOrder = startOrder + i,
                                    filePath = outFile.absolutePath,
                                    width = w,
                                    height = h
                                )
                            )
                        }
                    }
                    return pages
                }
            }
        } finally {
            tempPdf.delete()
        }
    }

    fun saveBitmapAsImmutableOriginal(
        bitmap: Bitmap,
        projectDir: File,
        pageOrder: Int
    ): ImportedImageFile {
        projectDir.mkdirs()
        val outFile = File(projectDir, "orig_${UUID.randomUUID().toString().take(12)}.png")
        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return ImportedImageFile(
            pageOrder = pageOrder,
            filePath = outFile.absolutePath,
            width = bitmap.width,
            height = bitmap.height
        )
    }

    fun saveBytesAsImmutableOriginal(
        bytes: ByteArray,
        projectDir: File,
        pageOrder: Int
    ): ImportedImageFile? {
        if (!isValidImageMagicBytes(bytes)) return null
        projectDir.mkdirs()
        return saveImmutableOriginal(bytes, projectDir, pageOrder)
    }

    private fun saveImmutableOriginal(
        bytes: ByteArray,
        projectDir: File,
        pageOrder: Int
    ): ImportedImageFile? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        // Downscale only if gigantic (> 2800px wide) to avoid OOM on mobile while keeping crisp quality
        val sampleSize = if (opts.outWidth > 2800) 2 else 1
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null

        val outFile = File(projectDir, "orig_${UUID.randomUUID().toString().take(12)}.png")
        FileOutputStream(outFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()
        return ImportedImageFile(pageOrder, outFile.absolutePath, w, h)
    }

    fun isValidImageMagicBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        // JPEG: FF D8 FF
        val isJpg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        // PNG: 89 50 4E 47
        val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        // WEBP: RIFF....WEBP
        val isWebp = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        return isJpg || isPng || isWebp
    }

    private fun readBoundedBytes(input: InputStream, maxBytes: Long): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            if (total > maxBytes) throw IllegalStateException("الملف يتجاوز الحد الأقصى (25MB)")
            baos.write(buf, 0, read)
        }
        return baos.toByteArray()
    }

    private fun queryFileName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun naturalSortKey(name: String): String {
        return name.replace(Regex("\\d+")) { m ->
            m.value.padStart(10, '0')
        }.lowercase()
    }
}
