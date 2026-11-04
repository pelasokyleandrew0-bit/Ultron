package com.nova.assistant

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import org.json.JSONObject

/** Turns a tool call from Claude into a real action on the phone and returns a plain-text result. */
class ActionExecutor(private val ctx: Context) {

    private val noAccessibility =
        "Screen control is switched off. Ask the user to enable Ultron under Settings, Accessibility."

    fun run(name: String, a: JSONObject): String = when (name) {
        "open_app" -> openApp(a.getString("app_name"))
        "open_url" -> openUrl(a.getString("url"))
        "web_search" -> openUrl("https://www.google.com/search?q=" + Uri.encode(a.getString("query")))
        "set_timer" -> setTimer(a.getInt("seconds"), a.optString("label", "Ultron timer"))
        "set_alarm" -> setAlarm(a.getInt("hour"), a.getInt("minute"), a.optString("label", "Ultron alarm"))
        "find_contact" -> findContact(a.getString("name"))
        "compose_text" -> composeText(a.getString("phone_number"), a.getString("message"))
        "dial" -> dial(a.getString("phone_number"))
        "play_music" -> playMusic(a.getString("query"))
        "read_screen" -> withScreen { it.readScreen() }
        "tap_text" -> withScreen {
            if (it.tapText(a.getString("text"))) "Tapped." else "Nothing tappable matched that text."
        }
        "type_text" -> withScreen {
            if (it.typeText(a.getString("text"))) "Typed." else "No text field is focused."
        }
        "scroll" -> withScreen { it.scroll(a.getString("direction")); "Scrolled." }
        "press_key" -> withScreen { if (it.press(a.getString("key"))) "Done." else "Unknown key." }
        else -> "Unknown tool: $name"
    }

    private inline fun withScreen(block: (NovaAccessibilityService) -> String): String {
        val svc = NovaAccessibilityService.instance ?: return noAccessibility
        return block(svc)
    }

    /**
     * Android blocks background apps from opening screens. An enabled accessibility service is
     * allowed to, so we launch through it when we can.
     */
    private fun launch(intent: Intent, success: String): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            val launcher: Context = NovaAccessibilityService.instance ?: ctx
            launcher.startActivity(intent)
            Thread.sleep(900) // let the new screen appear before the next step reads it
            success
        } catch (e: ActivityNotFoundException) {
            "No app on this phone can handle that."
        } catch (e: Exception) {
            "That failed: ${e.message}"
        }
    }

    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(probe, 0)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().equals(name, ignoreCase = true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) }
            ?: return "No installed app is named $name."
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "That app cannot be launched."
        return launch(intent, "Opened ${match.loadLabel(pm)}.")
    }

    private fun openUrl(url: String): String {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return "Only web links are allowed."
        return launch(Intent(Intent.ACTION_VIEW, uri), "Opened the page.")
    }

    private fun setTimer(seconds: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(i, "Timer request sent for $seconds seconds.")
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(i, "Alarm request sent for %02d:%02d.".format(hour, minute))
    }

    private fun findContact(name: String): String {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "Contacts permission has not been granted."
        }
        val sb = StringBuilder()
        val phone = ContactsContract.CommonDataKinds.Phone
        ctx.contentResolver.query(
            phone.CONTENT_URI,
            arrayOf(phone.DISPLAY_NAME, phone.NUMBER),
            "${phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )?.use { c ->
            var count = 0
            while (c.moveToNext() && count < 5) {
                sb.append(c.getString(0)).append(": ").append(c.getString(1)).append('\n')
                count++
            }
        }
        return if (sb.isEmpty()) "No contact matches $name." else sb.toString()
    }

    private fun composeText(number: String, body: String): String {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .putExtra("sms_body", body)
        return launch(i, "Message drafted but NOT sent. Confirm with the user before tapping Send.")
    }

    private fun dial(number: String): String {
        val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
        return launch(i, "Dialer opened. The call has NOT been placed yet.")
    }

    private fun playMusic(query: String): String {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, query)
        val result = launch(i, "Asked the music app to play $query.")
        return if (result.startsWith("No app")) {
            openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        } else result
    }
}
