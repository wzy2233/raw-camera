package com.camera.app

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class TetrahedralLUTFilter {

    // EGL 相关
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null

    // OpenGL 资源
    private var program = 0
    private var lutTexture = 0
    private var lutSize = 0
    private var isReady = false
    private var initialized = false
    private var inputTexture = 0
    private var outputTexture = 0
    private var fbo = 0
    private var width = 0
    private var height = 0
    private var vertexBuffer: FloatBuffer? = null

    // ✅ LUT 强度（0.0 = 原始图片, 1.0 = 完全 LUT）
    private var strength = 0.7f

    companion object {
        private const val EGL_OPENGL_ES3_BIT = 0x0040

        private const val VERTEX_SHADER = """
            #version 300 es
            precision highp float;
            in vec4 position;
            in vec2 inputTexCoord;
            out vec2 texCoord;
            void main() {
                gl_Position = position;
                texCoord = inputTexCoord;
            }
        """

        // ✅ 四面体插值 Fragment Shader（带强度控制）
        private const val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            in vec2 texCoord;
            uniform sampler2D inputImageTexture;
            uniform sampler3D lutTexture;
            uniform float lutSize;
            uniform float strength;
            out vec4 fragColor;

            vec4 tetrahedralInterpolation(vec3 coord) {
                float maxIndex = lutSize - 1.0;
                vec3 scaledCoord = coord * maxIndex;
                vec3 base = floor(scaledCoord);
                vec3 frac = scaledCoord - base;

                vec3 texBase = base / maxIndex;
                vec3 tex100 = (base + vec3(1, 0, 0)) / maxIndex;
                vec3 tex010 = (base + vec3(0, 1, 0)) / maxIndex;
                vec3 tex001 = (base + vec3(0, 0, 1)) / maxIndex;
                vec3 tex110 = (base + vec3(1, 1, 0)) / maxIndex;
                vec3 tex101 = (base + vec3(1, 0, 1)) / maxIndex;
                vec3 tex011 = (base + vec3(0, 1, 1)) / maxIndex;
                vec3 tex111 = (base + vec3(1, 1, 1)) / maxIndex;

                if (frac.x >= frac.y && frac.y >= frac.z) {
                    vec4 v000 = texture(lutTexture, texBase);
                    vec4 v100 = texture(lutTexture, tex100);
                    vec4 v110 = texture(lutTexture, tex110);
                    vec4 v111 = texture(lutTexture, tex111);
                    return mix(mix(v000, v100, frac.x),
                               mix(v110, v111, frac.x), frac.y);
                } else if (frac.x >= frac.z && frac.z >= frac.y) {
                    vec4 v000 = texture(lutTexture, texBase);
                    vec4 v100 = texture(lutTexture, tex100);
                    vec4 v101 = texture(lutTexture, tex101);
                    vec4 v111 = texture(lutTexture, tex111);
                    return mix(mix(v000, v100, frac.x),
                               mix(v101, v111, frac.x), frac.z);
                } else if (frac.y >= frac.x && frac.x >= frac.z) {
                    vec4 v000 = texture(lutTexture, texBase);
                    vec4 v010 = texture(lutTexture, tex010);
                    vec4 v110 = texture(lutTexture, tex110);
                    vec4 v111 = texture(lutTexture, tex111);
                    return mix(mix(v000, v010, frac.y),
                               mix(v110, v111, frac.y), frac.x);
                } else if (frac.y >= frac.z && frac.z >= frac.x) {
                    vec4 v000 = texture(lutTexture, texBase);
                    vec4 v010 = texture(lutTexture, tex010);
                    vec4 v011 = texture(lutTexture, tex011);
                    vec4 v111 = texture(lutTexture, tex111);
                    return mix(mix(v000, v010, frac.y),
                               mix(v011, v111, frac.y), frac.z);
                } else if (frac.z >= frac.x && frac.x >= frac.y) {
                    vec4 v000 = texture(lutTexture, texBase);
                    vec4 v001 = texture(lutTexture, tex001);
                    vec4 v101 = texture(lutTexture, tex101);
                    vec4 v111 = texture(lutTexture, tex111);
                    return mix(mix(v000, v001, frac.z),
                               mix(v101, v111, frac.z), frac.x);
                } else {
                    vec4 v000 = texture(lutTexture, texBase);
                    vec4 v001 = texture(lutTexture, tex001);
                    vec4 v011 = texture(lutTexture, tex011);
                    vec4 v111 = texture(lutTexture, tex111);
                    return mix(mix(v000, v001, frac.z),
                               mix(v011, v111, frac.z), frac.y);
                }
            }

            void main() {
                vec4 color = texture(inputImageTexture, texCoord);
                float scale = (lutSize - 1.0) / lutSize;
                float offset = 0.5 / lutSize;
                vec3 lutCoord = vec3(
                    color.r * scale + offset,
                    color.g * scale + offset,
                    color.b * scale + offset
                );
                vec3 mapped = tetrahedralInterpolation(lutCoord).rgb;
                fragColor = vec4(mix(color.rgb, mapped, strength), color.a);
            }
        """

        private val VERTICES = floatArrayOf(
            -1f, -1f, 0f, 0f, 0f,
            1f, -1f, 0f, 1f, 0f,
            -1f,  1f, 0f, 0f, 1f,
            1f,  1f, 0f, 1f, 1f
        )
    }

    init {
        initialized = initEGL()
        initVertexBuffer()
    }

    /** 设置 LUT 强度 */
    fun setStrength(s: Float) {
        strength = s.coerceIn(0f, 1f)
    }

    private fun initEGL(): Boolean {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                throw RuntimeException("eglGetDisplay failed")
            }

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw RuntimeException("eglInitialize failed")
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] <= 0
            ) {
                throw RuntimeException("eglChooseConfig failed")
            }
            eglConfig = configs[0]

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(
                eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
            )

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            eglSurface = EGL14.eglCreatePbufferSurface(
                eglDisplay, eglConfig, surfaceAttribs, 0
            )

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw RuntimeException("eglMakeCurrent failed")
            }

            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun ensureCurrent(): Boolean {
        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    fun isUsable(): Boolean = initialized

    private fun initVertexBuffer() {
        vertexBuffer = ByteBuffer
            .allocateDirect(VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(VERTICES)
        vertexBuffer?.position(0)
    }

    @Synchronized
    fun setLUT(cubeData: CUBEData) {
        if (!ensureCurrent()) {
            isReady = false
            return
        }
        releaseLUT()
        lutSize = cubeData.size

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) {
            isReady = false
            return
        }

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        lutTexture = tex[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        val buffer = ByteBuffer
            .allocateDirect(cubeData.data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(cubeData.data)
                position(0)
            }

        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
            lutSize, lutSize, lutSize, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, buffer
        )

        checkGlError("setLUT")
        isReady = true
    }

    @Synchronized
    fun processBitmap(bitmap: Bitmap): Bitmap? {
        if (!isReady) return bitmap

        if (!ensureCurrent()) {
            return bitmap
        }

        val w = bitmap.width
        val h = bitmap.height

        if (w != width || h != height) {
            width = w
            height = h
            createFramebuffer()
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)

        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "inputImageTexture"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "lutTexture"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "lutSize"), lutSize.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "strength"), strength)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        drawQuad()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        checkGlError("processBitmap render")

        val buf = ByteBuffer.allocateDirect(w * h * 4)
            .order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        checkGlError("processBitmap readpixels")

        val pixels = IntArray(w * h)
        buf.asIntBuffer().get(pixels)

        // 顶点着色器里 y=-1 对应 v=0（即 bitmap 第一行），framebuffer 第 0 行存的就是
        // bitmap 的第一行，readPixels 也按这个顺序返回，所以这里只需要做 RGBA->ARGB 转换，
        // 不能再翻转一次，否则输出会上下颠倒。
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p and 0xFF00FF00.toInt()) or
                ((p and 0xFF) shl 16) or
                ((p ushr 16) and 0xFF)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun createFramebuffer() {
        if (inputTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        }
        if (outputTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(outputTexture), 0)
        }
        if (fbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        }

        val texIn = IntArray(1)
        GLES30.glGenTextures(1, texIn, 0)
        inputTexture = texIn[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val texOut = IntArray(1)
        GLES30.glGenTextures(1, texOut, 0)
        outputTexture = texOut[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outputTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )

        val fb = IntArray(1)
        GLES30.glGenFramebuffers(1, fb, 0)
        fbo = fb[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, outputTexture, 0
        )

        checkGlError("createFramebuffer")
    }

    private fun drawQuad() {
        val vertBuffer = vertexBuffer ?: return

        val stride = 5 * 4
        val aPos = GLES30.glGetAttribLocation(program, "position")
        val aTex = GLES30.glGetAttribLocation(program, "inputTexCoord")

        vertBuffer.position(0)
        GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, stride, vertBuffer)
        GLES30.glEnableVertexAttribArray(aPos)

        vertBuffer.position(3)
        GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, stride, vertBuffer)
        GLES30.glEnableVertexAttribArray(aTex)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPos)
        GLES30.glDisableVertexAttribArray(aTex)

        checkGlError("drawQuad")
    }

    private fun createProgram(vert: String, frag: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vert)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, frag)

        if (vs == 0 || fs == 0) return 0

        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            android.util.Log.e("TetrahedralLUT", "Program link failed: $log")
            GLES30.glDeleteProgram(prog)
            return 0
        }

        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            android.util.Log.e("TetrahedralLUT", "Shader compile failed: $log")
            GLES30.glDeleteShader(shader)
            return 0
        }

        return shader
    }

    private fun checkGlError(op: String) {
        var error = GLES30.glGetError()
        while (error != GLES30.GL_NO_ERROR) {
            android.util.Log.e("TetrahedralLUT", "$op: glError $error")
            error = GLES30.glGetError()
        }
    }

    @Synchronized
    fun release() {
        if (eglContext != null) ensureCurrent()
        releaseLUT()

        if (inputTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        }
        if (outputTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(outputTexture), 0)
        }
        if (fbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        }

        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != null) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
        }
        if (eglContext != null) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
        }
        if (eglDisplay != null) {
            EGL14.eglTerminate(eglDisplay)
        }
    }

    private fun releaseLUT() {
        if (lutTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(lutTexture), 0)
            lutTexture = 0
        }
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
        isReady = false
    }
}