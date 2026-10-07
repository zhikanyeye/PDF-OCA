package com.zhikanyeye.pdfoca.ai
data class AiMessage(val role:String,val content:String)
data class ChatRequest(val messages:List<AiMessage>,val temperature:Double=.2,val maxTokens:Int=2048)
data class ChatResponse(val content:String,val model:String?=null,val usage:Usage?=null)
data class Usage(val promptTokens:Int=0,val completionTokens:Int=0,val totalTokens:Int=0)
data class AiModel(val id:String,val ownedBy:String?=null)
data class AiResult<T>(val value:T?=null,val error:String?=null){val isSuccess get()=value!=null&&error==null}
