@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zhikanyeye.pdfoca.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class ToolItem(
    val title: String,
    val icon: ImageVector,
    val action: HomeAction = HomeAction.Placeholder
)

private enum class HomeAction { OpenPdf }

private data class ToolSection(
    val title: String,
    val items: List<ToolItem>
)

@Composable
fun PdfOcaHome(
    onOpenPdf: (Uri) -> Unit,
    onMessage: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(2) }
    val openPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpenPdf(uri)
    }

    val sections = remember {
        listOf(
            ToolSection("PDF 文件", listOf(
                ToolItem("打开并编辑 PDF", Icons.Default.Edit, HomeAction.OpenPdf),
                ToolItem("阅读 PDF", Icons.Default.MenuBook, HomeAction.OpenPdf)
            ))
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("工具", style = MaterialTheme.typography.headlineSmall) },
                actions = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 3.dp,
                    shadowElevation = 2.dp
                ) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                        tonalElevation = 0.dp
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0; onMessage("首页") },
                            icon = { Icon(Icons.Default.Home, null) },
                            label = { Text("首页") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1; onMessage("文件") },
                            icon = { Icon(Icons.Default.Folder, null) },
                            label = { Text("文件") }
                        )
                        Spacer(Modifier.width(56.dp))
                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3; onMessage("工具") },
                            icon = { Icon(Icons.Default.Tune, null) },
                            label = { Text("工具") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 4,
                            onClick = { selectedTab = 4; onMessage("账户") },
                            icon = { Icon(Icons.Default.AccountCircle, null) },
                            label = { Text("账户") }
                        )
                    }
                }

                FloatingActionButton(
                    onClick = { openPdfLauncher.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.align(Alignment.TopCenter).size(60.dp),
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, "新建")
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .32f)
                        )
                    )
                )
        ) {
            val minCardWidth = if (maxWidth < 600.dp) 150.dp else 132.dp
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = minCardWidth),
                state = rememberLazyGridState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                sections.forEach { section ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 1.dp)
                        )
                    }
                    items(section.items, key = { section.title + it.title }) { tool ->
                        ToolCard(tool) {
                            when (tool.action) {
                                HomeAction.OpenPdf -> openPdfLauncher.launch(arrayOf("application/pdf"))
                                
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(item: ToolItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().aspectRatio(1.02f),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(9.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
