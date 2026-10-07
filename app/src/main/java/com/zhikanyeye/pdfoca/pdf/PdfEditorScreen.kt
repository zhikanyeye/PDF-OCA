package com.zhikanyeye.pdfoca.pdf

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun PdfEditorScreen(renderer: PdfRendererService, engine: PdfPageEngine) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pdfUri by remember { mutableStateOf<Uri?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var selectedPage by remember { mutableIntStateOf(0) }
    var selectedPages by remember { mutableStateOf(setOf<Int>()) }
    var pageEdits by remember { mutableStateOf<PdfPageEdits?>(null) }
    var selectedPreset by remember { mutableStateOf(SplitPreset.NONE) }
    var customRect by remember { mutableStateOf(CropRect(0f, 0f, 1f, 1f)) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

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
            selectedPages = setOf(0)
            scope.launch {
                busy = true
                runCatching { renderer.pageCount(uri) }
                    .onSuccess { pageCount = it; message = "已打开 PDF，共 \${it} 页" }
                    .onFailure { message = it.message ?: "打开 PDF 失败" }
                busy = false
            }
        }
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
                    else selectedPages.map { PageSplitRequest(it, regions) }
                    val bytes = engine.split(input, requests, pageEdits?.snapshot().orEmpty())
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

    LaunchedEffect(pdfUri, selectedPage) {
        val uri = pdfUri ?: return@LaunchedEffect
        busy = true
        runCatching { renderer.renderPage(uri, selectedPage, 1600) }
            .onSuccess { pageBitmap = it }
            .onFailure { message = it.message ?: "页面渲染失败" }
        busy = false
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openPdf.launch(arrayOf("application/pdf")) }) { Text("打开 PDF") }
            Button(enabled = pdfUri != null && !busy, onClick = { savePdf.launch("PDF-OCA-edited.pdf") }) {
                Text("导出")
            }
        }

        Spacer(Modifier.height(8.dp))

        if (pdfUri != null) {
            Text("第 \${selectedPage + 1} / \${pageCount} 页", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                repeat(pageCount) { index ->
                    FilterChip(
                        selected = index == selectedPage,
                        onClick = { selectedPage = index; selectedPages = selectedPages + index },
                        label = { Text("\${index + 1}") }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("分割方式", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "不分割" to SplitPreset.NONE,
                    "左右 2 页" to SplitPreset.HORIZONTAL_2,
                    "上下 2 页" to SplitPreset.VERTICAL_2,
                    "2×2" to SplitPreset.GRID_2X2,
                    "3×3" to SplitPreset.GRID_3X3,
                    "自定义" to SplitPreset.CUSTOM
                ).forEach { (label, preset) ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = { selectedPreset = preset },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                pageBitmap?.let { bitmap ->
                    SplitPreview(bitmap, selectedPreset, customRect) { customRect = it }
                } ?: CircularProgressIndicator()
            }

            Spacer(Modifier.height(8.dp))
            if (selectedPreset != SplitPreset.NONE) {
                val count = if (selectedPreset == SplitPreset.CUSTOM) 1
                else engine.presetRegions(selectedPreset).size
                Text("当前页面将生成 \$count 个逻辑页面。")
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
private fun SplitPreview(
    bitmap: Bitmap,
    preset: SplitPreset,
    customRect: CropRect,
    onCustomRectChange: (CropRect) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
        val ratio = bitmap.width.toFloat() / bitmap.height
        val width = minOf(maxWidth.value, maxHeight.value * ratio).dp
        val height = width / ratio

        Box(Modifier.size(width, height)) {
            Image(bitmap.asImageBitmap(), "PDF 页面预览", Modifier.fillMaxSize())

            val regions = when (preset) {
                SplitPreset.CUSTOM -> listOf(customRect)
                SplitPreset.NONE -> emptyList()
                else -> previewRegions(preset)
            }

            regions.forEach { region ->
                Box(
                    Modifier.offset(width * region.left, height * region.top)
                        .size(width * (region.right - region.left), height * (region.bottom - region.top))
                        .border(1.dp, Color.White)
                )
            }

            if (preset == SplitPreset.CUSTOM) {
                Box(
                    Modifier.offset(width * customRect.left, height * customRect.top)
                        .size(width * (customRect.right - customRect.left), height * (customRect.bottom - customRect.top))
                        .border(2.dp, MaterialTheme.colorScheme.primary)
                        .pointerInput(customRect) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val dx = drag.x / width.toPx()
                                val dy = drag.y / height.toPx()
                                onCustomRectChange(
                                    CropRect(
                                        (customRect.left + dx).coerceIn(0f, customRect.right - .05f),
                                        (customRect.top + dy).coerceIn(0f, customRect.bottom - .05f),
                                        customRect.right,
                                        customRect.bottom
                                    )
                                )
                            }
                        }
                )
            }
        }
    }
}

private fun previewRegions(preset: SplitPreset): List<CropRect> = when (preset) {
    SplitPreset.HORIZONTAL_2 -> listOf(CropRect(0f,0f,.5f,1f), CropRect(.5f,0f,1f,1f))
    SplitPreset.VERTICAL_2 -> listOf(CropRect(0f,0f,1f,.5f), CropRect(0f,.5f,1f,1f))
    SplitPreset.GRID_2X2 -> buildList {
        for (r in 0..1) for (c in 0..1) add(CropRect(c/2f,r/2f,(c+1)/2f,(r+1)/2f))
    }
    SplitPreset.GRID_3X3 -> buildList {
        for (r in 0..2) for (c in 0..2) add(CropRect(c/3f,r/3f,(c+1)/3f,(r+1)/3f))
    }
    else -> emptyList()
}
