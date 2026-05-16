package com.nzeus.nvg.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Wraps Zero-DCE TFLite model. Used off the GL thread; the GLES path can sample
 * the output via a SurfaceTexture upload if you want to A/B against the
 * shader-only pipeline. Falls back to no-op if model is missing.
 */
class ZeroDce(private val ctx: Context) {

    private var interp: Interpreter? = null
    private var gpu: GpuDelegate? = null
    var ready: Boolean = false
        private set

    fun init(modelAsset: String = "models/zero_dce.tflite") {
        try {
            val buf = loadModel(modelAsset) ?: return
            val opts = Interpreter.Options()
            if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                gpu = GpuDelegate()
                opts.addDelegate(gpu)
            } else {
                opts.setNumThreads(4)
            }
            interp = Interpreter(buf, opts)
            ready = true
            Log.i("NZEUS-ML", "Zero-DCE ready (GPU=${gpu != null})")
        } catch (e: Exception) {
            Log.w("NZEUS-ML", "Zero-DCE init: ${e.message}")
        }
    }

    fun enhance(input: Bitmap): Bitmap? {
        val i = interp ?: return null
        val inDetails = i.getInputTensor(0)
        val outDetails = i.getOutputTensor(0)
        val (_, h, w, _) = inDetails.shape()
        val small = Bitmap.createScaledBitmap(input, w, h, true)
        val inBuf = bitmapToFloatBuffer(small, w, h)
        val outBuf = ByteBuffer.allocateDirect(1 * h * w * 3 * 4).order(ByteOrder.nativeOrder())
        i.run(inBuf, outBuf)
        outBuf.rewind()
        val out = floatBufferToBitmap(outBuf, w, h)
        return Bitmap.createScaledBitmap(out, input.width, input.height, true)
    }

    fun release() {
        interp?.close(); interp = null
        gpu?.close(); gpu = null
        ready = false
    }

    private fun loadModel(name: String): MappedByteBuffer? = try {
        val afd = ctx.assets.openFd(name)
        val ch = afd.createInputStream().channel
        val mapped = ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        ch.close(); afd.close()
        mapped
    } catch (e: Exception) {
        Log.w("NZEUS-ML", "model missing: $name")
        null
    }

    private fun bitmapToFloatBuffer(bmp: Bitmap, w: Int, h: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(1 * h * w * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (p in pixels) {
            buf.putFloat(((p shr 16) and 0xFF) / 255f)
            buf.putFloat(((p shr 8) and 0xFF) / 255f)
            buf.putFloat((p and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }

    private fun floatBufferToBitmap(buf: ByteBuffer, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            val r = (buf.float.coerceIn(0f, 1f) * 255).toInt()
            val g = (buf.float.coerceIn(0f, 1f) * 255).toInt()
            val b = (buf.float.coerceIn(0f, 1f) * 255).toInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}

private operator fun IntArray.component4(): Int = this[3]
