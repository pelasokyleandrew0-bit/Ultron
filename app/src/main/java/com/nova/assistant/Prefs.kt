package com.nova.assistant

import android.content.Context

/** Simple settings storage. Values live in this app's private storage. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("nova", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString("api_key", "") ?: ""
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString("api_key", v.trim()).apply()

    // Haiku is the fastest, which matters for a voice assistant. Swap in "claude-sonnet-5-5" for smarter answers.
    fun model(c: Context): String = sp(c).getString("model", "claude-haiku-5-5") ?: "claude-haiku-5-5"
    fun setModel(c: Context, v: String) = sp(c).edit().putString("model", v.trim()).apply()

    /** Voice pitch. Lower = deeper. 1.0 is normal. */
    fun pitch(c: Context): Float = sp(c).getFloat("pitch", 0.7f)
    fun setPitch(c: Context, v: Float) = sp(c).edit().putFloat("pitch", v).apply()

    /** Speech rate. Below 1.0 is slower. */
    fun rate(c: Context): Float = sp(c).getFloat("rate", 0.85f)
    fun setRate(c: Context, v: Float) = sp(c).edit().putFloat("rate", v).apply()

    /** Amount of metallic ring-modulation, 0 (clean) to 1 (very robotic). */
    fun metal(c: Context): Float = sp(c).getFloat("metal", 0.35f)
    fun setMetal(c: Context, v: Float) = sp(c).edit().putFloat("metal", v).apply()
}
