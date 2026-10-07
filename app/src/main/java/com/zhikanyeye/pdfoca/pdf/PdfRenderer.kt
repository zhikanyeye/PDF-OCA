package com.zhikanyeye.pdfoca.pdf

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.LinkedHashMap

/**
 * PDF rendering service backed by MuPDF/Fitz.
 *
 * The reader keeps one MuPDF document session open instead of parsing the
 * complete PDF again for every page. Pages and display lists are kept in a
 * small native-resource LRU, while rendered Android bitmaps use a separate
 * memory-bounded cache.
 */
class PdfRendererService(private val context: Context) : AutoCloseable {

    private data class PageEntry(
        val page: Page,
        val displayList: DisplayList,
    )

    private class UriSeekableInputStream(
        private val resolver: ContentResolver,
        private val uri: Uri,
    ) : SeekableInputStream {
        private var stream: InputStream = open()
        private var position: Long = 0L

        private fun open(): InputStream =
            resolver.openInputStream(uri) ?: throw IOException("无法打开 PDF")

        override fun seek(offset: Long, whence: Int): Long {
            val target = when (whence) {
                SEEK_SET -> offset
                SEEK_CUR -> position + offset
                SEEK_END -> {
                    // Content providers do not always expose a length, so walk
                    // to EOF when MuPDF asks for an end-relative seek.
                    while (stream.read() >= 0) position++
                    position + offset
                }
                else -> throw IOException("无效的 PDF seek 模式")
            }

            require(target >= 0) { "无效的 PDF 文件位置" }

            if (target < position) {
                stream.close()
                stream = open()
                position = 0L
            }

            var remaining = target - position
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    position += skipped
                } else {
                    if (stream.read() < 0) {
                        throw IOException("PDF 文件读取提前结束")
                    }
                    remaining--
                    position++
                }
            }
            return position
        }

        override fun position(): Long = position

        override fun read(buffer: ByteArray): Int {
            val count = stream.read(buffer)
            if (count > 0) position += count
            return count
        }

        fun closeStream() {
            runCatching { stream.close() }
        }
    }

    private val bitmapCache = object : LruCache<String, Bitmap>(cacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val lock = Any()

    private var sessionUri: Uri? = null
    private var input: UriSeekableInputStream? = null
    private var document: Document? = null

    // A small native cache avoids repeatedly decoding the same page while the
    // user moves between the main page and thumbnails.
    private val pages = object : LinkedHashMap<Int, PageEntry>(5, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageEntry>): Boolean {
            if (size <= 4) return false
            destroyEntry(eldest.value)
            return true
        }
    }

    suspend fun renderPage(
        uri: Uri,
        pageIndex: Int,
        maxDimension: Int = 2200,
        rotation: Int = 0,
    ): Bitmap = withContext(Dispatchers.IO) {
        synchronized(lock) {
            ensureSession(uri)

            val doc = requireNotNull(document) { "PDF 文档未打开" }
            require(pageIndex in 0 until doc.countPages()) { "页面索引无效" }

            val normalizedRotation = ((rotation % 360) + 360) % 360
            val key = "${uri}|p=$pageIndex|d=$maxDimension|r=$normalizedRotation"
            bitmapCache.get(key)?.let { return@withContext it }

            val entry = pageEntry(pageIndex)
            val bounds = entry.page.getBounds()
            val width = (bounds.x1 - bounds.x0).coerceAtLeast(1f)
            val height = (bounds.y1 - bounds.y0).coerceAtLeast(1f)

            val scale = (maxDimension.toFloat() / maxOf(width, height))
                .coerceIn(0.35f, 8f)
            val bitmapWidth = (width * scale).toInt().coerceAtLeast(1)
            val bitmapHeight = (height * scale).toInt().coerceAtLeast(1)

            var rendered = Bitmap.createBitmap(
                bitmapWidth,
                bitmapHeight,
                Bitmap.Config.ARGB_8888,
            )
            rendered.eraseColor(Color.WHITE)

            val device = AndroidDrawDevice(rendered, 0, 0)
            try {
                entry.displayList.run(
                    device,
                    com.artifex.mupdf.fitz.Matrix(scale, scale),
                    null as Cookie?,
                )
            } finally {
                runCatching { device.close() }
                runCatching { device.destroy() }
            }

            if (normalizedRotation != 0) {
                val matrix = Matrix().apply {
                    postRotate(normalizedRotation.toFloat())
                }
                val rotated = Bitmap.createBitmap(
                    rendered,
                    0,
                    0,
                    rendered.width,
                    rendered.height,
                    matrix,
                    true,
                )
                if (rotated !== rendered && !rendered.isRecycled) {
                    rendered.recycle()
                }
                rendered = rotated
            }

            bitmapCache.put(key, rendered)
            rendered
        }
    }

    suspend fun pageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        synchronized(lock) {
            ensureSession(uri)
            requireNotNull(document) { "PDF 文档未打开" }.countPages()
        }
    }

    fun clearCache() {
        synchronized(lock) {
            bitmapCache.evictAll()
        }
    }

    override fun close() {
        synchronized(lock) {
            bitmapCache.evictAll()
            pages.values.toList().forEach(::destroyEntry)
            pages.clear()
            runCatching { document?.destroy() }
            document = null
            input?.closeStream()
            input = null
            sessionUri = null
        }
    }

    private fun ensureSession(uri: Uri) {
        if (sessionUri == uri && document != null) return

        bitmapCache.evictAll()
        pages.values.toList().forEach(::destroyEntry)
        pages.clear()
        runCatching { document?.destroy() }

        val newInput = UriSeekableInputStream(context.contentResolver, uri)
        try {
            val newDocument = Document.openDocument(newInput, "application/pdf")
            input = newInput
            document = newDocument
            sessionUri = uri
        } catch (t: Throwable) {
            newInput.closeStream()
            throw t
        }
    }

    private fun pageEntry(pageIndex: Int): PageEntry {
        pages[pageIndex]?.let { return it }

        val doc = requireNotNull(document)
        val page = doc.loadPage(pageIndex)
        return try {
            val displayList = page.toDisplayList()
            PageEntry(page, displayList).also { pages[pageIndex] = it }
        } catch (t: Throwable) {
            runCatching { page.destroy() }
            throw t
        }
    }

    private fun destroyEntry(entry: PageEntry) {
        runCatching { entry.displayList.destroy() }
        runCatching { entry.page.destroy() }
    }

    private fun cacheSize(): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return (manager.memoryClass * 1024 / 7).coerceAtLeast(12 * 1024)
    }
}
