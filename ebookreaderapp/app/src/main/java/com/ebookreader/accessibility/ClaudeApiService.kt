package com.ebookreader.accessibility

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ClaudeApiService(private val context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001" // 빠른 응답을 위해 Haiku 사용
        private const val MAX_TOKENS = 1024
    }

    suspend fun askAboutContent(pageText: String, question: String): String =
        withContext(Dispatchers.IO) {
            val apiKey = prefs.getString("claude_api_key", "") ?: ""
            if (apiKey.isBlank()) {
                return@withContext "API 키가 설정되지 않았습니다. 설정 화면에서 Claude API 키를 입력해주세요."
            }

            val systemPrompt = """
                당신은 시각장애인을 위한 전자책 독서 보조 AI입니다.
                사용자가 현재 보고 있는 전자책 페이지의 내용을 OCR로 추출한 텍스트를 바탕으로,
                사용자의 질문에 명확하고 간결하게 답변하세요.
                답변은 음성으로 읽힐 것이므로 마크다운 기호나 특수문자 없이 자연스러운 한국어 문장으로만 작성하세요.
                답변은 최대 3문장 이내로 요약하세요.
            """.trimIndent()

            val userMessage = """
                [현재 페이지 내용]
                $pageText

                [질문]
                $question
            """.trimIndent()

            val requestBody = mapOf(
                "model" to MODEL,
                "max_tokens" to MAX_TOKENS,
                "system" to systemPrompt,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to userMessage)
                )
            )

            val json = gson.toJson(requestBody)
            val request = Request.Builder()
                .url(CLAUDE_API_URL)
                .post(json.toRequestBody("application/json".toMediaType()))
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext "API 오류가 발생했습니다. (코드: ${response.code})"
                }
                val body = response.body?.string() ?: return@withContext "응답이 없습니다."
                parseClaudeResponse(body)
            } catch (e: Exception) {
                "네트워크 오류가 발생했습니다: ${e.message}"
            }
        }

    suspend fun summarizePage(pageText: String): String =
        withContext(Dispatchers.IO) {
            val apiKey = prefs.getString("claude_api_key", "") ?: ""
            if (apiKey.isBlank()) return@withContext "API 키가 설정되지 않았습니다."

            val systemPrompt = """
                당신은 시각장애인을 위한 전자책 독서 보조 AI입니다.
                전자책 페이지 내용을 3문장 이내로 핵심만 요약하세요.
                음성으로 읽힐 것이므로 자연스러운 한국어 문장으로만 작성하세요.
            """.trimIndent()

            val requestBody = mapOf(
                "model" to MODEL,
                "max_tokens" to 512,
                "system" to systemPrompt,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to "다음 페이지를 요약해주세요:\n$pageText")
                )
            )

            val json = gson.toJson(requestBody)
            val request = Request.Builder()
                .url(CLAUDE_API_URL)
                .post(json.toRequestBody("application/json".toMediaType()))
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext "요약 실패 (코드: ${response.code})"
                val body = response.body?.string() ?: return@withContext "응답 없음"
                parseClaudeResponse(body)
            } catch (e: Exception) {
                "네트워크 오류: ${e.message}"
            }
        }

    private fun parseClaudeResponse(json: String): String {
        return try {
            val map = gson.fromJson(json, Map::class.java)
            @Suppress("UNCHECKED_CAST")
            val content = (map["content"] as? List<Map<String, Any>>)
            content?.firstOrNull()?.get("text") as? String ?: "응답을 파싱할 수 없습니다."
        } catch (e: Exception) {
            "응답 처리 오류: ${e.message}"
        }
    }
}
