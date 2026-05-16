package com.nzeus.nvg.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.app.ActivityCompat
import com.nzeus.nvg.util.NvgConfig
import java.util.concurrent.Executors

/**
 * Camera2 controller. Picks the wide rear camera, opens the largest 16:9 preview size
 * up to 1920x1080, and exposes manual ISO / exposure / focus controls.
 *
 * Live ISO/exposure ranges from the device are queried so the UI can clamp sliders
 * to actually-supported values.
 */
class CameraController(private val ctx: Context) {

    interface Listener {
        fun onSurfaceReady(size: Size)
        fun onIsoRange(min: Int, max: Int)
        fun onExposureRange(minNs: Long, maxNs: Long)
        fun onError(msg: String)
    }

    private val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private val thread = HandlerThread("cam").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executors.newSingleThreadExecutor()

    var listener: Listener? = null
    var previewSize: Size = Size(1280, 720)
        private set

    fun start(surfaceTex: SurfaceTexture) {
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            listener?.onError("CAMERA permission not granted")
            return
        }

        val camId = pickRearWide() ?: run {
            listener?.onError("No suitable rear camera")
            return
        }

        val chars = cm.getCameraCharacteristics(camId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        previewSize = pickPreviewSize(map.getOutputSizes(SurfaceTexture::class.java))
        surfaceTex.setDefaultBufferSize(previewSize.width, previewSize.height)
        listener?.onSurfaceReady(previewSize)

        // Capability discovery
        chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
            listener?.onIsoRange(it.lower, it.upper)
        }
        chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
            listener?.onExposureRange(it.lower, it.upper)
        }

        openDevice(camId, surfaceTex)
    }

    @SuppressLint("MissingPermission")
    private fun openDevice(camId: String, surfaceTex: SurfaceTexture) {
        cm.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                createSession(camera, Surface(surfaceTex))
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close(); device = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close(); device = null
                listener?.onError("Camera open error $error")
            }
        }, handler)
    }

    private fun createSession(camera: CameraDevice, surface: Surface) {
        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            // High FPS target
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
        }
        request = req

        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface)),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    pushRequest()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    listener?.onError("Session config failed")
                }
            }
        )
        camera.createCaptureSession(cfg)
    }

    /** Re-applies current NvgConfig values to a new capture request and submits it. */
    fun pushRequest() {
        val s = session ?: return
        val r = request ?: return
        if (NvgConfig.manualIso > 0 || NvgConfig.manualExposureNs > 0) {
            r.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            if (NvgConfig.manualIso > 0) r.set(CaptureRequest.SENSOR_SENSITIVITY, NvgConfig.manualIso)
            if (NvgConfig.manualExposureNs > 0) r.set(CaptureRequest.SENSOR_EXPOSURE_TIME, NvgConfig.manualExposureNs)
        } else {
            r.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }
        if (NvgConfig.manualFocus >= 0f) {
            r.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            r.set(CaptureRequest.LENS_FOCUS_DISTANCE, NvgConfig.manualFocus)
        }
        try {
            s.setRepeatingRequest(r.build(), captureCallback, handler)
        } catch (e: Exception) {
            Log.e("NZEUS", "setRepeatingRequest", e)
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { NvgConfig.currentIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { NvgConfig.currentExposureNs = it }
        }
    }

    fun stop() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        session = null; device = null
    }

    fun release() {
        stop()
        thread.quitSafely()
        executor.shutdown()
    }

    private fun pickRearWide(): String? {
        val ids = cm.cameraIdList
        // Prefer back-facing, prefer LOGICAL_MULTI_CAMERA, prefer largest sensor
        var best: String? = null
        var bestArea = 0
        for (id in ids) {
            val c = cm.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val size = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: continue
            val area = size.width() * size.height()
            if (area > bestArea) { bestArea = area; best = id }
        }
        return best ?: ids.firstOrNull()
    }

    private fun pickPreviewSize(sizes: Array<Size>): Size {
        // Largest 16:9 up to 1920x1080
        val target = 1920 * 1080
        var best = sizes[0]
        for (s in sizes) {
            val area = s.width * s.height
            val ratio = s.width.toFloat() / s.height
            val is169 = kotlin.math.abs(ratio - 16f / 9f) < 0.02f
            if (is169 && area <= target && area > best.width * best.height) best = s
        }
        return best
    }
}
