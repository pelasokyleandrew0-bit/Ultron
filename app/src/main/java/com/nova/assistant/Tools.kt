package com.nova.assistant

import org.json.JSONArray
import org.json.JSONObject

/** The list of actions Claude is allowed to ask the phone to perform. */
object Tools {
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun int(d: String) = JSONObject().put("type", "integer").put("description", d)

    private fun tool(
        name: String,
        description: String,
        props: JSONObject = JSONObject(),
        required: List<String> = emptyList()
    ) = JSONObject()
        .put("name", name)
        .put("description", description)
        .put(
            "input_schema",
            JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("required", JSONArray(required))
        )

    fun schema(): JSONArray = JSONArray()
        .put(tool("open_app", "Open an installed app by name.",
            JSONObject().put("app_name", str("App name, e.g. Spotify, Camera, Maps")), listOf("app_name")))
        .put(tool("open_url", "Open a web page (http or https only) in the browser.",
            JSONObject().put("url", str("Full URL")), listOf("url")))
        .put(tool("web_search", "Search the web in the browser.",
            JSONObject().put("query", str("Search terms")), listOf("query")))
        .put(tool("set_timer", "Start a countdown timer.",
            JSONObject().put("seconds", int("Duration in seconds")).put("label", str("Optional label")),
            listOf("seconds")))
        .put(tool("set_alarm", "Set an alarm for a time of day. Rings at the next occurrence.",
            JSONObject().put("hour", int("Hour, 0-23")).put("minute", int("Minute, 0-59")).put("label", str("Optional label")),
            listOf("hour", "minute")))
        .put(tool("find_contact", "Look up a contact's phone number by name.",
            JSONObject().put("name", str("Full or partial contact name")), listOf("name")))
        .put(tool("compose_text", "Open the messaging app with a text message drafted. It is NOT sent. Confirm with the user, then tap_text 'Send'.",
            JSONObject().put("phone_number", str("Phone number")).put("message", str("Message body")),
            listOf("phone_number", "message")))
        .put(tool("dial", "Open the phone dialer with a number filled in. The call is NOT placed until Call is tapped.",
            JSONObject().put("phone_number", str("Phone number")), listOf("phone_number")))
        .put(tool("play_music", "Ask the music app to search for and play a song, artist, or playlist.",
            JSONObject().put("query", str("Song, artist, or playlist")), listOf("query")))
        .put(tool("read_screen", "Read the visible text on the current screen. '[tap]' marks tappable items."))
        .put(tool("tap_text", "Tap the on-screen element whose text or description contains the given text.",
            JSONObject().put("text", str("Text of the element to tap")), listOf("text")))
        .put(tool("type_text", "Type text into the focused text field.",
            JSONObject().put("text", str("Text to type")), listOf("text")))
        .put(tool("scroll", "Scroll the current screen.",
            JSONObject().put("direction", str("up, down, left, or right")), listOf("direction")))
        .put(tool("press_key", "Press a system button.",
            JSONObject().put("key", str("back, home, or recents")), listOf("key")))
}
