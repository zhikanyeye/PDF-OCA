package com.zhikanyeye.pdfoca

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
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
            MaterialTheme {
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
