package com.xincode.provider

import android.util.Log
import com.xincode.provider.HttpCacheProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Independent judge that evaluates whether a goal has been achieved.
 * Uses a separate non-streaming chat call — NOT the same model instance.
 * Returns [JudgeResult] with achieved/verdict/explanation.
 */
class JudgeService(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val apiPathType: String = "openai",
    private val extraHeadersJson: String = ""
) {
    companion object {
        private const val TAG = "JudgeService"
        private const val MAX_STDOUT = 4000
    }

    data class JudgeResult(
        val achieved: Boolean,
        val confidence: Float,
        val verdict: String,
        val explanation: String
    )

    private val httpClient = OkHttpClient.Builder()
.connectTimeout(30, TimeUnit.SECONDS)
.readTimeout(60, TimeUnit.SECONDS)
.cache(HttpCacheProvider.get())
.build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * gap-25 多裁判对抗面板:并发跑 [voters] 个独立裁判,按多数决合成结论,降低单裁判偶发误判。
     * achieved = 多数票;confidence = 命中方平均置信;verdict/explanation 取代表票。
     * 任一裁判返回 null(API 错误)不计票;全部失败返回 null。
     */
    suspend fun judgePanel(goal: String, output: String, iteration: Int, voters: Int = 3): JudgeResult? =
        withContext(Dispatchers.IO) {
            val n = voters.coerceIn(1, 5)
            val votes = (0 until n).map { async { judge(goal, output, iteration) } }.awaitAll().filterNotNull()
            if (votes.isEmpty()) return@withContext null
            val yes = votes.filter { it.achieved }
            val no = votes.filter { !it.achieved }
            val achieved = yes.size > no.size
            // 平票时不误用败方均值，以全体均值兜底
            val winners = if (achieved) yes else if (no.size > yes.size) no else votes
            val rep = winners.maxByOrNull { it.confidence } ?: votes.first()
            JudgeResult(
                achieved = achieved,
                confidence = winners.map { it.confidence }.average().toFloat(),
                verdict = rep.verdict,
                explanation = "[面板 ${yes.size}:${no.size}/${votes.size}] " + rep.explanation
            )
        }

    /**
     * Ask the model: has the goal been achieved given this output?
     * DeepSeek-compatible: explicitly disables thinking.
     */
    suspend fun judge(goal: String, output: String, iteration: Int): JudgeResult? = withContext(Dispatchers.IO) {
        try {
            val trimmedOutput = output.take(3000)
            val messages = listOf(
                JSONObject().apply {
                    put("role", "system")
                    put("content", JUDGE_PROMPT)
                },
                JSONObject().apply {
                    put("role", "user")
                    put("content", "目标: $goal\n\n当前轮次: $iteration\n\nAgent 最新输出:\n$trimmedOutput")
                }
            )
            val responses = apiPathType == "responses"
            val body = if (responses) {
                ResponsesProtocol.buildRequest(
                    model = model,
                    messages = messages,
                    maxOutputTokens = 500
                )
            } else {
                JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray(messages))
                    put("max_tokens", 500)
                    put("stream", false)
                    // DeepSeek: explicitly disable thinking for non-streaming judge calls
                    put("thinking", JSONObject().apply {
                        put("type", "disabled")
                    })
                }
            }

            val endpoint = if (responses) ResponsesProtocol.endpoint(baseUrl)
            else "${baseUrl.trimEnd('/')}/v1/chat/completions"
            Log.d(TAG, "→ POST $endpoint (judge, model=$model)")

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .applyExtraHeaders(extraHeadersJson)
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.use { it.body?.string() ?: "" }

            Log.d(TAG, "← $responseCode ${responseBody.take(300)}")

            if (responseCode !in 200..299) {
                Log.w(TAG, "Judge API error: $responseCode ${responseBody.take(200)}")
                return@withContext null
            }

            val json = JSONObject(responseBody)
            val content = if (responses) {
                ResponsesProtocol.extractResponse(json).content
            } else {
                // Extract content - handle both standard and reasoning_content responses
                val choices = json.getJSONArray("choices")
                val message = choices.getJSONObject(0).getJSONObject("message")
                if (!message.isNull("content")) message.getString("content") else ""
            }
            if (content.isBlank()) {
                Log.w(TAG, "Judge returned null content")
                return@withContext null
            }

            Log.i(TAG, "Judge raw response: ${content.take(200)}")
            parseJudgeResult(content)
        } catch (e: Exception) {
            Log.w(TAG, "Judge failed: ${e.message}", e)
            null
        }
    }

    private fun Request.Builder.applyExtraHeaders(value: String): Request.Builder {
        if (value.isBlank()) return this
        val blocked = setOf("authorization", "content-type", "content-length", "host")
        try {
            val headers = JSONObject(value)
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.lowercase() in blocked) continue
                if (key.isNotBlank() && !headers.isNull(key)) header(key, headers.optString(key))
            }
        } catch (_: Exception) {
            // Optional headers are best-effort; malformed JSON must not break judging.
        }
        return this
    }

    private fun parseJudgeResult(content: String): JudgeResult {
        // Parse structured JSON response from judge
        return try {
            val json = JSONObject(content.trim())
            JudgeResult(
                achieved = json.optBoolean("achieved", false),
                confidence = json.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f),
                verdict = json.optString("verdict", ""),
                explanation = json.optString("explanation", content.take(200))
            )
        } catch (e: Exception) {
            // Fallback: parse text response
            val lower = content.lowercase()
            val achieved = lower.contains("achieved") || lower.contains("达成") || lower.contains("完成")
            JudgeResult(
                achieved = achieved && !lower.contains("not achieved") && !lower.contains("未达成"),
                confidence = 0.3f,
                verdict = if (achieved) "ACHIEVED" else "NOT_ACHIEVED",
                explanation = content.take(200)
            )
        }
    }
}

private const val JUDGE_PROMPT = """You are an independent judge evaluating whether a goal has been achieved.

You will receive:
- The original goal
- The current iteration number
- The agent's latest output

Your task: Determine if the goal has been FULLY and CORRECTLY achieved.

Respond ONLY in this JSON format:
{
  "achieved": true/false,
  "confidence": 0.0-1.0,
  "verdict": "ACHIEVED" or "NOT_ACHIEVED",
  "explanation": "brief reason (1-2 sentences)"
}

Rules:
- achieved=true ONLY if the output contains concrete evidence the goal is met
- Don't be lenient — partial progress is NOT achieved
- If the agent seems stuck or repeating, that's NOT achieved
- If the output shows real results matching the goal, that IS achieved"""
