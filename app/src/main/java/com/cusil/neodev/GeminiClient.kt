package com.cusil.neodev

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ChatMsg(val fromUser: Boolean, val text: String)

class GeminiClient(
    private val key: () -> String,
    private val model: () -> String
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun ask(system: String, history: List<ChatMsg>, prompt: String): String {
        val k = key()
        if (k.isBlank()) throw AppException("No Gemini key yet. Add one in Settings.")

        val contents = JSONArray()
        for (m in history.takeLast(10)) {
            contents.put(
                JSONObject()
                    .put("role", if (m.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", m.text)))
            )
        }
        contents.put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        )
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .toString()

        val models = listOf(model(), Config.FALLBACK_MODEL).distinct()
        var lastError: AppException? = null
        for (m in models) {
            try {
                return call(m, k, body)
            } catch (e: AppException) {
                lastError = e
                if (e.code != 404 && e.code != 429) throw e
            }
        }
        throw lastError ?: AppException("Gemini request failed.")
    }

    private fun call(model: String, key: String, body: String): String {
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toRequestBody(jsonType))
            .build()
        return try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    val msg = try {
                        JSONObject(text).getJSONObject("error").optString("message", "")
                    } catch (e: Exception) {
                        ""
                    }
                    val nice = when (r.code) {
                        400 -> "Gemini rejected the request: $msg"
                        403 -> "Gemini key not allowed: $msg"
                        404 -> "Model $model was not found."
                        429 -> "Free Gemini quota reached for $model. Wait a bit or pick another model in Settings."
                        else -> "Gemini error ${r.code}: $msg"
                    }
                    throw AppException(nice, r.code)
                }
                val j = JSONObject(text)
                val cands = j.optJSONArray("candidates")
                if (cands == null || cands.length() == 0) {
                    val reason = j.optJSONObject("promptFeedback")?.optString("blockReason") ?: ""
                    throw AppException("Gemini returned no answer. $reason".trim())
                }
                val cand = cands.getJSONObject(0)
                val parts = cand.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (parts != null) {
                    for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                }
                if (sb.isBlank()) {
                    throw AppException("Gemini returned an empty answer (" + cand.optString("finishReason") + ").")
                }
                sb.toString().trim()
            }
        } catch (e: AppException) {
            throw e
        } catch (e: IOException) {
            throw AppException("Network error: " + (e.message ?: "no connection"))
        }
    }
}
