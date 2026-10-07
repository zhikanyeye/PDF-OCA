package com.zhikanyeye.pdfoca
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhikanyeye.pdfoca.ai.AiSettingsStore
class MainActivity:ComponentActivity(){
 override fun onCreate(state:Bundle?){
  super.onCreate(state)
  val store=AiSettingsStore(this)
  setContent{MaterialTheme{Scaffold(topBar={TopAppBar(title={Text("PDF-OCA 0.7.1")})}){p->
   Column(Modifier.fillMaxSize().padding(p).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
    Text("PDF 工具箱",style=MaterialTheme.typography.headlineSmall)
    Text("PDF / OCR / AI 基础工程")
    Text("Agnes AI · OpenAI Compatible")
    Text("默认模型：agnes-3.0-flash")
    Button(onClick={store.save(store.load().copy(model="agnes-3.0-flash"))}){Text("选择 Agnes 3.0 Flash")}
    Text("API Key 只保存在设备加密存储，不进入源码。")
   }
  }}}
 }
}
