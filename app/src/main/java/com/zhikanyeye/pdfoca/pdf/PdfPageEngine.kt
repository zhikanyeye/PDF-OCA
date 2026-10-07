package com.zhikanyeye.pdfoca.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayOutputStream
import kotlin.math.max

class PdfPageEngine(private val context: Context) {
    init { PDFBoxResourceLoader.init(context) }

    fun presetRegions(preset: SplitPreset): List<CropRect> = when (preset) {
        SplitPreset.NONE -> listOf(CropRect(0f,0f,1f,1f))
        SplitPreset.HORIZONTAL_2 -> listOf(CropRect(0f,0f,.5f,1f), CropRect(.5f,0f,1f,1f))
        SplitPreset.VERTICAL_2 -> listOf(CropRect(0f,0f,1f,.5f), CropRect(0f,.5f,1f,1f))
        SplitPreset.GRID_2X2 -> grid(2,2)
        SplitPreset.GRID_3X3 -> grid(3,3)
        SplitPreset.CUSTOM -> emptyList()
    }

    private fun grid(c: Int, r: Int) = buildList {
        for (row in 0 until r) for (col in 0 until c)
            add(CropRect(col.toFloat()/c,row.toFloat()/r,(col+1f)/c,(row+1f)/r))
    }

    fun split(
        input: Uri,
        requests: List<PageSplitRequest>,
        edits: List<PageEdit> = emptyList(),
        output: ByteArrayOutputStream = ByteArrayOutputStream()
    ): ByteArray {
        context.contentResolver.openInputStream(input).use { source ->
            requireNotNull(source) { "无法打开 PDF" }
            PDDocument.load(source).use { sourceDoc ->
                PDDocument().use { result ->
                    val order = if (edits.isEmpty()) {
                        (0 until sourceDoc.numberOfPages).map { PageEdit(it) }
                    } else edits
                    val requestByPage = requests.associateBy { it.pageIndex }

                    for (edit in order) {
                        val sourcePage = sourceDoc.getPage(edit.sourceIndex)
                        val regions = requestByPage[edit.sourceIndex]?.regions?.ifEmpty {
                            listOf(CropRect(0f,0f,1f,1f))
                        } ?: listOf(CropRect(0f,0f,1f,1f))
                        for (region in regions) {
                            val page = result.importPage(sourcePage, edit.sourceIndex)
                            applyCrop(page, region)
                            if (edit.rotation != 0) {
                                page.rotation = ((sourcePage.rotation + edit.rotation) % 360 + 360) % 360
                            }
                        }
                    }
                    require(result.numberOfPages > 0) { "至少保留一页" }
                    result.save(output)
                }
            }
        }
        return output.toByteArray()
    }

    private fun applyCrop(page: PDPage, region: CropRect) {
        val box = page.cropBox
        val width = box.width
        val height = box.height
        val left = box.lowerLeftX + width * region.left
        val right = box.lowerLeftX + width * region.right
        val top = box.upperRightY - height * region.top
        val bottom = box.upperRightY - height * region.bottom
        val crop = PDRectangle(max(1f,right-left), max(1f,top-bottom))
        crop.lowerLeftX = left
        crop.lowerLeftY = bottom
        page.cropBox = crop
    }
}
