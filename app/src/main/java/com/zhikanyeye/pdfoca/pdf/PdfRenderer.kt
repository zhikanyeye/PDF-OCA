package com.zhikanyeye.pdfoca.pdf

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfRendererService(private val context: Context) {
    private val cache = object : LruCache<String, Bitmap>(cacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    init { PDFBoxResourceLoader.init(context) }

    suspend fun renderPage(uri: Uri, pageIndex: Int, maxDimension: Int = 1800, rotation: Int = 0): Bitmap =
        withContext(Dispatchers.IO) {
            val normalizedRotation = ((rotation % 360) + 360) % 360
            val key = "${uri}|p=$pageIndex|d=$maxDimension|r=$normalizedRotation"
            synchronized(cache) { cache.get(key)?.let { return@withContext it } }

            val bitmap = context.contentResolver.openInputStream(uri).use { stream ->
                requireNotNull(stream) { "无法打开 PDF" }
                PDDocument.load(stream).use { document ->
                    require(pageIndex in 0 until document.numberOfPages) { "页面索引无效" }
                    val page = document.getPage(pageIndex)
                    val box = page.cropBox
                    val longest = maxOf(box.width, box.height)
                    val scale = (maxDimension / longest).coerceIn(0.35f, 4f)
                    val rendered = PDFRenderer(document).renderImage(pageIndex, scale)
                    if (normalizedRotation == 0) rendered
                    else {
                        val matrix = Matrix().apply { postRotate(normalizedRotation.toFloat()) }
                        Bitmap.createBitmap(rendered, 0, 0, rendered.width, rendered.height, matrix, true)
                            .also { if (it !== rendered && !rendered.isRecycled) rendered.recycle() }
                    }
                }
            }
            synchronized(cache) { cache.put(key, bitmap) }
            bitmap
        }

    fun clearCache() { synchronized(cache) { cache.evictAll() } }

    suspend fun pageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "无法打开 PDF" }
            PDDocument.load(stream).use { it.numberOfPages }
        }
    }

    private fun cacheSize(): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return (manager.memoryClass * 1024 / 8).coerceAtLeast(8 * 1024)
    }
}