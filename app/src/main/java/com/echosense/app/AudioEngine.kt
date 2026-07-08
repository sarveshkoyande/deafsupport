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
import kotlin.math.min
import kotlin.math.sin

/**
 * Turns "nearest obstacle" readings into sound + vibration, parking-sensor style.
 *
 *   - closer  -> higher pitch AND faster beeps
 *   - farther -> lower pitch AND slower beeps
 *   - left/right position -> stereo pan (needs bone-conduction / stereo headphones)
 *   - very close -> a vibration pulse as well
 *
 * The renderer thread just keeps calling [update]; a private thread does the beeping.
 */
class AudioEngine(private val vibrator: Vibrator?) {

    companion object {
        const val MIN_RANGE_M = 0.30f   // closer than this is clamped to "as close as it gets"
        const val MAX_RANGE_M = 4.0f    // farther than this -> silence (nothing worth warning about)
        const val VIBRATE_BELOW_M = 0.6f

        private const val SAMPLE_RATE = 44100
        private const val LOW_FREQ = 320.0   // pitch at MAX_RANGE
        private const val HIGH_FREQ = 1250.0 // pitch at MIN_RANGE
        private const val SLOW_GAP_MS = 650L // gap between beeps at MAX_RANGE
        private const val FAST_GAP_MS = 55L  // gap between beeps at MIN_RANGE
        private const val BEEP_MS = 80
    }

    @Volatile private var distanceM = Float.NaN   // NaN = no obstacle in range
    @Volatile private var pan = 0f                // -1 = full left, +1 = full right
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    /** Called from the depth thread every frame. distance in metres, pan in [-1, 1]. */
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
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = max(minBuf, SAMPLE_RATE) // ~1s of stereo headroom
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
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()

        thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        try {
            track?.stop()
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }

    private fun loop() {
        while (running) {
            val d = distanceM
            if (d.isNaN() || d > MAX_RANGE_M) {
                Thread.sleep(90)
                continue
            }
            val clamped = d.coerceIn(MIN_RANGE_M, MAX_RANGE_M)
            val t = (clamped - MIN_RANGE_M) / (MAX_RANGE_M - MIN_RANGE_M) // 0 near .. 1 far
            val freq = HIGH_FREQ + (LOW_FREQ - HIGH_FREQ) * t
            val gap = (FAST_GAP_MS + (SLOW_GAP_MS - FAST_GAP_MS) * t).toLong()

            playBeep(freq, pan)

            if (clamped <= VIBRATE_BELOW_M) buzz()

            Thread.sleep(gap)
        }
    }

    /** Generate and stream one stereo beep with an equal-power left/right pan. */
    private fun playBeep(freq: Double, panLR: Float) {
        val track = track ?: return
        val frames = SAMPLE_RATE * BEEP_MS / 1000
        val pcm = ShortArray(frames * 2)

        // equal-power pan: angle 0 -> full left, PI/2 -> full right
        val angle = ((panLR + 1f) / 2f) * (PI / 2)
        val leftGain = cos(angle)
        val rightGain = sin(angle)

        val attack = frames / 12
        val release = frames / 5
        for (i in 0 until frames) {
            val env = when {
                i < attack -> i.toDouble() / attack
                i > frames - release -> (frames - i).toDouble() / release
                else -> 1.0
            }
            val s = sin(2.0 * PI * freq * i / SAMPLE_RATE) * env * 0.6
            pcm[i * 2] = (s * leftGain * Short.MAX_VALUE).toInt().toShort()
            pcm[i * 2 + 1] = (s * rightGain * Short.MAX_VALUE).toInt().toShort()
        }
        track.write(pcm, 0, pcm.size)
    }

    private fun buzz() {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(40)
            }
        } catch (_: Exception) {
        }
    }
}
