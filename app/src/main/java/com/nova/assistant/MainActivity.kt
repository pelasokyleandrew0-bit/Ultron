package com.nova.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** One simple screen: API key, voice tuning, permissions, and start/stop. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private var voice: VoiceOutput? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(label("ULTRON", 28f))
        status = label("", 14f)
        root.addView(status)

        // --- API key and model ---
        val keyField = EditText(this).apply {
            hint = "Anthropic API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.apiKey(this@MainActivity))
        }
        val modelField = EditText(this).apply {
            hint = "Model"
            setText(Prefs.model(this@MainActivity))
        }
        root.addView(keyField)
        root.addView(modelField)
        root.addView(button("Save key and model") {
            Prefs.setApiKey(this, keyField.text.toString())
            Prefs.setModel(this, modelField.text.toString())
            toast("Saved.")
        })

        // --- Voice tuning ---
        root.addView(label("Voice", 18f))
        root.addView(slider("Pitch (lower = deeper)", 0.4f, 1.2f, Prefs.pitch(this)) { Prefs.setPitch(this, it) })
        root.addView(slider("Speed", 0.6f, 1.2f, Prefs.rate(this)) { Prefs.setRate(this, it) })
        root.addView(slider("Metallic effect", 0f, 1f, Prefs.metal(this)) { Prefs.setMetal(this, it) })
        root.addView(button("Test voice") {
            if (voice == null) voice = VoiceOutput(this)
            voice?.speak("Ultron online. All systems respond.") {}
        })

        // --- Setup and control ---
        root.addView(label("Setup", 18f))
        root.addView(button("1. Grant permissions") { requestAllPermissions() })
        root.addView(button("2. Enable screen control") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("3. Start Ultron") { startNova() })
        root.addView(button("Stop Ultron") {
            stopService(Intent(this, NovaService::class.java))
            toast("Ultron stopped.")
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        voice?.shutdown()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true
        status.text = "Microphone: ${if (mic) "granted" else "needed"}\n" +
            "Screen control: ${if (enabled) "on" else "off"}\n" +
            "API key: ${if (Prefs.apiKey(this).isNotEmpty()) "saved" else "missing"}"
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun startNova() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Grant the microphone permission first.")
            return
        }
        if (Prefs.apiKey(this).isEmpty()) {
            toast("Save your API key first.")
            return
        }
        startForegroundService(Intent(this, NovaService::class.java))
        toast("Ultron is listening. Say \"Hey Ultron\".")
    }

    // ---- tiny UI helpers ----

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, 16, 0, 8)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun slider(title: String, lo: Float, hi: Float, current: Float, onChange: (Float) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val caption = TextView(this).apply { text = "$title: %.2f".format(current) }
        val bar = SeekBar(this).apply {
            max = 100
            progress = (((current - lo) / (hi - lo)) * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = lo + (hi - lo) * p / 100f
                    caption.text = "$title: %.2f".format(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        box.addView(caption)
        box.addView(bar)
        return box
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
