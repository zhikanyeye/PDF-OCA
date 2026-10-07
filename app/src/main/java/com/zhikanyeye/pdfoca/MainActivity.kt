package com.zhikanyeye.pdfoca

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.zhikanyeye.pdfoca.pdf.PdfEditorScreen
import com.zhikanyeye.pdfoca.pdf.PdfPageEngine
import com.zhikanyeye.pdfoca.pdf.PdfRendererService

class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val renderer = PdfRendererService(this)
        val engine = PdfPageEngine(this)

        setContent {
            MaterialTheme {
                Surface {
                    PdfEditorScreen(renderer = renderer, engine = engine)
                }
            }
        }
    }
}
