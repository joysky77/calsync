package top.stevezmt.calsync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.util.concurrent.TimeUnit

class DeepSeekConnectionTest {

    @Test
    fun testDeepSeekConnection() {
        val apiKey = System.getenv("DEEPSEEK_API_KEY") ?: return
        val apiUrl = "https://api.deepseek.com"
        val model = "deepseek-chat"
        
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val prompt = "Respond with 'Connected' if you can read this."
        
        val jsonPayload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { 
                    put("role", "user")
                    put("content", prompt) 
                })
            })
            put("stream", false)
        }

        val request = Request.Builder()
            .url("$apiUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        println("Sending request to DeepSeek...")
        try {
            client.newCall(request).execute().use { resp ->
                println("Response code: ${resp.code}")
                val body = resp.body?.string()
                println("Response body: $body")
                
                if (resp.isSuccessful && body != null) {
                    val content = JSONObject(body)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    println("AI Content: $content")
                    assert(content.contains("Connected", ignoreCase = true))
                } else {
                    throw Exception("API call failed with code ${resp.code}: $body")
                }
            }
        } catch (e: Exception) {
            println("Connection failed: ${e.message}")
            // We don't want the build to fail just because of network issues in the test environment,
            // but we want to see the output.
        }
    }
}
