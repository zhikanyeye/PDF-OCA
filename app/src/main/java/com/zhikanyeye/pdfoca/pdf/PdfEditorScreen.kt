package com.zhikanyeye.pdfoca.pdf

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun PdfEditorScreen(renderer: PdfRendererService, engine: PdfPageEngine) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pdfUri by remember { mutableStateOf<Uri?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var selectedPage by remember { mutableIntStateOf(0) }
    var selectedPageId by remember { mutableLongStateOf(0L) }
    var selectedPages by remember { mutableStateOf(setOf<Long>()) }
    var pageEdits by remember { mutableStateOf<PdfPageEdits?>(null) }
    var selectedPreset by remember { mutableStateOf(SplitPreset.NONE) }
    var customRect by remember { mutableStateOf(CropRect(0f, 0f, 1f, 1f)) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val edits = pageEdits?.snapshot().orEmpty()

    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pdfUri = uri
            selectedPage = 0
            selectedPageId = 0L
            selectedPages = setOf(0L)
            scope.launch {
                busy = true
                runCatching { renderer.pageCount(uri) }
                    .onSuccess {
                        pageCount = it
                        pageEdits = PdfPageEdits(it)
                        selectedPage = 0
                        selectedPageId = 0L
                        selectedPages = if (it > 0) setOf(0L) else emptySet()
                        message = "已打开 PDF，共 ${it} 页"
                    }
                    .onFailure { message = it.message ?: "打开 PDF 失败" }
                busy = false
            }
        }
    }

    val savePdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { outputUri ->
        val input = pdfUri ?: return@rememberLauncherForActivityResult
        if (outputUri != null) {
            scope.launch {
                busy = true
                runCatching {
                    val regions = if (selectedPreset == SplitPreset.CUSTOM) {
                        listOf(customRect)
                    } else {
                        engine.presetRegions(selectedPreset)
                    }
                    val requests = if (selectedPreset == SplitPreset.NONE) {
                        emptyList()
                    } else {
                        selectedPages.mapNotNull { id ->
                            edits.firstOrNull { it.id == id }?.let {
                                PageSplitRequest(it.sourceIndex, regions)
                            }
                        }.distinctBy { it.pageIndex }
                    }
                    val bytes = engine.split(input, requests, edits)
                    context.contentResolver.openOutputStream(outputUri).use { out ->
                        requireNotNull(out) { "无法创建输出文件" }
                        out.write(bytes)
                    }
                }.onSuccess { message = "导出完成" }
                    .onFailure { message = it.message ?: "导出失败" }
                busy = false
            }
        }
    }

    LaunchedEffect(pdfUri, selectedPage, selectedPageId, edits) {
        val uri = pdfUri ?: return@LaunchedEffect
        val edit = edits.getOrNull(selectedPage)
        if (edit == null) {
            pageBitmap = null
            return@LaunchedEffect
        }
        busy = true
        runCatching { renderer.renderPage(uri, edit.sourceIndex, 1800, edit.rotation) }
            .onSuccess { pageBitmap = it }
            .onFailure { message = it.message ?: "页面渲染失败" }
        busy = false
    }

    fun selectPage(index: Int, id: Long) {
        selectedPage = index
        selectedPageId = id
        selectedPages = setOf(id)
    }

    fun moveSelected(delta: Int) {
        val id = selectedPageId
        val newIndex = pageEdits?.moveById(id, delta) ?: -1
        if (newIndex >= 0) {
            selectedPage = newIndex
            message = if (delta < 0) "页面已上移" else "页面已下移"
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openPdf.launch(arrayOf("application/pdf")) }) {
                Text("打开 PDF")
            }
            Button(
                enabled = pdfUri != null && edits.isNotEmpty() && !busy,
                onClick = { savePdf.launch("PDF-OCA-edited.pdf") }
            ) {
                Text("导出")
            }
        }

        Spacer(Modifier.height(8.dp))

        if (pdfUri != null) {
            Text(
                if (pageCount > 0) "第 ${selectedPage + 1} / $pageCount 页" else "暂无页面",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))

            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(
                    items = edits,
                    key = { it.id }
                ) { edit ->
                    val index = edits.indexOfFirst { it.id == edit.id }
                    PageThumbnail(
                        renderer = renderer,
                        pdfUri = pdfUri!!,
                        pageIndex = edit.sourceIndex,
                        rotation = edit.rotation,
                        position = index,
                        selected = index == selectedPage,
                        marked = edit.id in selectedPages,
                        onClick = { selectPage(index, edit.id) },
                        onMove = { delta ->
                            val newIndex = pageEdits?.moveById(edit.id, delta) ?: -1
                            if (newIndex >= 0) {
                                selectedPage = newIndex
                                selectedPageId = edit.id
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("第 " + (selectedPage + 1) + " / " + pageCount, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(12.dp))
                    Text("已选 " + selectedPages.size + " 页", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { selectedPages = setOf(selectedPageId) }) { Text("仅选当前") }
                }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SplitPreset.entries.forEach { preset ->
                    val label = when (preset) {
                        SplitPreset.NONE -> "预览"
                        SplitPreset.HORIZONTAL_2 -> "左右 2"
                        SplitPreset.VERTICAL_2 -> "上下 2"
                        SplitPreset.GRID_2X2 -> "2×2"
                        SplitPreset.GRID_3X3 -> "3×3"
                        SplitPreset.CUSTOM -> "自由裁剪"
                    }
                    if (preset == selectedPreset) {
                        Button(onClick = { selectedPreset = preset }) { Text(label) }
                    } else {
                        OutlinedButton(onClick = {
                            selectedPreset = preset
                            if (preset == SplitPreset.CUSTOM) customRect = CropRect(0f, 0f, 1f, 1f)
                        }) { Text(label) }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    enabled = selectedPages.isNotEmpty(),
                    onClick = {
                        val deletingSelected = selectedPageId in selectedPages
                        pageEdits?.removeSelected(selectedPages)
                        pageCount = pageEdits?.snapshot()?.size ?: 0
                        selectedPages = emptySet()
                        val remaining = pageEdits?.snapshot().orEmpty()
                        if (remaining.isEmpty()) {
                            selectedPage = 0
                            selectedPageId = 0L
                        } else if (deletingSelected) {
                            selectedPage = selectedPage.coerceAtMost(remaining.lastIndex)
                            selectedPageId = remaining[selectedPage].id
                        }
                        message = "已删除选中页面"
                    }
                ) { Text("删除") }

                OutlinedButton(
                    enabled = selectedPages.isNotEmpty(),
                    onClick = {
                        pageEdits?.duplicateSelected(selectedPages)
                        pageCount = pageEdits?.snapshot()?.size ?: 0
                        message = "已复制选中页面"
                    }
                ) { Text("复制") }

                OutlinedButton(
                    enabled = selectedPages.isNotEmpty(),
                    onClick = {
                        selectedPages.forEach { pageEdits?.rotateById(it) }
                        message = "已旋转 90°"
                    }
                ) { Text("旋转") }

                OutlinedButton(enabled = selectedPage > 0, onClick = { moveSelected(-1) }) {
                    Text("上移")
                }
                OutlinedButton(
                    enabled = selectedPage < pageCount - 1,
                    onClick = { moveSelected(1) }
                ) { Text("下移") }
            }

            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                pageBitmap?.let { bitmap ->
                    SplitPreview(bitmap, selectedPreset, customRect) {
                        customRect = it
                    }
                } ?: CircularProgressIndicator()
            }

            Spacer(Modifier.height(8.dp))
            if (selectedPreset != SplitPreset.NONE) {
                val count = if (selectedPreset == SplitPreset.CUSTOM) 1
                else engine.presetRegions(selectedPreset).size
                Text("当前页面将生成 $count 个逻辑页面。")
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("选择一个 PDF 开始编辑")
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun PageThumbnail(
    renderer: PdfRendererService,
    pdfUri: Uri,
    pageIndex: Int,
    rotation: Int,
    position: Int,
    selected: Boolean,
    marked: Boolean,
    onClick: () -> Unit,
    onMove: (Int) -> Unit
) {
    var bitmap by remember(pdfUri, pageIndex, rotation) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pdfUri, pageIndex, rotation) {
        runCatching { renderer.renderPage(pdfUri, pageIndex, 300, rotation) }
            .onSuccess { bitmap = it }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(92.dp).height(118.dp)
                .clip(MaterialTheme.shapes.small)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
                .pointerInput(position) {
                    var accumulatedX = 0f
                    detectDragGesturesAfterLongPress(
                        onDragStart = { accumulatedX = 0f },
                        onDragCancel = { accumulatedX = 0f },
                        onDragEnd = { accumulatedX = 0f }
                    ) { change, dragAmount ->
                        change.consume()
                        accumulatedX += dragAmount.x
                        if (abs(accumulatedX) >= 42f) {
                            val direction = if (accumulatedX > 0f) 1 else -1
                            onMove(direction)
                            accumulatedX = 0f
                        }
                    }
                }
                .padding(2.dp)
        ) {
            bitmap?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))

            if (marked) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(4.dp).size(10.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Text("${position + 1}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SplitPreview(
    bitmap: Bitmap,
    preset: SplitPreset,
    customRect: CropRect,
    onCustomRectChange: (CropRect) -> Unit
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        val ratio = bitmap.width.toFloat() / bitmap.height
        val width = minOf(maxWidth.value, maxHeight.value * ratio).dp
        val height = width / ratio

        Box(Modifier.size(width, height)) {
            Image(bitmap.asImageBitmap(), "PDF 页面预览", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)

            val regions = when (preset) {
                SplitPreset.CUSTOM -> listOf(customRect)
                SplitPreset.NONE -> emptyList()
                else -> previewRegions(preset)
            }

            regions.forEach { region ->
                Box(
                    Modifier.offset(width * region.left, height * region.top)
                        .size(
                            width * (region.right - region.left),
                            height * (region.bottom - region.top)
                        )
                        .border(1.5.dp, MaterialTheme.colorScheme.primary)
                )
            }

            if (preset == SplitPreset.CUSTOM) {
                CropEditor(
                    width = width,
                    height = height,
                    rect = customRect,
                    onChange = onCustomRectChange
                )
            }
        }
    }
}

@Composable
private fun CropEditor(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    rect: CropRect,
    onChange: (CropRect) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.offset(width * rect.left, height * rect.top)
                .size(
                    width * (rect.right - rect.left),
                    height * (rect.bottom - rect.top)
                )
                .border(2.dp, MaterialTheme.colorScheme.primary)
        )

        CropHandle(width, height, rect.left, rect.top) { dx, dy ->
            onChange(
                CropRect(
                    (rect.left + dx).coerceIn(0f, rect.right - .03f),
                    (rect.top + dy).coerceIn(0f, rect.bottom - .03f),
                    rect.right,
                    rect.bottom
                )
            )
        }
        CropHandle(width, height, rect.right, rect.top) { dx, dy ->
            onChange(
                CropRect(
                    rect.left,
                    (rect.top + dy).coerceIn(0f, rect.bottom - .03f),
                    (rect.right + dx).coerceIn(rect.left + .03f, 1f),
                    rect.bottom
                )
            )
        }
        CropHandle(width, height, rect.left, rect.bottom) { dx, dy ->
            onChange(
                CropRect(
                    (rect.left + dx).coerceIn(0f, rect.right - .03f),
                    rect.top,
                    rect.right,
                    (rect.bottom + dy).coerceIn(rect.top + .03f, 1f)
                )
            )
        }
        CropHandle(width, height, rect.right, rect.bottom) { dx, dy ->
            onChange(
                CropRect(
                    rect.left,
                    rect.top,
                    (rect.right + dx).coerceIn(rect.left + .03f, 1f),
                    (rect.bottom + dy).coerceIn(rect.top + .03f, 1f)
                )
            )
        }
    }
}

@Composable
private fun CropHandle(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    x: Float,
    y: Float,
    onDrag: (Float, Float) -> Unit
) {
    Box(
        Modifier.offset(width * x - 9.dp, height * y - 9.dp)
            .size(18.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(x, y) {
                detectDragGesturesAfterLongPress { change, drag ->
                    change.consume()
                    onDrag(
                        drag.x / width.toPx(),
                        drag.y / height.toPx()
                    )
                }
            }
    )
}

private fun previewRegions(preset: SplitPreset): List<CropRect> = when (preset) {
    SplitPreset.HORIZONTAL_2 -> listOf(
        CropRect(0f, 0f, .5f, 1f),
        CropRect(.5f, 0f, 1f, 1f)
    )
    SplitPreset.VERTICAL_2 -> listOf(
        CropRect(0f, 0f, 1f, .5f),
        CropRect(0f, .5f, 1f, 1f)
    )
    SplitPreset.GRID_2X2 -> buildList {
        for (r in 0..1) for (c in 0..1) {
            add(CropRect(c / 2f, r / 2f, (c + 1) / 2f, (r + 1) / 2f))
        }
    }
    SplitPreset.GRID_3X3 -> buildList {
        for (r in 0..2) for (c in 0..2) {
            add(CropRect(c / 3f, r / 3f, (c + 1) / 3f, (r + 1) / 3f))
        }
    }
    else -> emptyList()
}
