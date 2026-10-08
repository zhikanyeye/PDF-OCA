package com.zhikanyeye.pdfoca.pdf

enum class SplitPreset { NONE, HORIZONTAL_2, VERTICAL_2, GRID_2X2, GRID_3X3, CUSTOM, LINE }

data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f)
        require(right > left && bottom > top)
    }
}

data class PageSplitRequest(
    val pageIndex: Int,
    val regions: List<CropRect>
)

data class SplitPreviewRegion(
    val pageIndex: Int,
    val region: CropRect,
    val outputIndex: Int
)
