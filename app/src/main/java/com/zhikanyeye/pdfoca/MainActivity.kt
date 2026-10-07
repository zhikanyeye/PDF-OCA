package com.zhikanyeye.pdfoca

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import com.zhikanyeye.pdfoca.home.PdfOcaHome
import com.zhikanyeye.pdfoca.pdf.PdfEditorScreen
import com.zhikanyeye.pdfoca.pdf.PdfPageEngine
import com.zhikanyeye.pdfoca.pdf.PdfRendererService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val renderer = PdfRendererService(this)
        val engine = PdfPageEngine(this)

        setContent {
            val pdfOcaColors = lightColorScheme(
                primary = Color(0xFFD32F2F),
                onPrimary = Color.White,
                background = Color(0xFFF8F7FB),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFF0EEF5),
                onSurface = Color(0xFF303238),
                onSurfaceVariant = Color(0xFF6E7078)
            )
            MaterialTheme(colorScheme = pdfOcaColors) {
                val scope = rememberCoroutineScope()
                val snackbar = remember { SnackbarHostState() }
                var editorUri by remember { mutableStateOf<Uri?>(null) }
                var showEditor by remember { mutableStateOf(false) }

                Surface {
                    if (showEditor) {
                        PdfEditorScreen(
                            renderer = renderer,
                            engine = engine,
                            initialUri = editorUri,
                            onBack = {
                                showEditor = false
                                editorUri = null
                            }
                        )
                    } else {
                        PdfOcaHome(
                            onOpenPdf = { uri ->
                                editorUri = uri
                                showEditor = true
                            },
                            onMessage = { message ->
                                scope.launch { snackbar.showSnackbar(message) }
                            }
                        )
                    }
                }

                SnackbarHost(
                    hostState = snackbar,
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .wrapContentSize(androidx.compose.ui.Alignment.BottomCenter)
                )
            }
        }
    }
}
