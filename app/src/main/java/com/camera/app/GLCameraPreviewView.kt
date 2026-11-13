package com.camera.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.AttributeSet
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class GLCameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "GLCameraPreviewView"

        private val VERTICES = floatArrayOf(
            -1f, -1f, 0f, 0f, 1f,
            1f, -1f, 0f, 1f, 1f,
            -1f,  1f, 0f, 0f, 0f,
            1f,  1f, 0f, 1f, 0f
        )
    }

    private var oesTextureId = 0
    private var program = 0
    private var lutTexture = 0
    private var lutSize = 0
    private var strength = 0.7f

    private var eglConfig: android.opengl.EGLConfig? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(VERTICES)
            .also { it.position(0) }

    private var surfaceTexture: SurfaceTexture? = null
    private var isReleased = false

    init {
        surfaceTextureListener = this
    }

    /* =================== TextureView =================== */

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surfaceTexture = st
        CoroutineScope(Dispatchers.IO).launch {

            initGL()
            surfaceTexture?.setDefaultBufferSize(w, h)
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /* =================== CameraX 用 =================== */



    fun getCameraSurfaceTexture(): SurfaceTexture {
        return surfaceTexture
            ?: throw IllegalStateException("SurfaceTexture not ready")
    }
    /* =================== LUT 接口 =================== */

    fun setLUT(cubeData: CUBEData?) {
        if (cubeData == null) {
            lutTexture = 0
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            makeCurrent()
            uploadLUT(cubeData)
        }
    }

    fun setStrength(value: Float) {
        strength = value.coerceIn(0f, 1f)
    }

    /* =================== EGL / GL =================== */

    private fun initEGL(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, // ✅ 正确
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0
        )

        val st = surfaceTexture ?: return false
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, st, null, 0
        )

        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun makeCurrent() {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun initGL() {
        oesTextureId = createOESTexture()
        surfaceTexture?.attachToGLContext(oesTextureId)
        program = createProgram()
    }


    /* =================== 渲染 =================== */


    fun drawFrame() {
        if (isReleased || program == 0) return

        surfaceTexture?.updateTexImage() // ✅ 必须
        makeCurrent()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        bindTextures()
        drawQuad()

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun bindTextures() {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uOESTexture"), 0)

        if (lutTexture != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "lutTexture"), 1)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "lutSize"), lutSize.toFloat())
        }

        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "strength"), strength)
    }

    private fun drawQuad() {
        vertexBuffer.position(0)
        val aPos = GLES30.glGetAttribLocation(program, "position")
        val aTex = GLES30.glGetAttribLocation(program, "texCoord")

        GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 5 * 4, vertexBuffer)
        vertexBuffer.position(3)
        GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 5 * 4, vertexBuffer)

        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glEnableVertexAttribArray(aTex)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPos)
        GLES30.glDisableVertexAttribArray(aTex)
    }

    /* =================== LUT =================== */

    private fun uploadLUT(cubeData: CUBEData) {
        if (lutTexture == 0) {
            val tex = IntArray(1)
            GLES30.glGenTextures(1, tex, 0)
            lutTexture = tex[0]
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        val buffer = ByteBuffer.allocateDirect(cubeData.data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(cubeData.data)
                position(0)
            }

        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
            cubeData.size, cubeData.size, cubeData.size, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, buffer
        )

        lutSize = cubeData.size
    }

    /* =================== Shader =================== */

    private fun createProgram(): Int {
        val vert = """
            #version 300 es
            in vec4 position;
            in vec2 texCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = position;
                vTexCoord = texCoord;
            }
        """.trimIndent()

        val frag = """
            #version 300 es
            precision highp float;
            in vec2 vTexCoord;
            uniform samplerExternalOES uOESTexture;
            uniform sampler3D lutTexture;
            uniform float lutSize;
            uniform float strength;
            out vec4 fragColor;

            vec3 tetrahedral(vec3 c, float size) {
                float scale = (size - 1.0) / size;
                float offset = 0.5 / size;
                vec3 coord = c * scale + offset;
                return texture(lutTexture, coord).rgb;
            }

            void main() {
                vec4 color = texture(uOESTexture, vTexCoord);
                vec3 mapped = tetrahedral(color.rgb, lutSize);
                fragColor = vec4(mix(color.rgb, mapped, strength), 1.0);
            }
        """.trimIndent()

        return linkProgram(vert, frag)
    }

    /* =================== Utils =================== */

    private fun createOESTexture(): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        return tex[0]
    }

    private fun linkProgram(vert: String, frag: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vert)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, frag)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        return s
    }

    fun release() {
        isReleased = true
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
    }
}