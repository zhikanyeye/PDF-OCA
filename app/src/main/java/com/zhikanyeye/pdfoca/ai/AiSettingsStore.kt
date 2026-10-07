package com.zhikanyeye.pdfoca.ai
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
class AiSettingsStore(context:Context){
 private val p=EncryptedSharedPreferences.create("ai_settings",MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),context,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
 data class Settings(val baseUrl:String="https://apihub.agnes-ai.com/v1",val apiKey:String="",val model:String="agnes-3.0-flash",val temperature:Double=.2,val maxTokens:Int=2048)
 fun save(s:Settings){p.edit().putString("base_url",s.baseUrl).putString("api_key",s.apiKey).putString("model",s.model).putString("temperature",s.temperature.toString()).putInt("max_tokens",s.maxTokens).apply()}
 fun load()=Settings(p.getString("base_url","https://apihub.agnes-ai.com/v1")!!,p.getString("api_key","")!!,p.getString("model","agnes-3.0-flash")!!,p.getString("temperature","0.2")!!.toDoubleOrNull()?:.2,p.getInt("max_tokens",2048))
}
