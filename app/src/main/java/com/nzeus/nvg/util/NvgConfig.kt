package com.nzeus.nvg.util

/** Live, thread-safe pipeline configuration. All renderer reads happen on GL thread. */
object NvgConfig {
    enum class Mode(val id: Int) { NVG(0), THERMAL(1), CLAHE(2), RAW(3) }

    @Volatile var mode: Mode = Mode.NVG
    @Volatile var gain: Float = 1.0f
    @Volatile var gamma: Float = 1.6f
    @Volatile var edgeBoost: Float = 0.3f
    @Volatile var phosphorIntensity: Float = 0.85f
    @Volatile var denoiseStrength: Float = 0.4f
    @Volatile var mlEnhance: Boolean = false       // off by default — costly on first launch
    @Volatile var objectDetect: Boolean = false

    // Manual camera controls. -1 = auto.
    @Volatile var manualIso: Int = -1
    @Volatile var manualExposureNs: Long = -1L
    @Volatile var manualFocus: Float = -1f         // -1 auto, else diopters

    // Telemetry (renderer + camera write; UI reads)
    @Volatile var measuredFps: Float = 0f
    @Volatile var pipelineMs: Float = 0f
    @Volatile var currentIso: Int = 0
    @Volatile var currentExposureNs: Long = 0L
    @Volatile var frameCount: Long = 0
}
