package dev.cheng.keyboard.ai

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface AiResult {
    data class Ok(val text: String) : AiResult
    data class Err(val message: String) : AiResult
}

class DeepSeekClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var active: Call? = null

    fun cancel() {
        active?.cancel()
    }

    fun complete(
        apiKey: String,
        model: String,
        system: String,
        user: String,
        temperature: Double,
        maxTokens: Int,
        thinking: Boolean,
    ): AiResult {
        val first = request(apiKey, model, system, user, temperature, maxTokens, includeThinking = thinking)
        if (first is AiResult.Err && thinking && first.message.contains("400")) {
            return request(apiKey, model, system, user, temperature, maxTokens, includeThinking = false)
        }
        return first
    }

    private fun request(
        apiKey: String,
        model: String,
        system: String,
        user: String,
        temperature: Double,
        maxTokens: Int,
        includeThinking: Boolean,
    ): AiResult {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("stream", false)
        body.put(
            "thinking",
            JSONObject().put("type", if (includeThinking) "enabled" else "disabled"),
        )
        val call = http.newCall(
            Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build(),
        )
        active = call
        return try {
            call.execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    AiResult.Err(errorMessage(response.code, raw))
                } else {
                    val text = messageText(raw)
                    if (text.isBlank()) AiResult.Err("没有返回正文") else AiResult.Ok(stripWrapped(text))
                }
            }
        } catch (_: IOException) {
            if (call.isCanceled()) AiResult.Err("") else AiResult.Err("网络不可用")
        } catch (_: Exception) {
            AiResult.Err("请求失败")
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun errorMessage(code: Int, raw: String): String {
    val detail = runCatching {
        JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
    }.getOrDefault("")
    return when {
        code == 401 -> "密钥无效"
        detail.isNotBlank() -> detail
        else -> "请求失败（$code）"
    }
}

internal fun messageText(raw: String): String {
    val root = JSONObject(raw)
    val choice = root.getJSONArray("choices").getJSONObject(0)
    val message = choice.optJSONObject("message") ?: choice
    val content = message.optString("content").trim()
    if (content.isNotEmpty() && content != "null") return content
    return message.optString("reasoning_content").trim()
}

private fun stripWrapped(text: String): String = dev.cheng.keyboard.engine.stripWrapped(text)
