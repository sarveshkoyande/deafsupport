package com.echosense.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.util.Locale

/**
 * Stage 1 — live obstacle detector.
 *
 * Camera + ARCore Depth -> nearest obstacle -> spatial beeps (pitch = distance,
 * left/right pan = direction) + vibration when very close. Best used with
 * bone-conduction headphones so real-world hearing stays open.
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var glView: GLSurfaceView
    private lateinit var readout: TextView
    private lateinit var renderer: DepthRenderer
    private lateinit var audio: AudioEngine

    private var session: Session? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var installRequested = false
    private var lastSpoken = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        audio = AudioEngine(vibrator)

        renderer = DepthRenderer { reading -> onObstacle(reading) }

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        readout = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(48, 96, 48, 48)
            text = "Starting EchoSense…\nPoint the camera forward."
        }

        val root = FrameLayout(this)
        root.addView(glView)
        root.addView(readout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))
        setContentView(root)

        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale.US
        speak("EchoSense active. Point the camera forward. A soft tone rises in pitch as you get closer to something.")
    }

    override fun onResume() {
        super.onResume()
        if (!hasCameraPermission()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1001)
            return
        }
        ensureSessionAndResume()
    }

    private fun ensureSessionAndResume() {
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return // will come back to onResume after install
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> { /* continue */ }
                }

                val s = Session(this)
                val config = Config(s)
                if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.depthMode = Config.DepthMode.AUTOMATIC
                } else {
                    setReadout("This phone's ARCore build has no depth mode. Cannot run Stage 1.")
                }
                config.focusMode = Config.FocusMode.AUTO
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                s.configure(config)
                session = s
                renderer.session = s
            } catch (e: Exception) {
                setReadout("Could not start AR: ${e.message}")
                return
            }
        }

        try {
            renderer.displayRotation = windowManager.defaultDisplay.rotation
            session?.resume()
            glView.onResume()
            audio.start()
        } catch (e: Exception) {
            setReadout("Camera/AR resume failed: ${e.message}")
            session = null
        }
    }

    override fun onPause() {
        super.onPause()
        audio.stop()
        if (session != null) {
            glView.onPause()
            session?.pause()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasCameraPermission()) {
            ensureSessionAndResume()
        } else {
            setReadout("Camera permission is required for obstacle detection.")
            speak("Please allow the camera so I can detect obstacles.")
        }
    }

    private fun onObstacle(r: DepthRenderer.ObstacleReading) {
        audio.update(r.distanceM, r.pan)
        runOnUiThread {
            if (r.distanceM.isNaN()) {
                readout.text = "Path clear ✓\n(no obstacle within ${AudioEngine.MAX_RANGE_M.toInt()} m)"
            } else {
                val side = when {
                    r.pan < -0.33f -> "◄ LEFT"
                    r.pan > 0.33f -> "RIGHT ►"
                    else -> "▲ AHEAD"
                }
                val cm = (r.distanceM * 100).toInt()
                readout.text = "Obstacle: $side\n$cm cm away"
                maybeSpeakUrgent(r.distanceM, side)
            }
        }
    }

    /** Occasional spoken warning for a very close obstacle (beeps carry the rest). */
    private fun maybeSpeakUrgent(distanceM: Float, side: String) {
        val now = System.currentTimeMillis()
        if (distanceM < 0.5f && now - lastSpoken > 2500) {
            lastSpoken = now
            speak("Close, $side")
        }
    }

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun setReadout(msg: String) = runOnUiThread { readout.text = msg }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "es")
    }

    override fun onDestroy() {
        audio.stop()
        tts?.shutdown()
        session?.close()
        super.onDestroy()
    }
}
