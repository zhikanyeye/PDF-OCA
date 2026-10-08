@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zhikanyeye.pdfoca.pdf

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
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
    var documentName by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var selectedPage by remember { mutableIntStateOf(0) }
    var selectedPageId by remember { mutableLongStateOf(0L) }
    var selectedPages by remember { mutableStateOf(setOf<Long>()) }
    var pageEdits by remember { mutableStateOf<PdfPageEdits?>(null) }
    var selectedPreset by remember { mutableStateOf(SplitPreset.NONE) }
    var customRects by remember { mutableStateOf(listOf(CropRect(0f, 0f, 1f, 1f))) }
    var selectedCropIndex by remember { mutableIntStateOf(0) }
    var splitRatioX by remember { mutableFloatStateOf(.5f) }
    var splitRatioY by remember { mutableFloatStateOf(.5f) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var contentEdits by remember { mutableStateOf<List<PdfContentEdit>>(emptyList()) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showWatermarkDialog by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }
    var watermarkInput by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var showTools by remember { mutableStateOf(false) }
    var showPageTools by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var renderQuality by remember { mutableFloatStateOf(1800f) }
    var showSplit by remember { mutableStateOf(false) }

    val edits = pageEdits?.snapshot().orEmpty()

    fun loadPdf(uri: Uri) {
        pdfUri = uri
        documentName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() && !it.startsWith("document:") }
            ?: "PDF 文档"
        pageBitmap = null
        renderError = null
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
                .onFailure {
                    renderError = "打开 PDF 失败：${it.message ?: it.javaClass.simpleName}"
                    message = renderError
                }
            busy = false
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取图片")
                }.onSuccess { bytes ->
                    edits.getOrNull(selectedPage)?.let { page ->
                        contentEdits = contentEdits + PdfImageEdit(page.sourceIndex, bytes, .35f, .35f, .3f, .22f)
                        message = "图片已加入当前页面，保存后写入 PDF"
                    }
                }.onFailure { message = it.message ?: "图片读取失败" }
            }
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
                    val regions = if (selectedPreset == SplitPreset.CUSTOM) customRects
                    else previewRegions(selectedPreset, splitRatioX, splitRatioY)
                    val requests = if (selectedPreset == SplitPreset.NONE) emptyList()
                    else selectedPages.mapNotNull { id ->
                        edits.firstOrNull { it.id == id }?.let { PageSplitRequest(it.sourceIndex, regions) }
                    }.distinctBy { it.pageIndex }
                    val bytes = engine.export(
                        input = input,
                        edits = edits,
                        splitRequests = requests,
                        contentEdits = contentEdits
                    )
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

    LaunchedEffect(pdfUri, selectedPage, selectedPageId, edits, showSplit) {
        if (!showSplit) return@LaunchedEffect
        val uri = pdfUri ?: return@LaunchedEffect
        val edit = edits.getOrNull(selectedPage) ?: run {
            pageBitmap = null
            return@LaunchedEffect
        }
        busy = true
        renderError = null
        runCatching { renderer.renderPage(uri, edit.sourceIndex, 1200, edit.rotation) }
            .onSuccess { pageBitmap = it; renderError = null }
            .onFailure {
                pageBitmap = null
                renderError = "第 ${selectedPage + 1} 页渲染失败：${it.message ?: it.javaClass.simpleName}"
                message = renderError
            }
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

    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("添加文字") },
            text = { OutlinedTextField(value = textInput, onValueChange = { textInput = it }, label = { Text("文字内容") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = textInput.isNotBlank(), onClick = {
                    edits.getOrNull(selectedPage)?.let { page ->
                        contentEdits = contentEdits + PdfTextEdit(page.sourceIndex, textInput, .12f, .18f)
                        message = "文字已加入当前页面，保存后写入 PDF"
                    }
                    showTextDialog = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showTextDialog = false }) { Text("取消") } }
        )
    }

    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("阅读清晰度") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${renderQuality.toInt()} px · 数值越高越清晰，也会占用更多内存")
                    Slider(
                        value = renderQuality,
                        onValueChange = { renderQuality = it },
                        valueRange = 900f..3000f,
                        steps = 6
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("流畅")
                        Text("高清")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) { Text("完成") }
            },
            dismissButton = {
                TextButton(onClick = { renderQuality = 1800f }) { Text("恢复默认") }
            }
        )
    }

    if (showWatermarkDialog) {
        AlertDialog(
            onDismissRequest = { showWatermarkDialog = false },
            title = { Text("添加水印") },
            text = { OutlinedTextField(value = watermarkInput, onValueChange = { watermarkInput = it }, label = { Text("水印文字") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = watermarkInput.isNotBlank(), onClick = {
                    edits.getOrNull(selectedPage)?.let { page ->
                        contentEdits = contentEdits + PdfWatermarkEdit(page.sourceIndex, watermarkInput)
                        message = "水印已加入当前页面，保存后写入 PDF"
                    }
                    showWatermarkDialog = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showWatermarkDialog = false }) { Text("取消") } }
        )
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
                        Text(documentName ?: "PDF 阅读器", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        if (pageCount > 0) {
                            Text("第 ${selectedPage + 1} 页 · 共 $pageCount 页", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { showQualityDialog = true }) { Text("清晰度") }
                    TextButton(onClick = { showTools = !showTools }) { Text("编辑") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                if (showSplit) {
                    Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .24f)), contentAlignment = Alignment.Center) {
                        pageBitmap?.let { bitmap ->
                            SplitPreview(bitmap, selectedPreset, customRects, selectedCropIndex, splitRatioX, splitRatioY,
                                onSelectCrop = { selectedCropIndex = it },
                                onRatioXChange = { splitRatioX = it },
                                onRatioYChange = { splitRatioY = it },
                                onLineDraw = { start, end ->
                                    val dx = abs(end.x - start.x)
                                    val dy = abs(end.y - start.y)
                                    if (dx >= dy) {
                                        splitRatioY = ((start.y + end.y) / 2f).coerceIn(.1f, .9f)
                                        selectedPreset = SplitPreset.VERTICAL_2
                                        message = "已按直线位置分割为上下两部分"
                                    } else {
                                        splitRatioX = ((start.x + end.x) / 2f).coerceIn(.1f, .9f)
                                        selectedPreset = SplitPreset.HORIZONTAL_2
                                        message = "已按直线位置分割为左右两部分"
                                    }
                                },
                                onCustomRectChange = { index, rect -> customRects = customRects.toMutableList().also { it[index] = rect } })
                        } ?: if (busy) CircularProgressIndicator() else Text(renderError ?: "正在准备分割预览")
                    }
                } else if (pdfUri != null && edits.isNotEmpty()) {
                    val readerState = rememberLazyListState(initialFirstVisibleItemIndex = selectedPage.coerceIn(edits.indices))
                    // Keep the list position driven by the user's scroll. Scrolling
                    // updates selectedPage below; scrolling back in response to every
                    // selectedPage update creates a feedback loop and visible jank.
                    LaunchedEffect(readerState, showPageTools, edits) {
                        if (!showPageTools) {
                            snapshotFlow { readerState.firstVisibleItemIndex }
                                .distinctUntilChanged()
                                .collect { visibleIndex ->
                                    if (visibleIndex in edits.indices) {
                                        selectedPage = visibleIndex
                                        selectedPageId = edits[visibleIndex].id
                                    }
                                }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .24f)),
                        state = readerState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(edits.size, key = { edits[it].id }) { index ->
                            val edit = edits[index]
                            ReaderPage(renderer, pdfUri!!, edit.sourceIndex, edit.rotation, index + 1, renderQuality.toInt())
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        when {
                            busy -> CircularProgressIndicator()
                            renderError != null -> Text(renderError!!, modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                            else -> Text("打开一个 PDF 开始阅读")
                        }
                    }
                }
                if (showPageTools) Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(edits, key = { it.id }) { edit ->
                            val index = edits.indexOfFirst { it.id == edit.id }
                            PageThumbnail(renderer, pdfUri ?: return@items, edit.sourceIndex, edit.rotation, index,
                                index == selectedPage, edit.id in selectedPages,
                                { selectPage(index, edit.id) },
                                { delta ->
                                    val n = pageEdits?.moveById(edit.id, delta) ?: -1
                                    if (n >= 0) { selectedPage = n; selectedPageId = edit.id }
                                })
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
                    { action ->
                        when (action) {
                            "text" -> { textInput = ""; showTextDialog = true }
                            "image" -> imagePicker.launch(arrayOf("image/*"))
                            "watermark" -> { watermarkInput = ""; showWatermarkDialog = true }
                            else -> message = action
                        }
                    },
                    { showTools = false; showPageTools = false; showSplit = true })
            }

            if (showPageTools) {
                PageToolsPanel(Modifier.align(Alignment.BottomCenter), { showPageTools = false },
                    { deleteSelected(); showPageTools = false },
                    { pageEdits?.duplicateSelected(selectedPages); pageCount = pageEdits?.snapshot()?.size ?: 0; showPageTools = false },
                    { selectedPages.forEach { pageEdits?.rotateById(it) }; showPageTools = false },
                    { moveSelected(-1); showPageTools = false },
                    { moveSelected(1); showPageTools = false },
                    { showPageTools = false; showTools = false; showSplit = true })
            }

            if (showSplit) {
                SplitPanel(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    selected = selectedPreset,
                    customRects = customRects,
                    selectedCropIndex = selectedCropIndex,
                    onSelect = { selectedPreset = it },
                    onSelectCrop = { selectedCropIndex = it },
                    onAddCrop = { customRects = customRects + CropRect(.15f, .15f, .85f, .85f); selectedCropIndex = customRects.lastIndex; selectedPreset = SplitPreset.CUSTOM },
                    onRemoveCrop = { if (customRects.isNotEmpty()) { customRects = customRects.filterIndexed { index, _ -> index != selectedCropIndex }; selectedCropIndex = if (customRects.isEmpty()) 0 else selectedCropIndex.coerceAtMost(customRects.lastIndex) } },
                    onApply = {
                        showSplit = false
                        message = if (selectedPreset == SplitPreset.NONE) "请选择一种分割方式" else "分割方案已应用到选中页面，保存时导出"
                    },
                    onDismiss = { showSplit = false }
                )
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
    onAction: (String) -> Unit,
    onOpenSplit: () -> Unit
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp, shadowElevation = 8.dp) {
        Column(Modifier.padding(16.dp)) {
            PanelHeader("编辑工具", onDismiss)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolAction("添加文字", Icons.Default.Edit) { onAction("text") }
                ToolAction("插入图片", Icons.Default.Image) { onAction("image") }
                ToolAction("水印", Icons.Default.WaterDrop) { onAction("watermark") }
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
private fun SplitPanel(
    modifier: Modifier,
    selected: SplitPreset,
    customRects: List<CropRect>,
    selectedCropIndex: Int,
    onSelect: (SplitPreset) -> Unit,
    onSelectCrop: (Int) -> Unit,
    onAddCrop: () -> Unit,
    onRemoveCrop: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 10.dp, shadowElevation = 10.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("页面分割", style = MaterialTheme.typography.titleMedium)
                    Text("一页可框选多个区域，导出为多个独立页面", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitChoice("左右 2 分", SplitPreset.HORIZONTAL_2, selected, onSelect)
                SplitChoice("上下 2 分", SplitPreset.VERTICAL_2, selected, onSelect)
                SplitChoice("2 × 2", SplitPreset.GRID_2X2, selected, onSelect)
                SplitChoice("3 × 3", SplitPreset.GRID_3X3, selected, onSelect)
                SplitChoice("自由裁切", SplitPreset.CUSTOM, selected, onSelect)
                SplitChoice("画线分割", SplitPreset.LINE, selected, onSelect)
            }
            if (selected == SplitPreset.CUSTOM) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("区域", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        customRects.forEachIndexed { index, _ ->
                            FilterChip(selected = selectedCropIndex == index, onClick = { onSelectCrop(index) }, label = { Text("区域 ${index + 1}") })
                        }
                    }
                    IconButton(onClick = onAddCrop) { Icon(Icons.Default.Add, "添加区域") }
                    IconButton(onClick = onRemoveCrop, enabled = customRects.isNotEmpty()) { Icon(Icons.Default.Remove, "删除区域") }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(when (selected) {
                    SplitPreset.HORIZONTAL_2 -> "当前页面将左右拆成 2 个 PDF 页面"
                    SplitPreset.VERTICAL_2 -> "当前页面将上下拆成 2 个 PDF 页面"
                    SplitPreset.GRID_2X2 -> "当前页面将拆成 4 个 PDF 页面"
                    SplitPreset.GRID_3X3 -> "当前页面将拆成 9 个 PDF 页面"
                    SplitPreset.CUSTOM -> if (customRects.isEmpty()) "请添加至少一个裁切区域" else "已设置 ${customRects.size} 个区域，保存时逐个生成页面"
                    SplitPreset.LINE -> "在页面上从一侧向另一侧拖动画线，自动吸附为横线或竖线分割"
                    SplitPreset.NONE -> "请选择分割方式"
                }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = onApply, enabled = selected != SplitPreset.NONE && selected != SplitPreset.LINE && (selected != SplitPreset.CUSTOM || customRects.isNotEmpty())) { Text("应用") }
            }
        }
    }
}
@Composable
private fun SplitChoice(label: String, preset: SplitPreset, selected: SplitPreset, onSelect: (SplitPreset) -> Unit) {
    FilterChip(selected = selected == preset, onClick = { onSelect(preset) }, label = { Text(label) })
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
private fun ReaderPage(renderer: PdfRendererService, uri: Uri, pageIndex: Int, rotation: Int, displayNumber: Int, quality: Int) {
    var bitmap by remember(uri, pageIndex, rotation, quality) { mutableStateOf<Bitmap?>(null) }
    var error by remember(uri, pageIndex, rotation, quality) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri, pageIndex, rotation, quality) {
        bitmap = null
        error = null
        runCatching { renderer.renderPage(uri, pageIndex, quality, rotation) }
            .onSuccess { bitmap = it }
            .onFailure { error = it.message ?: "页面渲染失败" }
    }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                bitmap != null -> Image(bitmap!!.asImageBitmap(), contentDescription = "第 ${displayNumber} 页", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                error != null -> Text("第 ${displayNumber} 页加载失败：$error", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                else -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            Text("$displayNumber", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    customRects: List<CropRect>,
    selectedCropIndex: Int,
    ratioX: Float,
    ratioY: Float,
    onSelectCrop: (Int) -> Unit,
    onRatioXChange: (Float) -> Unit,
    onRatioYChange: (Float) -> Unit,
    onCustomRectChange: (Int, CropRect) -> Unit,
    onLineDraw: (androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
        val ratio = bitmap.width.toFloat() / bitmap.height
        val width = minOf(maxWidth.value, maxHeight.value * ratio).dp
        val height = width / ratio
        Box(Modifier.size(width, height)) {
            Image(bitmap.asImageBitmap(), "PDF 页面预览", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            val regions = when (preset) {
                SplitPreset.CUSTOM -> customRects
                SplitPreset.NONE, SplitPreset.LINE -> emptyList()
                else -> previewRegions(preset, ratioX, ratioY)
            }
            regions.forEachIndexed { index, region ->
                Box(Modifier.offset(width * region.left, height * region.top)
                    .size(width * (region.right - region.left), height * (region.bottom - region.top))
                    .border(if (preset == SplitPreset.CUSTOM && index == selectedCropIndex) 2.5.dp else 1.5.dp,
                        if (preset == SplitPreset.CUSTOM && index == selectedCropIndex) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary))
            }
            if (preset == SplitPreset.LINE) {
                var dragStart by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
                var dragEnd by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().pointerInput(preset, width, height) {
                        detectDragGestures(
                            onDragStart = { dragStart = it; dragEnd = it },
                            onDragEnd = {
                                val start = dragStart
                                val end = dragEnd
                                if (start != null && end != null) {
                                    onLineDraw(
                                        androidx.compose.ui.geometry.Offset((start.x / size.width).coerceIn(0f, 1f), (start.y / size.height).coerceIn(0f, 1f)),
                                        androidx.compose.ui.geometry.Offset((end.x / size.width).coerceIn(0f, 1f), (end.y / size.height).coerceIn(0f, 1f))
                                    )
                                }
                                dragStart = null
                                dragEnd = null
                            },
                            onDragCancel = { dragStart = null; dragEnd = null }
                        ) { change, _ ->
                            change.consume()
                            dragEnd = change.position
                        }
                    })
                    Canvas(Modifier.fillMaxSize()) {
                        val start = dragStart
                        val end = dragEnd
                        if (start != null && end != null) {
                            drawLine(
                                color = Color(0xFFFF5722),
                                start = start,
                                end = end,
                                strokeWidth = 5.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                            drawCircle(Color.White, radius = 7.dp.toPx(), center = start)
                            drawCircle(Color(0xFFFF5722), radius = 5.dp.toPx(), center = start)
                            drawCircle(Color.White, radius = 7.dp.toPx(), center = end)
                            drawCircle(Color(0xFFFF5722), radius = 5.dp.toPx(), center = end)
                        }
                    }
                }
            } else if (preset == SplitPreset.CUSTOM && customRects.isNotEmpty()) {
                CropEditor(width = width, height = height,
                    rect = customRects[selectedCropIndex.coerceIn(customRects.indices)],
                    onChange = { onCustomRectChange(selectedCropIndex, it) })
            } else if (preset == SplitPreset.HORIZONTAL_2 || preset == SplitPreset.GRID_2X2) {
                Box(
                    Modifier.offset(width * ratioX - 10.dp, 0.dp).width(20.dp).fillMaxHeight()
                        .pointerInput(ratioX, width) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                onRatioXChange((ratioX + drag.x / width.toPx()).coerceIn(.1f, .9f))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight(.94f)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = .9f)))
                    Box(Modifier.size(width = 18.dp, height = 34.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.tertiary),
                        contentAlignment = Alignment.Center) {
                        Text("⋮", color = MaterialTheme.colorScheme.onTertiary)
                    }
                }
            }
            if (preset == SplitPreset.VERTICAL_2 || preset == SplitPreset.GRID_2X2) {
                Box(
                    Modifier.offset(0.dp, height * ratioY - 10.dp).fillMaxWidth().height(20.dp)
                        .pointerInput(ratioY, height) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                onRatioYChange((ratioY + drag.y / height.toPx()).coerceIn(.1f, .9f))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.fillMaxWidth(.94f).height(3.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = .9f)))
                    Box(Modifier.size(width = 34.dp, height = 18.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.tertiary),
                        contentAlignment = Alignment.Center) {
                        Text("⋯", color = MaterialTheme.colorScheme.onTertiary)
                    }
                }
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
                .pointerInput(rect, width, height) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val dx = drag.x / width.toPx()
                        val dy = drag.y / height.toPx()
                        val w = rect.right - rect.left
                        val h = rect.bottom - rect.top
                        val left = (rect.left + dx).coerceIn(0f, 1f - w)
                        val top = (rect.top + dy).coerceIn(0f, 1f - h)
                        onChange(CropRect(left, top, left + w, top + h))
                    }
                }
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
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(
                        drag.x / width.toPx(),
                        drag.y / height.toPx()
                    )
                }
            }
    )
}

private fun previewRegions(preset: SplitPreset, ratioX: Float = .5f, ratioY: Float = .5f): List<CropRect> = when (preset) {
    SplitPreset.HORIZONTAL_2 -> listOf(
        CropRect(0f, 0f, ratioX, 1f),
        CropRect(ratioX, 0f, 1f, 1f)
    )
    SplitPreset.VERTICAL_2 -> listOf(
        CropRect(0f, 0f, 1f, ratioY),
        CropRect(0f, ratioY, 1f, 1f)
    )
    SplitPreset.GRID_2X2 -> buildList {
        for (r in 0..1) for (c in 0..1) {
            add(CropRect(if (c == 0) 0f else ratioX, if (r == 0) 0f else ratioY, if (c == 0) ratioX else 1f, if (r == 0) ratioY else 1f))
        }
    }
    SplitPreset.GRID_3X3 -> buildList {
        for (r in 0..2) for (c in 0..2) {
            add(CropRect(c / 3f, r / 3f, (c + 1) / 3f, (r + 1) / 3f))
        }
    }
    else -> emptyList()
}