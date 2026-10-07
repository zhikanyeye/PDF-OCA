package com.zhikanyeye.pdfoca.pdf

class PdfSplitPlan {
    private val pages = linkedMapOf<Int, MutableList<CropRect>>()

    fun set(pageIndex: Int, regions: List<CropRect>) {
        require(pageIndex >= 0)
        require(regions.isNotEmpty())
        pages[pageIndex] = regions.toMutableList()
    }

    fun setPreset(pageIndex: Int, preset: SplitPreset, engine: PdfPageEngine) {
        val regions = engine.presetRegions(preset)
        require(regions.isNotEmpty()) { "自定义模式需要提供裁剪区域" }
        set(pageIndex, regions)
    }

    fun remove(pageIndex: Int) {
        pages.remove(pageIndex)
    }

    fun requests(): List<PageSplitRequest> =
        pages.entries.sortedBy { it.key }.map { PageSplitRequest(it.key, it.value.toList()) }
}

// Split plans are intentionally independent from the UI so the same engine can
// be driven by presets, draggable crop handles, or future AI-assisted detection.
