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
 * Sends what you said to Google's Gemini API (free tier), runs any phone actions it asks for,
 * and returns the short reply to speak. Keeps a rolling conversation so follow-ups work.
 * (The class keeps its old name so the other files do not need to change.)
 */
class ClaudeClient(private val ctx: Context) {

    private val http = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private val actions = ActionExecutor(ctx)
    private val history = JSONArray()

    fun ask(userText: String): String {
        val startSize = history.length()
        try {
            return runConversation(userText)
        } catch (e: Exception) {
            // Undo this turn so a failure never leaves the history in a broken state.
            while (history.length() > startSize) history.remove(history.length() - 1)
            throw e
        }
    }

    private fun runConversation(userText: String): String {
        history.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userText)))
        )

        var rounds = 0
        while (rounds++ < 8) {
            val resp = call()
            val candidate = resp.optJSONArray("candidates")?.optJSONObject(0)
            val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
            if (parts == null || parts.length() == 0) {
                throw ClaudeException("I received no answer. Try again.")
            }
            history.put(JSONObject().put("role", "model").put("parts", parts))

            val calls = mutableListOf<JSONObject>()
            for (i in 0 until parts.length()) {
                parts.getJSONObject(i).optJSONObject("functionCall")?.let { calls.add(it) }
            }
            if (calls.isEmpty()) {
                trimHistory()
                return textOf(parts).ifBlank { "Done." }
            }

            val responses = JSONArray()
            for (fc in calls) {
                val name = fc.getString("name")
                val args = fc.optJSONObject("args") ?: JSONObject()
                val output = try {
                    actions.run(name, args)
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                responses.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject()
                            .put("name", name)
                            .put("response", JSONObject().put("result", output))
                    )
                )
            }
            history.put(JSONObject().put("role", "user").put("parts", responses))
        }
        return "That took too many steps. I stopped."
    }

    private fun call(): JSONObject {
        val key = Prefs.apiKey(ctx)
        if (key.isEmpty()) throw ClaudeException("I have no API key. Enter one in the app.")

        // If an old Claude model name is still saved, use the Gemini default instead.
        val saved = Prefs.model(ctx)
        val model = if (saved.isBlank() || saved.startsWith("claude")) DEFAULT_MODEL else saved

        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.US).format(Date())
        val body = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT + "\n\nCurrent local time: " + now))
                )
            )
            .put("contents", history)
            .put("tools", JSONArray().put(JSONObject().put("function_declarations", declarations())))

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    val detail = try {
                        JSONObject(text).getJSONObject("error").optString("message")
                    } catch (e: Exception) {
                        ""
                    }
                    throw ClaudeException(
                        when {
                            r.code == 429 -> "I have reached the free limit. Wait a moment."
                            r.code == 404 -> "That model name is not available. Change it in the app."
                            r.code == 503 -> "The service is busy. Try again."
                            r.code == 403 || detail.contains("API key", ignoreCase = true) ->
                                "The API key was rejected."
                            else -> "The service returned error ${r.code}."
                        }
                    )
                }
                return JSONObject(text)
            }
        } catch (e: IOException) {
            throw ClaudeException("I cannot reach the network.")
        }
    }

    /** Converts the shared tool list into Gemini's function declaration format. */
    private fun declarations(): JSONArray {
        val src = Tools.schema()
        val out = JSONArray()
        for (i in 0 until src.length()) {
            val t = src.getJSONObject(i)
            val d = JSONObject()
                .put("name", t.getString("name"))
                .put("description", t.getString("description"))
            val schema = t.getJSONObject("input_schema")
            val props = schema.getJSONObject("properties")
            // Gemini rejects an empty parameter list, so tools without inputs omit it.
            if (props.length() > 0) {
                d.put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", schema.getJSONArray("required"))
                )
            }
            out.put(d)
        }
        return out
    }

    private fun textOf(parts: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            val t = p.optString("text", "")
            if (t.isNotEmpty()) sb.append(t).append(' ')
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
        val p = m.getJSONArray("parts")
        return p.length() > 0 && p.getJSONObject(0).has("text")
    }

    companion object {
        private const val DEFAULT_MODEL = "gemini-3.8-flash"

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
