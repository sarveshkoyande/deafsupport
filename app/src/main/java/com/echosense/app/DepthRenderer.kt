package com.echosense.app

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Drives the ARCore session on the GL thread and extracts the nearest obstacle
 * from each depth frame. We don't draw the camera to screen (the user is blind and
 * the phone faces forward); we only need the camera texture bound so ARCore can run.
 */
class DepthRenderer(
    private val onReading: (ObstacleReading) -> Unit
) : GLSurfaceView.Renderer {

    /** distanceM = Float.NaN means "nothing close enough to warn about". */
    data class ObstacleReading(val distanceM: Float, val pan: Float, val validPixels: Int)

    @Volatile var session: Session? = null
    @Volatile var displayRotation: Int = 0

    private var cameraTextureId: Int = -1
    private var viewportW = 1
    private var viewportH = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.06f, 0.08f, 0.10f, 1f)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        cameraTextureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = width
        viewportH = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val session = session ?: return
        try {
            session.setCameraTextureName(cameraTextureId)
            session.setDisplayGeometry(displayRotation, viewportW, viewportH)
            val frame = session.update()
            processDepth(frame)
        } catch (_: Exception) {
            // A dropped frame or transient session error: skip this frame quietly.
        }
    }

    private fun processDepth(frame: Frame) {
        val image = try {
            frame.acquireDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            return // depth not ready yet (needs a moment / some motion)
        } catch (e: Exception) {
            return
        }

        try {
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            // Look at a central band; ignore the outer margins and the very top/bottom
            // (which are often floor / ceiling rather than obstacles ahead).
            val x0 = (w * 0.15f).toInt()
            val x1 = (w * 0.85f).toInt()
            val y0 = (h * 0.25f).toInt()
            val y1 = (h * 0.78f).toInt()

            // Pass 1: find the minimum valid depth.
            var minMm = Int.MAX_VALUE
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val idx = y * rowStride + x * pixelStride
                    val mm = (buffer.get(idx + 1).toInt() and 0xFF shl 8) or
                        (buffer.get(idx).toInt() and 0xFF)
                    if (mm in 1 until minMm) minMm = mm
                    x += 2
                }
                y += 2
            }

            if (minMm == Int.MAX_VALUE) {
                onReading(ObstacleReading(Float.NaN, 0f, 0))
                return
            }

            // Pass 2: cluster of pixels within 25cm of the nearest point -> the obstacle.
            val nearThresh = minMm + 250
            var count = 0
            var sumX = 0.0
            y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val idx = y * rowStride + x * pixelStride
                    val mm = (buffer.get(idx + 1).toInt() and 0xFF shl 8) or
                        (buffer.get(idx).toInt() and 0xFF)
                    if (mm in 1..nearThresh) {
                        count++
                        sumX += x
                    }
                    x += 2
                }
                y += 2
            }

            // Require a real cluster, not a couple of noisy pixels.
            if (count < 12) {
                onReading(ObstacleReading(Float.NaN, 0f, count))
                return
            }

            val avgX = sumX / count
            // Map horizontal position in the depth image to a pan of [-1, 1].
            // NOTE: sensor orientation may flip this; easy to invert after real testing.
            val pan = ((avgX / w) * 2f - 1f).toFloat()
            val distanceM = minMm / 1000f
            onReading(ObstacleReading(distanceM, pan, count))
        } finally {
            image.close()
        }
    }
}
