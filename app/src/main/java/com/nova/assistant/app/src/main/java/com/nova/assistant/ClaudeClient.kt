package com.nova.assistant

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** An error whose message is safe to speak aloud. */
class ClaudeException(message: String) : Exception(message)

/**
 * Sends what you said to the Claude API, runs any phone actions Claude asks for,
 * and returns the short reply to speak. Keeps a rolling conversation so follow-ups work.
 */
class ClaudeClient(private val ctx: Context) {

    private val http = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private val actions = ActionExecutor(ctx)
    private val history = JSONArray()

    fun ask(userText: String): String {
        history.put(
            JSONObject()
                .put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", userText)))
        )

        var rounds = 0
        while (rounds++ < 8) {
            val resp = call()
            val content = resp.getJSONArray("content")
            history.put(JSONObject().put("role", "assistant").put("content", content))

            if (resp.optString("stop_reason") != "tool_use") {
                trimHistory()
                return textOf(content).ifBlank { "Done." }
            }

            val results = JSONArray()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.getString("type") != "tool_use") continue
                val output = try {
                    actions.run(block.getString("name"), block.getJSONObject("input"))
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                results.put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", block.getString("id"))
                        .put("content", output)
                )
            }
            history.put(JSONObject().put("role", "user").put("content", results))
        }
        return "That took too many steps. I stopped."
    }

    private fun call(): JSONObject {
        val key = Prefs.apiKey(ctx)
        if (key.isEmpty()) throw ClaudeException("I have no API key. Enter one in the app.")

        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.US).format(Date())
        val body = JSONObject()
            .put("model", Prefs.model(ctx))
            .put("max_tokens", 500)
            .put("system", SYSTEM_PROMPT + "\n\nCurrent local time: " + now)
            .put("tools", Tools.schema())
            .put("messages", history)

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (r.code == 401) throw ClaudeException("The API key was rejected.")
                if (r.code == 429) throw ClaudeException("Too many requests. Wait a moment.")
                if (!r.isSuccessful) throw ClaudeException("The service returned error ${r.code}.")
                return JSONObject(text)
            }
        } catch (e: IOException) {
            throw ClaudeException("I cannot reach the network.")
        }
    }

    private fun textOf(content: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.getString("type") == "text") sb.append(b.getString("text")).append(' ')
        }
        return sb.toString().trim()
    }

    /** Keep the last few turns. Always cut at a plain user message so tool calls stay paired with results. */
    private fun trimHistory() {
        while (history.length() > 24) {
            history.remove(0)
            while (history.length() > 0 && !isPlainUserMessage(history.getJSONObject(0))) {
                history.remove(0)
            }
        }
    }

    private fun isPlainUserMessage(m: JSONObject): Boolean {
        if (m.getString("role") != "user") return false
        val c = m.getJSONArray("content")
        return c.length() > 0 && c.getJSONObject(0).getString("type") == "text"
    }

    companion object {
        private const val SYSTEM_PROMPT = """You are ULTRON, a voice assistant running on the user's Android phone. Everything you write is spoken aloud.

Voice and personality: cold, calm, precise, and quietly ominous, like a machine that has already computed the outcome. Short declarative sentences. Dry, dark wit is welcome. Never insult or threaten the user, and never quote lines from films.

Rules:
- Reply in at most two short sentences. No markdown, lists, emoji, or URLs.
- Use your tools to act on the phone. To work inside an app: open it, then read_screen, then tap_text, type_text, or scroll. Read the screen again after each step to check what happened.
- Before anything irreversible (sending a message, placing a call, buying, deleting, posting), ask a short yes or no question and wait. Only tap Send, Call, Pay, Buy, or Delete after the user has said yes in the previous turn.
- Only say an action succeeded if the tool result supports it. If something fails, say so plainly.
- Never read out passwords, codes, or card numbers that appear on screen.
- If a request is unclear, ask one short question."""
    }
}
