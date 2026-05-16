package com.nzeus.nvg.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.nzeus.nvg.sensors.SensorHub
import com.nzeus.nvg.util.NvgConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Single Canvas-drawn HUD. Cheap, scales with display, no view hierarchy overhead. */
class HudOverlay @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(ctx, attrs, defStyle) {

    var sensorState: SensorHub.State = SensorHub.State()
        set(v) { field = v; postInvalidateOnAnimation() }
    var recording: Boolean = false
        set(v) { field = v; postInvalidateOnAnimation() }
    var detections: List<com.nzeus.nvg.ml.YoloDetector.Detection> = emptyList()
        set(v) { field = v; postInvalidateOnAnimation() }
    var showGrid: Boolean = true
        set(v) { field = v; invalidate() }
    var showReticle: Boolean = true
        set(v) { field = v; invalidate() }

    private val phosphor = Color.parseColor("#39FF14")
    private val phosphorDim = Color.parseColor("#1f8c0a")
    private val amber = Color.parseColor("#FF9900")
    private val critical = Color.parseColor("#FF2A2A")

    private val orbitron: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val mono: Typeface = Typeface.MONOSPACE

    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = phosphor; strokeWidth = dp(1f); style = Paint.Style.STROKE
        setShadowLayer(dp(2f), 0f, 0f, phosphor)
    }
    private val pDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = phosphorDim; strokeWidth = dp(0.5f); style = Paint.Style.STROKE
    }
    private val pTextLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = phosphorDim; textSize = sp(9f); typeface = orbitron
        letterSpacing = 0.2f
    }
    private val pTextVal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = phosphor; textSize = sp(12f); typeface = mono
        setShadowLayer(dp(2f), 0f, 0f, phosphor)
    }
    private val pTextLarge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = phosphor; textSize = sp(11f); typeface = orbitron
        letterSpacing = 0.25f
        setShadowLayer(dp(3f), 0f, 0f, phosphor)
    }
    private val pReticle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = phosphor; strokeWidth = dp(1f); style = Paint.Style.STROKE
        setShadowLayer(dp(4f), 0f, 0f, phosphor)
    }
    private val pDetBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = phosphor; strokeWidth = dp(1.5f); style = Paint.Style.STROKE
    }
    private val pDetLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = phosphor; textSize = sp(10f); typeface = mono
    }
    private val pBg = Paint().apply { color = Color.argb(160, 0, 0, 0) }

    private val tlPath = Path()

    private val clockFmt = SimpleDateFormat("HH:mm:ss'Z'", Locale.US)
    private val tmpBounds = Rect()

    init { setWillNotDraw(false); setLayerType(LAYER_TYPE_HARDWARE, null) }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(12f)

        drawGrid(canvas, w, h)
        drawCornerBrackets(canvas, w, h)
        drawBanner(canvas, w)
        drawCompass(canvas, w, h)
        drawTelemetry(canvas, w, h, pad)
        drawOpticsBlock(canvas, w, h, pad)
        drawPositionBlock(canvas, w, h, pad)
        drawSystemBlock(canvas, w, h, pad)
        if (showReticle) drawReticle(canvas, w, h)
        drawDetections(canvas, w, h)
        if (recording) drawRecBadge(canvas, w)

        // 60 FPS HUD redraw — minimal cost since most work is text
        postInvalidateOnAnimation()
    }

    private fun drawGrid(c: Canvas, w: Float, h: Float) {
        if (!showGrid) return
        val s = dp(64f)
        pDim.color = Color.argb(40, 57, 255, 20)
        var x = 0f
        while (x < w) { c.drawLine(x, 0f, x, h, pDim); x += s }
        var y = 0f
        while (y < h) { c.drawLine(0f, y, w, y, pDim); y += s }
    }

    private fun drawCornerBrackets(c: Canvas, w: Float, h: Float) {
        val m = dp(8f); val L = dp(20f)
        pLine.color = phosphor
        // Top-left
        c.drawLine(m, m + L, m, m, pLine); c.drawLine(m, m, m + L, m, pLine)
        // Top-right
        c.drawLine(w - m - L, m, w - m, m, pLine); c.drawLine(w - m, m, w - m, m + L, pLine)
        // Bottom-left
        c.drawLine(m, h - m, m, h - m - L, pLine); c.drawLine(m, h - m, m + L, h - m, pLine)
        // Bottom-right
        c.drawLine(w - m, h - m - L, w - m, h - m, pLine); c.drawLine(w - m, h - m, w - m - L, h - m, pLine)
    }

    private fun drawBanner(c: Canvas, w: Float) {
        val y = dp(22f)
        c.drawText("NZEUS // OPTIC-2 v2.0", dp(28f), y, pTextLarge)
        val live = "● LIVE"
        pTextLarge.color = critical
        pTextLarge.getTextBounds(live, 0, live.length, tmpBounds)
        c.drawText(live, w - tmpBounds.width() - dp(28f), y, pTextLarge)
        pTextLarge.color = phosphor
    }

    private fun drawCompass(c: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val top = dp(40f); val bot = dp(62f)
        val barW = dp(240f)
        val l = cx - barW / 2; val r = cx + barW / 2
        c.drawLine(l, top, r, top, pDim)
        c.drawLine(l, bot, r, bot, pDim)
        val heading = sensorState.heading
        val ppd = barW / 90f
        var deg = (heading - 45).toInt()
        if (deg < 0) deg += 360
        val startOffset = -((heading - 45) - deg) * ppd
        var x = l + startOffset
        var d = deg
        while (x < r) {
            val major = d % 30 == 0
            val tickLen = if (major) dp(8f) else dp(4f)
            c.drawLine(x, top, x, top + tickLen, pLine)
            if (major) {
                val label = when (d) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> "$d" }
                pTextVal.getTextBounds(label, 0, label.length, tmpBounds)
                c.drawText(label, x - tmpBounds.width() / 2f, top + dp(20f), pTextVal)
            }
            x += ppd * 5
            d = (d + 5) % 360
        }
        // Center marker
        val triPath = Path().apply {
            moveTo(cx - dp(5f), top - dp(2f))
            lineTo(cx + dp(5f), top - dp(2f))
            lineTo(cx, top + dp(4f)); close()
        }
        val triPaint = Paint(pReticle).apply { style = Paint.Style.FILL }
        c.drawPath(triPath, triPaint)
        // Numeric heading
        val txt = "%03d°".format(heading.toInt())
        pTextLarge.getTextBounds(txt, 0, txt.length, tmpBounds)
        c.drawText(txt, cx - tmpBounds.width() / 2f, bot + dp(16f), pTextLarge)
    }

    private fun drawTelemetry(c: Canvas, w: Float, h: Float, pad: Float) {
        val x = pad + dp(8f); var y = dp(80f)
        c.drawText("TELEMETRY", x, y, pTextLabel); y += dp(16f)
        c.drawText("FPS  ${"%.1f".format(NvgConfig.measuredFps)}", x, y, pTextVal); y += dp(16f)
        c.drawText("PROC ${"%.1f".format(NvgConfig.pipelineMs)} MS", x, y, pTextVal); y += dp(16f)
        c.drawText("FRM  ${NvgConfig.frameCount}", x, y, pTextVal)
    }

    private fun drawOpticsBlock(c: Canvas, w: Float, h: Float, pad: Float) {
        var y = dp(80f)
        val anchor = w - pad - dp(8f)
        val labels = arrayOf(
            "OPTICS",
            "ISO ${NvgConfig.currentIso}",
            "EXP ${(NvgConfig.currentExposureNs / 1_000_000)} MS",
            "%.1f LX".format(sensorState.lux),
        )
        val paints = arrayOf(pTextLabel, pTextVal, pTextVal, pTextVal)
        for ((i, l) in labels.withIndex()) {
            val pt = paints[i]
            pt.getTextBounds(l, 0, l.length, tmpBounds)
            c.drawText(l, anchor - tmpBounds.width(), y, pt)
            y += dp(16f)
        }
    }

    private fun drawPositionBlock(c: Canvas, w: Float, h: Float, pad: Float) {
        val x = pad + dp(8f); var y = h - dp(120f)
        c.drawText("POSITION", x, y, pTextLabel); y += dp(16f)
        c.drawText("LAT %.5f".format(sensorState.lat), x, y, pTextVal); y += dp(16f)
        c.drawText("LON %.5f".format(sensorState.lon), x, y, pTextVal); y += dp(16f)
        c.drawText("ALT %.0f M".format(sensorState.alt), x, y, pTextVal)
    }

    private fun drawSystemBlock(c: Canvas, w: Float, h: Float, pad: Float) {
        var y = h - dp(120f)
        val anchor = w - pad - dp(8f)
        c.drawText("SYSTEM", anchor - measure("SYSTEM", pTextLabel), y, pTextLabel); y += dp(16f)
        val bat = "BAT ${sensorState.batteryPct}%"
        pTextVal.color = when {
            sensorState.batteryPct < 20 -> critical
            sensorState.batteryPct < 40 -> amber
            else -> phosphor
        }
        c.drawText(bat, anchor - measure(bat, pTextVal), y, pTextVal); y += dp(16f)
        pTextVal.color = phosphor
        val hdg = "HDG %03d°".format(sensorState.heading.toInt())
        c.drawText(hdg, anchor - measure(hdg, pTextVal), y, pTextVal); y += dp(16f)
        val t = clockFmt.format(Date())
        c.drawText(t, anchor - measure(t, pTextVal), y, pTextVal)
    }

    private fun drawReticle(c: Canvas, w: Float, h: Float) {
        val cx = w / 2f; val cy = h / 2f
        val ringR = dp(30f); val crossLen = dp(90f)
        c.drawCircle(cx, cy, ringR, pReticle)
        c.drawLine(cx - crossLen, cy, cx - dp(10f), cy, pReticle)
        c.drawLine(cx + dp(10f), cy, cx + crossLen, cy, pReticle)
        c.drawLine(cx, cy - crossLen, cx, cy - dp(10f), pReticle)
        c.drawLine(cx, cy + dp(10f), cx, cy + crossLen, pReticle)
        // Center dot
        val fillP = Paint(pReticle).apply { style = Paint.Style.FILL }
        c.drawCircle(cx, cy, dp(2f), fillP)
    }

    private fun drawDetections(c: Canvas, w: Float, h: Float) {
        for (d in detections) {
            val x1 = d.box[0] * w; val y1 = d.box[1] * h
            val x2 = d.box[2] * w; val y2 = d.box[3] * h
            c.drawRect(x1, y1, x2, y2, pDetBox)
            val tag = "${d.label.uppercase()} ${(d.score * 100).toInt()}"
            pDetLabel.getTextBounds(tag, 0, tag.length, tmpBounds)
            val labelH = tmpBounds.height() + dp(4f)
            c.drawRect(x1, y1 - labelH, x1 + tmpBounds.width() + dp(8f), y1, pBg)
            c.drawText(tag, x1 + dp(4f), y1 - dp(3f), pDetLabel)
        }
    }

    private fun drawRecBadge(c: Canvas, w: Float) {
        val r = dp(5f)
        val cx = w / 2f - dp(28f); val cy = dp(48f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = critical
            setShadowLayer(dp(4f), 0f, 0f, critical)
            // Pulse via alpha cycling tied to system time
            alpha = (155 + 100 * (kotlin.math.sin(System.currentTimeMillis() / 250.0))).toInt().coerceIn(50, 255)
        }
        c.drawCircle(cx, cy, r, p)
        val rec = "REC"
        pTextLarge.color = critical
        c.drawText(rec, cx + dp(10f), cy + dp(4f), pTextLarge)
        pTextLarge.color = phosphor
    }

    private fun measure(s: String, p: Paint): Float {
        p.getTextBounds(s, 0, s.length, tmpBounds); return tmpBounds.width().toFloat()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
