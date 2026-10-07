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
fun PdfEditorScreen(
    renderer: PdfRendererService,
    engine: PdfPageEngine,
    initialUri: Uri? = null,
    onBack: (() -> Unit)? = null
) {
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
    var showTools by remember { mutableStateOf(false) }
    var showPageTools by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }

    val edits = pageEdits?.snapshot().orEmpty()

    fun loadPdf(uri: Uri) {
        pdfUri = uri
        selectedPage = 0
        selectedPageId = 0L
        selectedPages = emptySet()
        scope.launch {
            busy = true
            runCatching { renderer.pageCount(uri) }
                .onSuccess {
                    pageCount = it
                    pageEdits = PdfPageEdits(it)
                    selectedPages = if (it > 0) setOf(0L) else emptySet()
                    message = "已打开 PDF，共 $it 页"
                }
                .onFailure { message = it.message ?: "打开 PDF 失败" }
            busy = false
        }
    }

    LaunchedEffect(initialUri) {
        val uri = initialUri ?: return@LaunchedEffect
        if (uri != pdfUri) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            loadPdf(uri)
        }
    }

    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadPdf(uri)
    }

    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { outputUri ->
        val input = pdfUri ?: return@rememberLauncherForActivityResult
        if (outputUri != null) {
            scope.launch {
                busy = true
                runCatching {
                    val regions = if (selectedPreset == SplitPreset.CUSTOM) listOf(customRect)
                    else engine.presetRegions(selectedPreset)
                    val requests = if (selectedPreset == SplitPreset.NONE) emptyList()
                    else selectedPages.mapNotNull { id ->
                        edits.firstOrNull { it.id == id }?.let { PageSplitRequest(it.sourceIndex, regions) }
                    }.distinctBy { it.pageIndex }
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
        val edit = edits.getOrNull(selectedPage) ?: run {
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
        val newIndex = pageEdits?.moveById(selectedPageId, delta) ?: -1
        if (newIndex >= 0) selectedPage = newIndex
    }

    fun deleteSelected() {
        pageEdits?.removeSelected(selectedPages)
        val remaining = pageEdits?.snapshot().orEmpty()
        pageCount = remaining.size
        if (remaining.isEmpty()) {
            selectedPage = 0
            selectedPageId = 0L
            selectedPages = emptySet()
        } else {
            selectedPage = selectedPage.coerceAtMost(remaining.lastIndex)
            selectedPageId = remaining[selectedPage].id
            selectedPages = setOf(selectedPageId)
        }
        message = "已删除选中页面"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onBack?.invoke() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                title = {
                    Column {
                        Text(pdfUri?.lastPathSegment ?: "PDF 阅读器", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        if (pageCount > 0) {
                            Text("第 ${selectedPage + 1} 页 · 共 $pageCount 页", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { showTools = !showTools }) { Text("编辑") }
                    TextButton(onClick = { message = "AI 助手入口已预留" }) { Text("AI") }
                    IconButton(onClick = { message = "搜索功能正在接入" }) { Icon(Icons.Default.Search, "搜索") }
                    IconButton(onClick = { message = "书签功能正在接入" }) { Icon(Icons.Default.BookmarkBorder, "书签") }
                    IconButton(onClick = { showMore = !showMore }) { Icon(Icons.Default.MoreVert, "更多") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .24f)),
                    contentAlignment = Alignment.Center
                ) {
                    pageBitmap?.let { SplitPreview(it, selectedPreset, customRect) { customRect = it } }
                        ?: if (busy) CircularProgressIndicator() else Text("打开一个 PDF 开始阅读")

                    if (pageCount > 0) {
                        Surface(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f)
                        ) {
                            Text("${selectedPage + 1} / $pageCount", color = MaterialTheme.colorScheme.surface,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                        }
                    }

                    if (showMore) {
                        Surface(Modifier.align(Alignment.TopEnd).padding(10.dp).width(230.dp),
                            shape = MaterialTheme.shapes.large, tonalElevation = 5.dp, shadowElevation = 5.dp) {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                MoreAction("保存副本", Icons.Default.ContentCopy) { showMore = false; savePdf.launch("PDF-OCA-edited.pdf") }
                                MoreAction("分享", Icons.Default.Share) { showMore = false; message = "分享功能正在接入" }
                                MoreAction("打印", Icons.Default.Print) { showMore = false; message = "打印功能正在接入" }
                                MoreAction("打开其他 PDF", Icons.Default.FolderOpen) { showMore = false; openPdf.launch(arrayOf("application/pdf")) }
                            }
                        }
                    }
                }

                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(edits, key = { it.id }) { edit ->
                            val index = edits.indexOfFirst { it.id == edit.id }
                            PageThumbnail(
                                renderer, pdfUri ?: return@items, edit.sourceIndex, edit.rotation, index,
                                index == selectedPage, edit.id in selectedPages,
                                { selectPage(index, edit.id) },
                                { delta ->
                                    val n = pageEdits?.moveById(edit.id, delta) ?: -1
                                    if (n >= 0) { selectedPage = n; selectedPageId = edit.id }
                                }
                            )
                        }
                    }
                }

                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(enabled = selectedPage > 0, onClick = { moveSelected(-1) }) { Icon(Icons.Default.ChevronLeft, "上一页") }
                        Spacer(Modifier.weight(1f))
                        FilledTonalButton(onClick = { showPageTools = !showPageTools }) {
                            Icon(Icons.Default.ViewModule, null); Spacer(Modifier.width(5.dp)); Text("页面")
                        }
                        FilledTonalButton(onClick = { showTools = !showTools }) {
                            Icon(Icons.Default.Edit, null); Spacer(Modifier.width(5.dp)); Text("工具")
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(enabled = selectedPage < pageCount - 1, onClick = { moveSelected(1) }) { Icon(Icons.Default.ChevronRight, "下一页") }
                    }
                }
            }

            FloatingActionButton(
                onClick = { showTools = !showTools },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 120.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) { Icon(Icons.Default.Edit, "编辑") }

            if (showTools) {
                ToolPanel(Modifier.align(Alignment.BottomCenter), { showTools = false },
                    { showTools = false; savePdf.launch("PDF-OCA-edited.pdf") },
                    { message = it },
                    { showTools = false; showPageTools = true })
            }

            if (showPageTools) {
                PageToolsPanel(Modifier.align(Alignment.BottomCenter), { showPageTools = false },
                    { deleteSelected(); showPageTools = false },
                    { pageEdits?.duplicateSelected(selectedPages); pageCount = pageEdits?.snapshot()?.size ?: 0; showPageTools = false },
                    { selectedPages.forEach { pageEdits?.rotateById(it) }; showPageTools = false },
                    { moveSelected(-1); showPageTools = false },
                    { moveSelected(1); showPageTools = false },
                    { showPageTools = false; showTools = true })
            }

            if (busy) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
            message?.let {
                LaunchedEffect(it) { kotlinx.coroutines.delay(1800); message = null }
                Surface(Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .86f)) {
                    Text(it, color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ToolPanel(
    modifier: Modifier,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onMessage: (String) -> Unit,
    onOpenSplit: () -> Unit
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp, shadowElevation = 8.dp) {
        Column(Modifier.padding(16.dp)) {
            PanelHeader("编辑工具", onDismiss)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolAction("编辑", Icons.Default.Edit) { onMessage("文字/图片编辑正在接入") }
                ToolAction("OCR", Icons.Default.TextFields) { onMessage("OCR 功能正在接入") }
                ToolAction("转换", Icons.Default.Transform) { onMessage("PDF 转换功能正在接入") }
                ToolAction("批注", Icons.Default.Draw) { onMessage("批注功能正在接入") }
                ToolAction("水印", Icons.Default.WaterDrop) { onMessage("水印功能正在接入") }
                ToolAction("保护", Icons.Default.Lock) { onMessage("加密/解密功能正在接入") }
                ToolAction("保存", Icons.Default.Save) { onSave() }
                ToolAction("分割", Icons.Default.ContentCut) { onOpenSplit() }
            }
        }
    }
}

@Composable
private fun PageToolsPanel(
    modifier: Modifier,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onRotate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSplit: () -> Unit
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp, shadowElevation = 8.dp) {
        Column(Modifier.padding(16.dp)) {
            PanelHeader("页面管理", onDismiss)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolAction("删除", Icons.Default.Delete, onDelete)
                ToolAction("复制", Icons.Default.ContentCopy, onDuplicate)
                ToolAction("旋转", Icons.Default.RotateRight, onRotate)
                ToolAction("上移", Icons.Default.ArrowUpward, onMoveUp)
                ToolAction("下移", Icons.Default.ArrowDownward, onMoveDown)
                ToolAction("分割", Icons.Default.ContentCut, onSplit)
            }
        }
    }
}

@Composable
private fun PanelHeader(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ToolAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(52.dp)) { Icon(icon, contentDescription = label) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MoreAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null); Spacer(Modifier.width(12.dp)); Text(label, modifier = Modifier.weight(1f))
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
