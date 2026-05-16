package com.nzeus.nvg.record

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaRecorder
import android.opengl.GLES30
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.OutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages clip recording (MediaRecorder fed by a Surface — for v2.1 we'll wire that
 * Surface into the GL pipeline as a second EGL target) and snapshots from a glReadPixels
 * dump. For now this exposes the API the HUD calls; v2.1 will wire the Surface in.
 */
class Recorder(private val ctx: Context) {

    private var mr: MediaRecorder? = null
    @Volatile var recording: Boolean = false
        private set
    var inputSurface: Surface? = null
        private set

    private val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun start(width: Int, height: Int, fps: Int = 30): Surface? {
        if (recording) return inputSurface
        val name = "NVG_${fmt.format(Date())}.mp4"
        val uri = createVideoUri(name) ?: return null

        val pfd = ctx.contentResolver.openFileDescriptor(uri, "w") ?: return null

        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoEncodingBitRate(12_000_000)
            rec.setVideoFrameRate(fps)
            rec.setVideoSize(width, height)
            rec.setOutputFile(pfd.fileDescriptor)
            rec.prepare()
            inputSurface = rec.surface
            rec.start()
            mr = rec
            recording = true
            Log.i("NZEUS-REC", "started $name @ ${width}x$height $fps")
        } catch (e: Exception) {
            Log.e("NZEUS-REC", "start failed", e)
            try { rec.reset(); rec.release() } catch (_: Exception) {}
            return null
        } finally {
            try { pfd.close() } catch (_: Exception) {}
        }
        return inputSurface
    }

    fun stop() {
        if (!recording) return
        try {
            mr?.stop()
        } catch (e: Exception) { Log.e("NZEUS-REC", "stop", e) }
        try {
            mr?.reset(); mr?.release()
        } catch (_: Exception) {}
        mr = null
        inputSurface = null
        recording = false
        Log.i("NZEUS-REC", "stopped")
    }

    /** Read pixels off the GL context and write a JPG to MediaStore. */
    fun snapshot(width: Int, height: Int): String? {
        val buf = ByteBuffer.allocateDirect(width * height * 4)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        // GL is bottom-up; flip vertically
        val matrix = android.graphics.Matrix().apply { postScale(1f, -1f, width / 2f, height / 2f) }
        val flipped = Bitmap.createBitmap(bmp, 0, 0, width, height, matrix, true)
        bmp.recycle()

        val name = "NVG_${fmt.format(Date())}.jpg"
        val uri = createImageUri(name) ?: return null
        val os: OutputStream = ctx.contentResolver.openOutputStream(uri) ?: return null
        os.use { flipped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        flipped.recycle()
        return uri.toString()
    }

    private fun createVideoUri(name: String) = ctx.contentResolver.insert(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/NZEUS-NVG")
            }
        }
    )

    private fun createImageUri(name: String) = ctx.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/NZEUS-NVG")
            }
        }
    )
}
