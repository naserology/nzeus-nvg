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
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8n TFLite detector. Drop yolov8n.tflite and coco.txt into assets/models/.
 * No-ops gracefully if model is missing.
 */
class YoloDetector(private val ctx: Context) {

    data class Detection(
        val label: String,
        val score: Float,
        val box: FloatArray  // [x1, y1, x2, y2] normalized
    )

    private var interp: Interpreter? = null
    private var gpu: GpuDelegate? = null
    private var labels: List<String> = emptyList()
    var ready: Boolean = false
        private set
    var inputSize: Int = 640
        private set

    fun init(modelAsset: String = "models/yolov8n.tflite", labelsAsset: String = "models/coco.txt") {
        try {
            val model = loadModel(modelAsset) ?: return
            val opts = Interpreter.Options()
            if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                gpu = GpuDelegate(); opts.addDelegate(gpu)
            } else {
                opts.setNumThreads(4)
            }
            interp = Interpreter(model, opts)
            inputSize = interp!!.getInputTensor(0).shape()[1]
            labels = try {
                ctx.assets.open(labelsAsset).bufferedReader().use { it.readLines() }
            } catch (_: Exception) { emptyList() }
            ready = true
            Log.i("NZEUS-ML", "YOLO ready (GPU=${gpu != null}, input=$inputSize)")
        } catch (e: Exception) {
            Log.w("NZEUS-ML", "YOLO init: ${e.message}")
        }
    }

    fun detect(bmp: Bitmap, scoreThresh: Float = 0.35f, iouThresh: Float = 0.45f): List<Detection> {
        val i = interp ?: return emptyList()
        val n = inputSize
        val scaled = Bitmap.createScaledBitmap(bmp, n, n, true)
        val input = bitmapToFloatBuffer(scaled, n)
        // YOLOv8 output: [1, 84, 8400] or [1, num_dets, ...]; varies by export.
        // We assume [1, 4+nc, n] common Ultralytics export.
        val outShape = i.getOutputTensor(0).shape()
        val out = Array(outShape[0]) { Array(outShape[1]) { FloatArray(outShape[2]) } }
        i.run(input, out)
        return decode(out[0], scoreThresh, iouThresh)
    }

    private fun decode(out: Array<FloatArray>, scoreThresh: Float, iouThresh: Float): List<Detection> {
        val ncPlus4 = out.size
        val nDet = out[0].size
        val numClasses = ncPlus4 - 4
        val raw = mutableListOf<Detection>()
        for (d in 0 until nDet) {
            var bestC = -1; var bestS = 0f
            for (c in 0 until numClasses) {
                val s = out[4 + c][d]
                if (s > bestS) { bestS = s; bestC = c }
            }
            if (bestS < scoreThresh) continue
            val cx = out[0][d]; val cy = out[1][d]
            val w = out[2][d]; val h = out[3][d]
            val x1 = (cx - w/2) / inputSize
            val y1 = (cy - h/2) / inputSize
            val x2 = (cx + w/2) / inputSize
            val y2 = (cy + h/2) / inputSize
            val label = labels.getOrNull(bestC) ?: "obj"
            raw.add(Detection(label, bestS, floatArrayOf(x1, y1, x2, y2)))
        }
        return nms(raw, iouThresh)
    }

    private fun nms(dets: List<Detection>, iouThresh: Float): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val keep = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0); keep.add(best)
            val it = sorted.iterator()
            while (it.hasNext()) if (iou(best.box, it.next().box) > iouThresh) it.remove()
        }
        return keep
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val x1 = max(a[0], b[0]); val y1 = max(a[1], b[1])
        val x2 = min(a[2], b[2]); val y2 = min(a[3], b[3])
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val aArea = (a[2]-a[0]) * (a[3]-a[1])
        val bArea = (b[2]-b[0]) * (b[3]-b[1])
        return inter / max(1e-6f, aArea + bArea - inter)
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
    } catch (_: Exception) { null }

    private fun bitmapToFloatBuffer(bmp: Bitmap, n: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(1 * n * n * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(n * n)
        bmp.getPixels(pixels, 0, n, 0, 0, n, n)
        for (p in pixels) {
            buf.putFloat(((p shr 16) and 0xFF) / 255f)
            buf.putFloat(((p shr 8) and 0xFF) / 255f)
            buf.putFloat((p and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }
}
