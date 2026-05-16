package com.nzeus.nvg.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.nzeus.nvg.util.NvgConfig
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GL renderer running on its own thread. Owns:
 *   - The external SurfaceTexture that the Camera2 session writes into
 *   - The NVG fragment shader program
 *   - Frame timing for fps/latency telemetry
 */
class NvgRenderer(
    private val ctx: Context,
) : GLSurfaceView.Renderer {

    interface SurfaceListener { fun onSurfaceTextureCreated(st: SurfaceTexture) }
    var listener: SurfaceListener? = null

    private var program = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    @Volatile private var frameAvailable = false

    private var aPosLoc = 0
    private var aTexLoc = 0
    private var uTexMatrixLoc = 0
    private var uTexLoc = 0
    private var uMlTexLoc = 0
    private var uHasMlLoc = 0
    private var uResLoc = 0
    private var uModeLoc = 0
    private var uGainLoc = 0
    private var uGammaLoc = 0
    private var uEdgeLoc = 0
    private var uPhosLoc = 0
    private var uTimeLoc = 0
    private var uDenoiseLoc = 0

    private var viewW = 0
    private var viewH = 0

    private var lastFrameNs = 0L
    private var fpsAccum = 0f
    private val startNs = System.nanoTime()

    // Fullscreen quad
    private val quad = floatArrayOf(
        // x, y, u, v
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f
    )
    private val quadBuf = GlUtil.floatBuffer(quad)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // External OES texture for camera
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId).also { st ->
            st.setOnFrameAvailableListener { frameAvailable = true }
        }
        listener?.onSurfaceTextureCreated(surfaceTexture!!)

        val vs = GlUtil.loadShaderSource(ctx, "shaders/vertex.glsl")
        val fs = GlUtil.loadShaderSource(ctx, "shaders/nvg.frag")
        program = GlUtil.program(vs, fs)

        aPosLoc = GLES30.glGetAttribLocation(program, "aPos")
        aTexLoc = GLES30.glGetAttribLocation(program, "aTex")
        uTexMatrixLoc = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uTexLoc = GLES30.glGetUniformLocation(program, "uTex")
        uMlTexLoc = GLES30.glGetUniformLocation(program, "uMlTex")
        uHasMlLoc = GLES30.glGetUniformLocation(program, "uHasMl")
        uResLoc = GLES30.glGetUniformLocation(program, "uResolution")
        uModeLoc = GLES30.glGetUniformLocation(program, "uMode")
        uGainLoc = GLES30.glGetUniformLocation(program, "uGain")
        uGammaLoc = GLES30.glGetUniformLocation(program, "uGamma")
        uEdgeLoc = GLES30.glGetUniformLocation(program, "uEdge")
        uPhosLoc = GLES30.glGetUniformLocation(program, "uPhosphor")
        uTimeLoc = GLES30.glGetUniformLocation(program, "uTime")
        uDenoiseLoc = GLES30.glGetUniformLocation(program, "uDenoise")

        GLES30.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width; viewH = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val t0 = System.nanoTime()

        val st = surfaceTexture ?: return
        if (frameAvailable) {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            frameAvailable = false
            NvgConfig.frameCount++
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        // Bind camera external texture to unit 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(uTexLoc, 0)

        GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES30.glUniform2f(uResLoc, viewW.toFloat(), viewH.toFloat())
        GLES30.glUniform1i(uModeLoc, NvgConfig.mode.id)
        GLES30.glUniform1f(uGainLoc, NvgConfig.gain)
        GLES30.glUniform1f(uGammaLoc, NvgConfig.gamma)
        GLES30.glUniform1f(uEdgeLoc, NvgConfig.edgeBoost)
        GLES30.glUniform1f(uPhosLoc, NvgConfig.phosphorIntensity)
        GLES30.glUniform1f(uDenoiseLoc, NvgConfig.denoiseStrength)
        GLES30.glUniform1f(uHasMlLoc, 0f)  // ML texture path wires in later
        GLES30.glUniform1f(uTimeLoc, (System.nanoTime() - startNs) / 1e9f)

        // Bind quad
        quadBuf.position(0)
        GLES30.glEnableVertexAttribArray(aPosLoc)
        GLES30.glVertexAttribPointer(aPosLoc, 2, GLES30.GL_FLOAT, false, 16, quadBuf)
        quadBuf.position(2)
        GLES30.glEnableVertexAttribArray(aTexLoc)
        GLES30.glVertexAttribPointer(aTexLoc, 2, GLES30.GL_FLOAT, false, 16, quadBuf)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPosLoc)
        GLES30.glDisableVertexAttribArray(aTexLoc)

        // Telemetry
        val now = System.nanoTime()
        if (lastFrameNs != 0L) {
            val dt = (now - lastFrameNs) / 1e9f
            if (dt > 0) {
                val inst = 1f / dt
                fpsAccum = fpsAccum * 0.9f + inst * 0.1f
                NvgConfig.measuredFps = fpsAccum
            }
        }
        lastFrameNs = now
        NvgConfig.pipelineMs = (now - t0) / 1e6f
    }
}
