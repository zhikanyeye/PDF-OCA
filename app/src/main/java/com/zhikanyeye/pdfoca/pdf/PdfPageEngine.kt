package com.zhikanyeye.pdfoca.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDRectangle
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max

class PdfPageEngine(private val context: Context) {

    init {
        PDFBoxResourceLoader.init(context)
    }

    fun presetRegions(preset: SplitPreset): List<CropRect> = when (preset) {
        SplitPreset.NONE -> listOf(CropRect(0f, 0f, 1f, 1f))
        SplitPreset.HORIZONTAL_2 -> listOf(
            CropRect(0f, 0f, 0.5f, 1f),
            CropRect(0.5f, 0f, 1f, 1f)
        )
        SplitPreset.VERTICAL_2 -> listOf(
            CropRect(0f, 0f, 1f, 0.5f),
            CropRect(0f, 0.5f, 1f, 1f)
        )
        SplitPreset.GRID_2X2 -> grid(2, 2)
        SplitPreset.GRID_3X3 -> grid(3, 3)
        SplitPreset.CUSTOM -> emptyList()
    }

    private fun grid(columns: Int, rows: Int): List<CropRect> {
        val result = ArrayList<CropRect>(columns * rows)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                result += CropRect(
                    column.toFloat() / columns,
                    row.toFloat() / rows,
                    (column + 1).toFloat() / columns,
                    (row + 1).toFloat() / rows
                )
            }
        }
        return result
    }

    fun split(
        input: Uri,
        requests: List<PageSplitRequest>,
        output: ByteArrayOutputStream = ByteArrayOutputStream()
    ): ByteArray {
        context.contentResolver.openInputStream(input).use { source ->
            requireNotNull(source) { "无法打开 PDF" }
            PDDocument.load(source).use { sourceDoc ->
                PDDocument().use { resultDoc ->
                    val requestByPage = requests.associateBy { it.pageIndex }

                    for (index in 0 until sourceDoc.numberOfPages) {
                        val sourcePage = sourceDoc.getPage(index)
                        val request = requestByPage[index]
                        val regions = request?.regions?.ifEmpty {
                            listOf(CropRect(0f, 0f, 1f, 1f))
                        } ?: listOf(CropRect(0f, 0f, 1f, 1f))

                        for (region in regions) {
                            val page = resultDoc.importPage(sourcePage)
                            applyCrop(page, region)
                        }
                    }

                    resultDoc.save(output)
                }
            }
        }
        return output.toByteArray()
    }

    private fun applyCrop(page: PDPage, region: CropRect) {
        val box = page.cropBox
        val width = box.width
        val height = box.height

        // UI coordinates use top-left origin; PDF coordinates use bottom-left.
        val left = box.lowerLeftX + width * region.left
        val right = box.lowerLeftX + width * region.right
        val top = box.upperRightY - height * region.top
        val bottom = box.upperRightY - height * region.bottom

        val crop = PDRectangle(
            max(1f, right - left),
            max(1f, top - bottom)
        )
        crop.lowerLeftX = left
        crop.lowerLeftY = bottom

        page.cropBox = crop
    }
}
