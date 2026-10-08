package com.zhikanyeye.pdfoca.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

class PdfPageEngine(private val context: Context) {
    init { PDFBoxResourceLoader.init(context) }

    fun presetRegions(preset: SplitPreset): List<CropRect> = when (preset) {
        SplitPreset.NONE -> listOf(CropRect(0f,0f,1f,1f))
        SplitPreset.HORIZONTAL_2 -> listOf(CropRect(0f,0f,.5f,1f), CropRect(.5f,0f,1f,1f))
        SplitPreset.VERTICAL_2 -> listOf(CropRect(0f,0f,1f,.5f), CropRect(0f,.5f,1f,1f))
        SplitPreset.GRID_2X2 -> grid(2,2)
        SplitPreset.GRID_3X3 -> grid(3,3)
        SplitPreset.CUSTOM, SplitPreset.LINE -> emptyList()
    }

    private fun grid(c: Int, r: Int) = buildList {
        for (row in 0 until r) for (col in 0 until c)
            add(CropRect(col.toFloat()/c,row.toFloat()/r,(col+1f)/c,(row+1f)/r))
    }

    /**
     * Exports the current page order/splits and persists content-level edits.
     * This is intentionally separate from the renderer: rendering is read-only,
     * while this method creates a new PDF document.
     */
    fun export(
        input: Uri,
        edits: List<PageEdit>,
        splitRequests: List<PageSplitRequest> = emptyList(),
        contentEdits: List<PdfContentEdit> = emptyList(),
        output: ByteArrayOutputStream = ByteArrayOutputStream()
    ): ByteArray {
        context.contentResolver.openInputStream(input).use { source ->
            requireNotNull(source) { "无法打开 PDF" }
            PDDocument.load(source).use { sourceDoc ->
                PDDocument().use { result ->
                    val order = if (edits.isEmpty()) {
                        (0 until sourceDoc.numberOfPages).map { PageEdit(it.toLong(), it) }
                    } else edits
                    val requestByPage = splitRequests.associateBy { it.pageIndex }

                    for (edit in order) {
                        val sourcePage = sourceDoc.getPage(edit.sourceIndex)
                        val regions = requestByPage[edit.sourceIndex]?.regions?.ifEmpty {
                            listOf(CropRect(0f,0f,1f,1f))
                        } ?: listOf(CropRect(0f,0f,1f,1f))

                        for (region in regions) {
                            val page = result.importPage(sourcePage)
                            applyCrop(page, region)
                            applyContentEdits(
                                result,
                                page,
                                region,
                                contentEdits.filter { it.pageSourceIndex == edit.sourceIndex }
                            )
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

    fun split(
        input: Uri,
        requests: List<PageSplitRequest>,
        edits: List<PageEdit> = emptyList(),
        output: ByteArrayOutputStream = ByteArrayOutputStream()
    ): ByteArray = export(input, edits, requests, emptyList(), output)

    private fun applyContentEdits(
        doc: PDDocument,
        page: PDPage,
        region: CropRect,
        edits: List<PdfContentEdit>
    ) {
        if (edits.isEmpty()) return
        val box = page.cropBox
        val pageWidth = box.width
        val pageHeight = box.height

        fun mapX(x: Float) = ((x - region.left) / (region.right - region.left)).coerceIn(0f, 1f)
        fun mapY(y: Float) = ((y - region.top) / (region.bottom - region.top)).coerceIn(0f, 1f)
        fun inside(x: Float, y: Float) =
            x >= region.left && x <= region.right && y >= region.top && y <= region.bottom

        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
            edits.forEach { edit ->
                when (edit) {
                    is PdfTextEdit -> {
                        if (!inside(edit.x, edit.y)) return@forEach
                        val x = pageWidth * mapX(edit.x)
                        val y = pageHeight * (1f - mapY(edit.y))
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, edit.fontSize.coerceIn(4f, 144f))
                        stream.setNonStrokingColor(
                            (edit.colorArgb shr 16) and 0xFF,
                            (edit.colorArgb shr 8) and 0xFF,
                            edit.colorArgb and 0xFF
                        )
                        stream.newLineAtOffset(x, (y - edit.fontSize).coerceAtLeast(0f))
                        stream.showText(edit.text)
                        stream.endText()
                    }

                    is PdfImageEdit -> {
                        val right = edit.x + edit.width
                        val bottom = edit.y + edit.height
                        if (right <= region.left || edit.x >= region.right ||
                            bottom <= region.top || edit.y >= region.bottom) return@forEach

                        val x1 = mapX(max(edit.x, region.left))
                        val y1 = mapY(max(edit.y, region.top))
                        val x2 = mapX(min(right, region.right))
                        val y2 = mapY(min(bottom, region.bottom))
                        if (x2 <= x1 || y2 <= y1) return@forEach

                        val image = PDImageXObject.createFromByteArray(doc, edit.bytes, "pdfoca-image")
                        val drawX = pageWidth * x1
                        val drawY = pageHeight * (1f - y2)
                        stream.drawImage(
                            image,
                            drawX,
                            drawY,
                            pageWidth * (x2 - x1),
                            pageHeight * (y2 - y1)
                        )
                    }

                    is PdfWatermarkEdit -> {
                        // Watermarks are page-level by design. During a split, apply
                        // them only to the full-page output to avoid duplicated fragments.
                        if (region != CropRect(0f,0f,1f,1f)) return@forEach
                        val x = pageWidth / 2f
                        val y = pageHeight / 2f
                        stream.saveGraphicsState()
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA_BOLD, edit.fontSize.coerceIn(8f, 160f))
                        stream.setNonStrokingColor(
                            (edit.colorArgb shr 16) and 0xFF,
                            (edit.colorArgb shr 8) and 0xFF,
                            edit.colorArgb and 0xFF
                        )
                        stream.setTextMatrix(
                            Matrix.getRotateInstance(
                                Math.toRadians(edit.rotation.toDouble()),
                                x,
                                y
                            )
                        )
                        stream.showText(edit.text)
                        stream.endText()
                        stream.restoreGraphicsState()
                    }

                    is PdfAnnotationEdit -> {
                        val r = edit.rect
                        val x1 = mapX(r.left)
                        val y1 = mapY(r.top)
                        val x2 = mapX(r.right)
                        val y2 = mapY(r.bottom)
                        if (x2 <= x1 || y2 <= y1) return@forEach
                        stream.setNonStrokingColor(
                            (edit.colorArgb shr 16) and 0xFF,
                            (edit.colorArgb shr 8) and 0xFF,
                            edit.colorArgb and 0xFF
                        )
                        stream.addRect(
                            pageWidth * x1,
                            pageHeight * (1f - y2),
                            pageWidth * (x2 - x1),
                            pageHeight * (y2 - y1)
                        )
                        stream.fill()
                    }
                }
            }
        }
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
