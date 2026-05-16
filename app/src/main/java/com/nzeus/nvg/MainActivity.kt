package com.nzeus.nvg

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nzeus.nvg.camera.CameraController
import com.nzeus.nvg.databinding.ActivityMainBinding
import com.nzeus.nvg.gl.NvgRenderer
import com.nzeus.nvg.record.Recorder
import com.nzeus.nvg.sensors.SensorHub
import com.nzeus.nvg.util.NvgConfig

class MainActivity : ComponentActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var renderer: NvgRenderer
    private lateinit var camera: CameraController
    private lateinit var sensors: SensorHub
    private lateinit var recorder: Recorder
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) startCamera()
        else toast("Camera permission required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // GL renderer
        renderer = NvgRenderer(this)
        b.glView.setEGLContextClientVersion(3)
        b.glView.setRenderer(renderer)
        b.glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Camera + listeners
        camera = CameraController(this).apply {
            listener = object : CameraController.Listener {
                override fun onSurfaceReady(size: Size) {
                    runOnUiThread { /* could adjust aspect here */ }
                }
                override fun onIsoRange(min: Int, max: Int) {
                    runOnUiThread { b.seekIso.max = max - min; b.seekIso.tag = min }
                }
                override fun onExposureRange(minNs: Long, maxNs: Long) {
                    runOnUiThread {
                        // Map seekbar 0..200 -> log range
                        b.seekExp.max = 200
                        b.seekExp.tag = (minNs to maxNs)
                    }
                }
                override fun onError(msg: String) { runOnUiThread { toast(msg) } }
            }
        }

        renderer.listener = object : NvgRenderer.SurfaceListener {
            override fun onSurfaceTextureCreated(st: SurfaceTexture) {
                b.glView.post { camera.start(st) }
            }
        }

        sensors = SensorHub(this)
        recorder = Recorder(this)

        wireUi()
        requestPerms()

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nzeus:nvg")
        wakeLock?.acquire(8L * 60 * 60 * 1000)

        // HUD polling tick
        handler.post(object : Runnable {
            override fun run() {
                sensors.pollBattery()
                b.hud.sensorState = sensors.state
                b.hud.recording = recorder.recording
                b.tvIsoVal.text = "ISO ${if (NvgConfig.manualIso < 0) "AUTO" else NvgConfig.manualIso}"
                val expMs = NvgConfig.manualExposureNs / 1_000_000
                b.tvExpVal.text = "EXP ${if (NvgConfig.manualExposureNs < 0) "AUTO" else "${expMs}MS"}"
                handler.postDelayed(this, 250)
            }
        })
    }

    private fun wireUi() {
        // Mode buttons
        val modeBtns = listOf(b.btnNvg, b.btnThermal, b.btnClahe, b.btnRaw)
        fun setMode(m: NvgConfig.Mode, btn: Button) {
            NvgConfig.mode = m
            modeBtns.forEach { it.isSelected = (it === btn) }
        }
        b.btnNvg.setOnClickListener { setMode(NvgConfig.Mode.NVG, b.btnNvg) }
        b.btnThermal.setOnClickListener { setMode(NvgConfig.Mode.THERMAL, b.btnThermal) }
        b.btnClahe.setOnClickListener { setMode(NvgConfig.Mode.CLAHE, b.btnClahe) }
        b.btnRaw.setOnClickListener { setMode(NvgConfig.Mode.RAW, b.btnRaw) }
        b.btnNvg.isSelected = true

        // Sliders
        b.seekGamma.setOnSeekBarChangeListener(simpleSeek { v ->
            NvgConfig.gamma = 0.5f + v * 0.025f
            b.tvGamma.text = "GAMMA %.2f".format(NvgConfig.gamma)
        })
        b.seekGain.setOnSeekBarChangeListener(simpleSeek { v ->
            NvgConfig.gain = 0.5f + v * 0.025f
            b.tvGain.text = "GAIN %.2f".format(NvgConfig.gain)
        })
        b.seekEdge.setOnSeekBarChangeListener(simpleSeek { v ->
            NvgConfig.edgeBoost = v / 100f * 1.5f
            b.tvEdge.text = "EDGE %.2f".format(NvgConfig.edgeBoost)
        })
        b.seekPhos.setOnSeekBarChangeListener(simpleSeek { v ->
            NvgConfig.phosphorIntensity = v / 100f
            b.tvPhos.text = "PHOSPHOR %.2f".format(NvgConfig.phosphorIntensity)
        })
        b.seekIso.setOnSeekBarChangeListener(simpleSeekStop { v, fromUser ->
            val base = (b.seekIso.tag as? Int) ?: 0
            NvgConfig.manualIso = if (v == 0) -1 else base + v
            if (fromUser) camera.pushRequest()
        })
        b.seekExp.setOnSeekBarChangeListener(simpleSeekStop { v, fromUser ->
            val range = b.seekExp.tag as? Pair<Long, Long>
            NvgConfig.manualExposureNs = if (range == null || v == 0) -1L
            else {
                val lnMin = Math.log10(range.first.toDouble())
                val lnMax = Math.log10(range.second.toDouble())
                Math.pow(10.0, lnMin + (lnMax - lnMin) * (v / 200.0)).toLong()
            }
            if (fromUser) camera.pushRequest()
        })

        // Initial slider positions
        b.seekGamma.progress = ((NvgConfig.gamma - 0.5f) / 0.025f).toInt()
        b.seekGain.progress = ((NvgConfig.gain - 0.5f) / 0.025f).toInt()
        b.seekEdge.progress = (NvgConfig.edgeBoost / 1.5f * 100).toInt()
        b.seekPhos.progress = (NvgConfig.phosphorIntensity * 100).toInt()

        // Action buttons
        b.btnSnap.setOnClickListener {
            b.glView.queueEvent {
                val r = recorder.snapshot(b.glView.width, b.glView.height)
                runOnUiThread { toast(if (r != null) "SNAP SAVED" else "SNAP FAIL") }
            }
        }
        b.btnRec.setOnClickListener {
            if (recorder.recording) {
                recorder.stop(); toast("RECORDING STOPPED")
            } else {
                recorder.start(b.glView.width, b.glView.height)?.let { toast("RECORDING") }
            }
        }
        b.btnCfg.setOnClickListener {
            b.panel.visibility = if (b.panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        b.btnGridTgl.setOnClickListener { b.hud.showGrid = !b.hud.showGrid }
        b.btnRtcl.setOnClickListener { b.hud.showReticle = !b.hud.showReticle }
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { onChange(p) }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun simpleSeekStop(onChange: (Int, Boolean) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { onChange(p, fromUser) }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun requestPerms() {
        val needed = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.CAMERA
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        if (needed.isEmpty()) startCamera() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startCamera() {
        sensors.start()
    }

    override fun onPause() {
        super.onPause()
        camera.stop()
        sensors.stop()
        if (recorder.recording) recorder.stop()
        b.glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        b.glView.onResume()
        // Camera restart is triggered by SurfaceListener after EGL context recreates.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            sensors.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        camera.release()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
