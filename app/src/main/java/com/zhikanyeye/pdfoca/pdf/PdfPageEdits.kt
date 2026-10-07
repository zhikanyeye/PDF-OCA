package com.zhikanyeye.pdfoca.pdf

import androidx.compose.runtime.mutableStateListOf

data class PageEdit(
    val id: Long,
    val sourceIndex: Int,
    val rotation: Int = 0
)

class PdfPageEdits(initialCount: Int) {
    private var nextId = initialCount.toLong()
    private val pages = mutableStateListOf<PageEdit>().apply {
        repeat(initialCount) { add(PageEdit(it.toLong(), it)) }
    }

    fun snapshot(): List<PageEdit> = pages.toList()

    fun removeSelected(indices: Set<Int>) {
        pages.removeAll { it.sourceIndex in indices }
    }

    fun duplicateSelected(indices: Set<Int>) {
        val result = mutableListOf<PageEdit>()
        pages.forEach {
            result += it
            if (it.sourceIndex in indices) result += it.copy(id = nextId++)
        }
        pages.clear()
        pages.addAll(result)
    }

    fun rotateBySource(index: Int, delta: Int = 90) {
        val i = pages.indexOfFirst { it.sourceIndex == index }
        if (i >= 0) pages[i] = pages[i].copy(rotation = (pages[i].rotation + delta + 360) % 360)
    }

    fun move(from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices || from == to) return
        val item = pages.removeAt(from)
        pages.add(to.coerceIn(0, pages.size), item)
    }
}
