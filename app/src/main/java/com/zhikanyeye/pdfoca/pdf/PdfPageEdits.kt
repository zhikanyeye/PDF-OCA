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

    fun removeSelected(ids: Set<Long>) {
        if (ids.isEmpty()) return
        pages.removeAll { it.id in ids }
    }

    fun duplicateSelected(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val result = mutableListOf<PageEdit>()
        pages.forEach { page ->
            result += page
            if (page.id in ids) result += page.copy(id = nextId++)
        }
        pages.clear()
        pages.addAll(result)
    }

    fun rotateById(id: Long, delta: Int = 90) {
        val i = pages.indexOfFirst { it.id == id }
        if (i >= 0) {
            pages[i] = pages[i].copy(rotation = (pages[i].rotation + delta + 360) % 360)
        }
    }

    fun move(from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices || from == to) return
        val item = pages.removeAt(from)
        pages.add(to.coerceIn(0, pages.size), item)
    }

    fun moveById(id: Long, delta: Int): Int {
        val from = pages.indexOfFirst { it.id == id }
        if (from < 0) return -1
        val to = (from + delta).coerceIn(0, pages.lastIndex)
        if (from == to) return from
        move(from, to)
        return to
    }
}
