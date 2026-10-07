package com.zhikanyeye.pdfoca.pdf

/**
 * Persistent content-level edits. Coordinates are normalized to the page's
 * visible crop box, with (0,0) at the top-left.
 */
sealed interface PdfContentEdit {
    val pageSourceIndex: Int
}

data class PdfTextEdit(
    override val pageSourceIndex: Int,
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float = .4f,
    val fontSize: Float = 16f,
    val colorArgb: Int = 0xFF202124.toInt()
) : PdfContentEdit

data class PdfImageEdit(
    override val pageSourceIndex: Int,
    val bytes: ByteArray,
    val x: Float,
    val y: Float,
    val width: Float = .3f,
    val height: Float = .2f
) : PdfContentEdit

data class PdfWatermarkEdit(
    override val pageSourceIndex: Int,
    val text: String,
    val fontSize: Float = 28f,
    val colorArgb: Int = 0x66333333,
    val rotation: Float = -35f
) : PdfContentEdit

data class PdfAnnotationEdit(
    override val pageSourceIndex: Int,
    val rect: CropRect,
    val colorArgb: Int = 0x66FFF176,
    val opacity: Float = .45f
) : PdfContentEdit
