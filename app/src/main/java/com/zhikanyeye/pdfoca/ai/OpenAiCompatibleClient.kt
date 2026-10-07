package com.zhikanyeye.pdfoca.ai
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
class OpenAiCompatibleClient(private val baseUrl:String,private val apiKey:String,private val client:OkHttpClient=OkHttpClient()){
 private fun endpoint(path:String)=baseUrl.trimEnd('/')+"/"+path.trimStart('/')
 private val media="application/json; charset=utf-8".toMediaType()
 suspend fun listModels()=withContext(Dispatchers.IO){
  runCatching{
   val q=Request.Builder().url(endpoint("models")).header("Authorization","Bearer $apiKey").get().build()
   client.newCall(q).execute().use{response->
    val raw=response.body?.string().orEmpty()
    if(!response.isSuccessful)return@withContext AiResult<List<AiModel>>(error="HTTP "+response.code+": "+raw.take(500))
    val a=JSONObject(raw).optJSONArray("data")?:JSONArray()
    AiResult(value=buildList{for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;val id=o.optString("id");if(id.isNotBlank())add(AiModel(id,o.optString("owned_by").ifBlank{null}))}})
   }
  }.getOrElse{AiResult(error=it.message?:"连接失败")}
 }
 suspend fun chat(model:String,data:ChatRequest)=withContext(Dispatchers.IO){
  runCatching{
   val m=JSONArray();data.messages.forEach{m.put(JSONObject().put("role",it.role).put("content",it.content))}
   val payload=JSONObject().put("model",model).put("messages",m).put("temperature",data.temperature).put("max_tokens",data.maxTokens).put("stream",false)
   val q=Request.Builder().url(endpoint("chat/completions")).header("Authorization","Bearer $apiKey").post(payload.toString().toRequestBody(media)).build()
   client.newCall(q).execute().use{response->
    val raw=response.body?.string().orEmpty()
    if(!response.isSuccessful)return@withContext AiResult<ChatResponse>(error="HTTP "+response.code+": "+raw.take(800))
    val root=JSONObject(raw)
    val content=root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
    if(content.isBlank())return@withContext AiResult<ChatResponse>(error="AI 返回内容为空")
    val u=root.optJSONObject("usage")
    AiResult(value=ChatResponse(content,root.optString("model").ifBlank{null},u?.let{Usage(it.optInt("prompt_tokens"),it.optInt("completion_tokens"),it.optInt("total_tokens"))}))
   }
  }.getOrElse{AiResult(error=it.message?:"AI 请求失败")}
 }
}
