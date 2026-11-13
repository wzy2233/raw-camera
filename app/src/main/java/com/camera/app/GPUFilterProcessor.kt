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

class GPUFilterProcessor {

    private var program = 0
    private var lutTexture = 0
    private var lutSize = 0
    private var isReady = false
    private var inputTexture = 0
    private var outputTexture = 0
    private var fbo = 0
    private var width = 0
    private var height = 0

    companion object {
        private const val VERTEX = """
            #version 300 es
            in vec4 position;
            in vec2 inputTexCoord;
            out vec2 texCoord;
            void main() {
                gl_Position = position;
                texCoord = inputTexCoord;
            }
        """
        private const val FRAGMENT = """
            #version 300 es
            precision highp float;
            in vec2 texCoord;
            uniform sampler2D inputImageTexture;
            uniform sampler3D lutTexture;
            uniform float lutSize;
            out vec4 fragColor;
            void main() {
                vec4 color = texture(inputImageTexture, texCoord);
                float scale = (lutSize - 1.0) / lutSize;
                float offset = 0.5 / lutSize;
                vec3 coord = vec3(color.r * scale + offset,
                                 color.g * scale + offset,
                                 color.b * scale + offset);
                fragColor = vec4(texture(lutTexture, coord).rgb, color.a);
            }
        """
        private val VERTICES = floatArrayOf(
                -1f, -1f, 0f, 0f, 1f,
                1f, -1f, 0f, 1f, 1f,
                -1f,  1f, 0f, 0f, 0f,
                1f,  1f, 0f, 1f, 0f
        )
    }

    /** 初始化 LUT 纹理 */
    fun setLUT(cubeData: CUBEData) {
        releaseLUT()
        lutSize = cubeData.size
        program = createProgram(VERTEX, FRAGMENT)
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        lutTexture = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        val buffer = ByteBuffer.allocateDirect(cubeData.data.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(cubeData.data); position(0)
        }
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
                lutSize, lutSize, lutSize, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, buffer)
        isReady = true
    }

    /** 使用 GPU 处理 Bitmap */
    fun processBitmap(bitmap: Bitmap): Bitmap {
        if (!isReady) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        if (w != width || h != height) {
            width = w; height = h
            createFramebuffer()
        }
        // 上传输入纹理
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "inputImageTexture"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "lutTexture"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "lutSize"), lutSize.toFloat())

        // 渲染到 FBO
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)
        drawQuad()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // 读取像素
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        val pixels = IntArray(w * h)
        buf.asIntBuffer().get(pixels)
        // OpenGL 读出的像素是上下翻转的，翻转回来
        val flipped = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                flipped[y * w + x] = pixels[(h - 1 - y) * w + x]
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(flipped, 0, w, 0, 0, w, h)
        return result
    }

    private fun createFramebuffer() {
        GLES30.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        GLES30.glDeleteTextures(1, intArrayOf(outputTexture), 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)

        // 输入纹理
        val texIn = IntArray(1)
        GLES30.glGenTextures(1, texIn, 0)
        inputTexture = texIn[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        // 输出纹理 + FBO
        val texOut = IntArray(1)
        GLES30.glGenTextures(1, texOut, 0)
        outputTexture = texOut[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outputTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)

        val fb = IntArray(1)
        GLES30.glGenFramebuffers(1, fb, 0)
        fbo = fb[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, outputTexture, 0)
    }

    private fun drawQuad() {
        val buf = ByteBuffer.allocateDirect(VERTICES.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(VERTICES); position(0) }
        val aPos = GLES30.glGetAttribLocation(program, "position")
        val aTex = GLES30.glGetAttribLocation(program, "inputTexCoord")
        GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 20, buf)
        GLES30.glEnableVertexAttribArray(aPos)
        buf.position(3)
        GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 20, buf)
        GLES30.glEnableVertexAttribArray(aTex)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        releaseLUT()
        GLES30.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        GLES30.glDeleteTextures(1, intArrayOf(outputTexture), 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
    }

    private fun releaseLUT() {
        if (lutTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(lutTexture), 0)
        if (program != 0) GLES30.glDeleteProgram(program)
        isReady = false
    }

    private fun createProgram(vert: String, frag: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vert)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, frag)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs); GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src); GLES30.glCompileShader(shader)
        return shader
    }
}
