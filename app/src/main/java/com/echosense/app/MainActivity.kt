package com.echosense.app

import android.app.Activity
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import java.util.Locale

/**
 * Stage 0 diagnostic.
 *
 * This tiny screen exists to prove the whole pipeline works end to end:
 * GitHub builds the APK -> it installs -> it runs -> it makes sound -> and it
 * tells us (out loud) whether this exact phone supports ARCore depth sensing.
 *
 * No camera preview and no depth logic yet -- that arrives in Stage 1.
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var status: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 110, 56, 56)
            setBackgroundColor(Color.parseColor("#101418"))
        }

        val title = TextView(this).apply {
            text = "EchoSense"
            textSize = 30f
            setTextColor(Color.WHITE)
        }
        val subtitle = TextView(this).apply {
            text = "Stage 0 — pipeline & phone check"
            textSize = 16f
            setTextColor(Color.parseColor("#80CBC4"))
            setPadding(0, 8, 0, 0)
        }
        status = TextView(this).apply {
            textSize = 19f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 48, 0, 48)
        }
        val repeatBtn = Button(this).apply {
            text = "Run check again / speak result"
            textSize = 18f
            setOnClickListener { runDiagnostic() }
        }
        val beepBtn = Button(this).apply {
            text = "Play test beep"
            textSize = 18f
            setOnClickListener { playBeep() }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(repeatBtn)
        root.addView(beepBtn)

        val scroll = ScrollView(this).apply {
            addView(
                root,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(scroll)

        status.text = "Starting up…"
        tts = TextToSpeech(this, this)
    }

    override fun onInit(statusCode: Int) {
        ttsReady = statusCode == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale.US
        runDiagnostic()
    }

    private fun runDiagnostic() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)

        // The very first query can return "still checking"; re-query shortly.
        if (availability == ArCoreApk.Availability.UNKNOWN_CHECKING) {
            status.text = "Checking whether this phone supports depth sensing…"
            Handler(Looper.getMainLooper()).postDelayed({ runDiagnostic() }, 800)
            return
        }

        val verdict = when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED ->
                "Good news: this phone SUPPORTS depth sensing, and the AR service is already installed. We can build the real obstacle detector."
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ->
                "This phone SUPPORTS depth sensing, but Google Play Services for AR needs to be installed or updated first. Install it from the Play Store, then run the check again."
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                "This phone does NOT support ARCore depth sensing. That's okay — we'll use the camera AI fallback instead."
            else ->
                "Could not determine depth support (state: $availability). Tap Run check again."
        }

        val model = "${Build.MANUFACTURER} ${Build.MODEL}"
        status.text = "Phone: $model\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n" +
            "$verdict\n\n" +
            "(If you heard a beep and this spoke aloud, the pipeline works.)"

        speak(verdict)
        playBeep()
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "diag")
    }

    private fun playBeep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 350)
            Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, 600)
        } catch (e: Exception) {
            // A busy audio device can throw; safe to ignore for a test beep.
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
