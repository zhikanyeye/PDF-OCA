package com.zhikanyeye.pdfoca.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfRendererService(private val context: Context) {
    init { PDFBoxResourceLoader.init(context) }

    suspend fun renderPage(uri: Uri, pageIndex: Int, maxDimension: Int = 1800): Bitmap =
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri).use { stream ->
                requireNotNull(stream) { "无法打开 PDF" }
                PDDocument.load(stream).use { document ->
                    require(pageIndex in 0 until document.numberOfPages)
                    val page = document.getPage(pageIndex)
                    val box = page.cropBox
                    val longest = maxOf(box.width, box.height)
                    val scale = (maxDimension / longest).coerceAtMost(4f)
                    val renderer = PDFRenderer(document)
                    renderer.renderImage(pageIndex, scale).also {
                        if (it.config == null) it.setConfig(Bitmap.Config.ARGB_8888)
                    }
                }
            }
        }

    suspend fun pageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream)
            PDDocument.load(stream).use { it.numberOfPages }
        }
    }
}
