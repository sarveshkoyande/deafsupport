package com.echosense.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Turns "nearest obstacle" readings into a gentle, continuous tone (not beeps).
 *
 *   - closer  -> the tone glides smoothly UP in pitch
 *   - farther -> it glides back down
 *   - nothing in range -> it fades softly to silence
 *   - left/right position -> stereo pan (needs stereo / bone-conduction headphones)
 *   - very close -> a light vibration pulse as well
 *
 * Everything glides (no sudden jumps), so it's calm to listen to instead of nagging.
 */
class AudioEngine(private val vibrator: Vibrator?) {

    companion object {
        const val MIN_RANGE_M = 0.30f
        const val MAX_RANGE_M = 4.0f
        const val VIBRATE_BELOW_M = 0.6f

        private const val SAMPLE_RATE = 44100
        // Lower, softer band than the old beeps (which topped out shrill at 1250 Hz).
        private const val FAR_FREQ = 240.0   // pitch at MAX_RANGE
        private const val NEAR_FREQ = 720.0  // pitch at MIN_RANGE
        private const val AMPLITUDE = 0.45   // 0..1, kept modest so it isn't harsh

        // Per-sample glide rates (bigger = snappier). Tuned for ~40-60 ms smoothing.
        private const val FREQ_GLIDE = 0.0006
        private const val GAIN_GLIDE = 0.0004
        private const val PAN_GLIDE = 0.0005

        private const val BLOCK = 1024 // frames per write (~23 ms)
    }

    @Volatile private var distanceM = Float.NaN
    @Volatile private var pan = 0f
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun update(distanceMeters: Float, panLeftRight: Float) {
        distanceM = distanceMeters
        pan = panLeftRight.coerceIn(-1f, 1f)
    }

    fun clear() {
        distanceM = Float.NaN
    }

    fun start() {
        if (running) return
        running = true
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, BLOCK * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
        thread = Thread { synthLoop() }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        try {
            track?.stop(); track?.release()
        } catch (_: Exception) {
        }
        track = null
    }

    private fun synthLoop() {
        val track = track ?: return
        val buf = ShortArray(BLOCK * 2)
        var phase = 0.0
        var curFreq = FAR_FREQ
        var curGain = 0.0
        var curPan = 0.0
        var lastBuzz = 0L

        while (running) {
            val d = distanceM
            val inRange = !d.isNaN() && d <= MAX_RANGE_M

            val targetFreq: Double
            val targetGain: Double
            if (inRange) {
                val clamped = d.coerceIn(MIN_RANGE_M, MAX_RANGE_M)
                val t = (clamped - MIN_RANGE_M) / (MAX_RANGE_M - MIN_RANGE_M) // 0 near .. 1 far
                targetFreq = NEAR_FREQ + (FAR_FREQ - NEAR_FREQ) * t
                targetGain = AMPLITUDE
            } else {
                targetFreq = curFreq // hold pitch while fading out (avoids a swoop)
                targetGain = 0.0
            }
            val targetPan = pan.toDouble()

            for (i in 0 until BLOCK) {
                curFreq += (targetFreq - curFreq) * FREQ_GLIDE
                curGain += (targetGain - curGain) * GAIN_GLIDE
                curPan += (targetPan - curPan) * PAN_GLIDE

                phase += 2.0 * PI * curFreq / SAMPLE_RATE
                if (phase > 2.0 * PI) phase -= 2.0 * PI

                val s = sin(phase) * curGain
                val angle = ((curPan + 1.0) / 2.0) * (PI / 2.0) // equal-power pan
                buf[i * 2] = (s * cos(angle) * Short.MAX_VALUE).toInt().toShort()
                buf[i * 2 + 1] = (s * sin(angle) * Short.MAX_VALUE).toInt().toShort()
            }
            track.write(buf, 0, buf.size)

            if (inRange && d <= VIBRATE_BELOW_M) {
                val now = System.currentTimeMillis()
                if (now - lastBuzz > 500) {
                    lastBuzz = now
                    buzz()
                }
            }
        }
    }

    private fun buzz() {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(35)
            }
        } catch (_: Exception) {
        }
    }
}
