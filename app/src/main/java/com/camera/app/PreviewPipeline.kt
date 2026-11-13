package com.camera.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * 取景画面上的实时"成片同款"处理。
 *
 * 以前预览只套了一条伽马空间的 ColorMatrix（近似校准），而照片走的是
 *   两点校准(LUT, 线性光) -> 暗部中性化(自适应) -> 滤镜(3D LUT / 矩阵)
 * 两条链路不一样，于是"预览看着还行，拍出来又变了个样"。
 *
 * 这里把同一条链路写成一个 AGSL 运行时着色器，直接作用在 PreviewView 的渲染节点上：
 *   1) 解码到线性光，减黑电平、乘白点增益，再编码回伽马空间
 *   2) 暗部按亮度权重减掉色偏（delta 由 PreviewStatsAnalyzer 实时统计得来）
 *   3) 滤镜：内置滤镜走 3x3 矩阵，.cube 走 2D 图集上的四面体插值
 * 顺序、公式与 postProcessPhoto 完全一致，所以取景即所得。
 *
 * 需要 API 33（RuntimeShader）。低版本退回 ColorMatrix 近似（只有校准，没有滤镜和暗部处理）。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class PreviewPipeline {

    companion object {
        private const val TAG = "PreviewPipeline"

        /** 暗部权重衰减的拐点，和 ShadowNeutralizer.KNEE 保持一致（72/255） */
        private const val KNEE = 72f / 255f

        /** LUT 图集超过这个尺寸就先重采样，16384 宽的纹理很多 GPU 都不收 */
        private const val MAX_ATLAS_N = 40
        private const val RESAMPLE_N = 33

        private val SHADER = """
            uniform shader uInput;
            uniform shader uLut;

            uniform float uLutN;
            uniform float uLutN2;
            uniform float uLutOn;
            uniform float uLutStrength;

            uniform float uCalOn;
            uniform float3 uBlackLin;
            uniform float3 uGain;

            uniform float uCcmOn;
            uniform float3 uCcm0;
            uniform float3 uCcm1;
            uniform float3 uCcm2;

            uniform float uShadowOn;
            uniform float3 uShadowDelta;
            uniform float uShadowDenom;

            uniform float uMatOn;
            uniform float3 uM0;
            uniform float3 uM1;
            uniform float3 uM2;

            float3 lutTexel(float r, float g, float b) {
                float2 uv = float2((r + g * uLutN + 0.5) / uLutN2, (b + 0.5) / uLutN);
                half4 t = uLut.eval(uv);
                return float3(t.r, t.g, t.b);
            }

            // 四面体插值，和照片链路里 TetrahedralLUTFilter 的分支完全对应
            float3 applyLut(float3 c) {
                float n = uLutN - 1.0;
                float3 s = clamp(c, 0.0, 1.0) * n;
                float3 base = floor(s);
                float3 f = s - base;

                float3 c000 = lutTexel(base.x, base.y, base.z);
                float3 c111 = lutTexel(base.x + 1.0, base.y + 1.0, base.z + 1.0);
                float3 out3 = c000;

                if (f.x >= f.y && f.y >= f.z) {
                    float3 c100 = lutTexel(base.x + 1.0, base.y, base.z);
                    float3 c110 = lutTexel(base.x + 1.0, base.y + 1.0, base.z);
                    out3 = mix(mix(c000, c100, f.x), mix(c110, c111, f.x), f.y);
                } else if (f.x >= f.z && f.z >= f.y) {
                    float3 c100 = lutTexel(base.x + 1.0, base.y, base.z);
                    float3 c101 = lutTexel(base.x + 1.0, base.y, base.z + 1.0);
                    out3 = mix(mix(c000, c100, f.x), mix(c101, c111, f.x), f.z);
                } else if (f.y >= f.x && f.x >= f.z) {
                    float3 c010 = lutTexel(base.x, base.y + 1.0, base.z);
                    float3 c110 = lutTexel(base.x + 1.0, base.y + 1.0, base.z);
                    out3 = mix(mix(c000, c010, f.y), mix(c110, c111, f.y), f.x);
                } else if (f.y >= f.z && f.z >= f.x) {
                    float3 c010 = lutTexel(base.x, base.y + 1.0, base.z);
                    float3 c011 = lutTexel(base.x, base.y + 1.0, base.z + 1.0);
                    out3 = mix(mix(c000, c010, f.y), mix(c011, c111, f.y), f.z);
                } else if (f.z >= f.x && f.x >= f.y) {
                    float3 c001 = lutTexel(base.x, base.y, base.z + 1.0);
                    float3 c101 = lutTexel(base.x + 1.0, base.y, base.z + 1.0);
                    out3 = mix(mix(c000, c001, f.z), mix(c101, c111, f.z), f.x);
                } else {
                    float3 c001 = lutTexel(base.x, base.y, base.z + 1.0);
                    float3 c011 = lutTexel(base.x, base.y + 1.0, base.z + 1.0);
                    out3 = mix(mix(c000, c001, f.z), mix(c011, c111, f.z), f.y);
                }
                return mix(c, out3, uLutStrength);
            }

            half4 main(float2 coord) {
                half4 src = uInput.eval(coord);
                // 相机帧是不透明的，alpha 恒为 1，这里逐分量取出来避免依赖隐式类型转换
                float alpha = float(src.a);
                float3 c = float3(src.r, src.g, src.b);
                float3 g = float3(2.2, 2.2, 2.2);
                float3 invG = float3(0.4545, 0.4545, 0.4545);

                // 1) 线性段：两点校准 + 3x3 色彩矩阵（CCM 必须在线性光里做，伽马空间里做矩阵乘法是错的）
                if (uCalOn > 0.5 || uCcmOn > 0.5) {
                    float3 lin = pow(clamp(c, 0.0, 1.0), g);
                    if (uCalOn > 0.5) {
                        lin = clamp((lin - uBlackLin) * uGain, 0.0, 1.0);
                    }
                    if (uCcmOn > 0.5) {
                        lin = clamp(
                            float3(dot(uCcm0, lin), dot(uCcm1, lin), dot(uCcm2, lin)),
                            0.0, 1.0
                        );
                    }
                    c = pow(lin, invG);
                }

                // 2) 暗部中性化：越黑权重越大，拐点以上不动
                //    0.28235294 = 72/255，对应 ShadowNeutralizer.KNEE
                if (uShadowOn > 0.5) {
                    float luma = dot(c, float3(0.299, 0.587, 0.114));
                    float w = clamp((0.28235294 - luma) / uShadowDenom, 0.0, 1.0);
                    c = clamp(c - uShadowDelta * w, 0.0, 1.0);
                }

                // 3) 滤镜
                if (uMatOn > 0.5) {
                    c = clamp(float3(dot(uM0, c), dot(uM1, c), dot(uM2, c)), 0.0, 1.0);
                }
                if (uLutOn > 0.5) {
                    c = applyLut(c);
                }

                return half4(c.r, c.g, c.b, alpha);
            }
        """.trimIndent()

        /** 着色器编译失败时不要连带把 App 拖崩，直接返回 null 走降级路径 */
        fun create(): PreviewPipeline? = try {
            PreviewPipeline()
        } catch (t: Throwable) {
            Log.e(TAG, "AGSL 着色器初始化失败，退回矩阵近似: ${t.message}")
            null
        }
    }

    private val shader = RuntimeShader(SHADER)

    private var lutAtlas: Bitmap? = null
    private var lutShader: BitmapShader? = null
    private var lutKey: String? = null

    private var calOn = false
    private var ccmOn = false
    private var shadowOn = false
    private var matOn = false
    private var lutOn = false
    private var strength = 0.7f

    init {
        // 先把所有 uniform 置成"不处理"，避免着色器里有未初始化的值
        shader.setFloatUniform("uLutOn", 0f)
        shader.setFloatUniform("uLutStrength", 1f)
        shader.setFloatUniform("uLutN", 2f)
        shader.setFloatUniform("uLutN2", 4f)
        shader.setFloatUniform("uCalOn", 0f)
        shader.setFloatUniform("uBlackLin", 0f, 0f, 0f)
        shader.setFloatUniform("uGain", 1f, 1f, 1f)
        shader.setFloatUniform("uCcmOn", 0f)
        shader.setFloatUniform("uCcm0", 1f, 0f, 0f)
        shader.setFloatUniform("uCcm1", 0f, 1f, 0f)
        shader.setFloatUniform("uCcm2", 0f, 0f, 1f)
        shader.setFloatUniform("uShadowOn", 0f)
        shader.setFloatUniform("uShadowDelta", 0f, 0f, 0f)
        shader.setFloatUniform("uShadowDenom", 1f)
        shader.setFloatUniform("uMatOn", 0f)
        shader.setFloatUniform("uM0", 1f, 0f, 0f)
        shader.setFloatUniform("uM1", 0f, 1f, 0f)
        shader.setFloatUniform("uM2", 0f, 0f, 1f)
        shader.setInputShader("uLut", placeholderShader())
    }

    /** 没有任何处理时不要挂 RenderEffect，省一层全屏合成 */
    fun isNeutral(): Boolean = !calOn && !ccmOn && !shadowOn && !matOn && !lutOn

    /** 色卡解出来的 3x3（行优先，作用在线性光上） */
    fun setCcm(matrix: FloatArray?) {
        if (matrix == null || matrix.size < 9) {
            ccmOn = false
            shader.setFloatUniform("uCcmOn", 0f)
            return
        }
        ccmOn = true
        shader.setFloatUniform("uCcmOn", 1f)
        shader.setFloatUniform("uCcm0", matrix[0], matrix[1], matrix[2])
        shader.setFloatUniform("uCcm1", matrix[3], matrix[4], matrix[5])
        shader.setFloatUniform("uCcm2", matrix[6], matrix[7], matrix[8])
    }

    fun setCalibration(calibration: ColorCalibration?) {
        val params = calibration?.shaderParams()
        if (params == null) {
            calOn = false
            shader.setFloatUniform("uCalOn", 0f)
            return
        }
        calOn = true
        shader.setFloatUniform("uCalOn", 1f)
        shader.setFloatUniform("uBlackLin", params[0], params[1], params[2])
        shader.setFloatUniform("uGain", params[3], params[4], params[5])
    }

    /**
     * @param delta 0..1 量纲的三通道偏移，null 表示关掉暗部处理
     * @param darkLuma 0..255，暗部中位亮度；越小说明画面里真有暗部，权重曲线才有效
     */
    fun setShadow(delta: FloatArray?, darkLuma: Float) {
        if (delta == null || delta.size < 3) {
            shadowOn = false
            shader.setFloatUniform("uShadowOn", 0f)
            return
        }
        val denom = (KNEE - darkLuma / 255f).coerceAtLeast(0.004f)
        shadowOn = true
        shader.setFloatUniform("uShadowOn", 1f)
        shader.setFloatUniform("uShadowDelta", delta[0], delta[1], delta[2])
        shader.setFloatUniform("uShadowDenom", denom)
    }

    fun setFilter(filterKey: String?, filter: ImageFilter?, lutStrength: Float) {
        strength = lutStrength.coerceIn(0f, 1f)
        shader.setFloatUniform("uLutStrength", strength)

        if (filter == null || (filter.type == FilterType.BUILTIN && filter.name == "Original")) {
            matOn = false
            lutOn = false
            shader.setFloatUniform("uMatOn", 0f)
            shader.setFloatUniform("uLutOn", 0f)
            return
        }

        when (filter.type) {
            FilterType.BUILTIN -> {
                val m = builtinMatrix(filter.name) ?: run {
                    matOn = false
                    shader.setFloatUniform("uMatOn", 0f)
                    return
                }
                matOn = true
                lutOn = false
                shader.setFloatUniform("uMatOn", 1f)
                shader.setFloatUniform("uM0", m[0], m[1], m[2])
                shader.setFloatUniform("uM1", m[3], m[4], m[5])
                shader.setFloatUniform("uM2", m[6], m[7], m[8])
                shader.setFloatUniform("uLutOn", 0f)
            }

            FilterType.CUSTOM_CUBE -> {
                matOn = false
                shader.setFloatUniform("uMatOn", 0f)
                val cube = filter.cubeData
                if (cube == null) {
                    lutOn = false
                    shader.setFloatUniform("uLutOn", 0f)
                    return
                }
                val key = filterKey ?: (cube.title + "@" + cube.size)
                if (key != lutKey) {
                    if (!uploadAtlas(cube)) {
                        lutOn = false
                        shader.setFloatUniform("uLutOn", 0f)
                        return
                    }
                    lutKey = key
                }
                lutOn = true
                shader.setFloatUniform("uLutOn", 1f)
            }
        }
    }

    /**
     * 每次调用都新建一个 RenderEffect。
     *
     * 注意：不要缓存这个对象。实测（Magic6 / Android 14）RenderEffect 会把着色器当前的
     * uniform 快照进去，缓存同一个 effect 再改 uniform，画面不会跟着变——
     * 表现就是"选了滤镜预览没反应"。重建一个节点的开销可以忽略，
     * 调用方按需（参数变化时）重建即可。
     */
    fun effectOrNull(): RenderEffect? {
        if (isNeutral()) return null
        return RenderEffect.createRuntimeShaderEffect(shader, "uInput")
    }

    fun release() {
        lutAtlas?.recycle()
        lutAtlas = null
        lutShader = null
        lutKey = null
    }

    // ---------------- 内部 ----------------

    private fun placeholderShader(): BitmapShader {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFFFFFFFF.toInt())
        return BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    /**
     * .cube 的排布是 b 最外层、r 最快，正好摊成 n*n 宽、n 高的图集：
     *   x = r + g * n，y = b
     * 采样时取格点中心，配合 NEAREST 就是精确 texel，四面体插值才不会被硬件插值糊掉。
     */
    private fun uploadAtlas(raw: CUBEData): Boolean {
        return try {
            val cube = if (raw.size > MAX_ATLAS_N) resample(raw, RESAMPLE_N) else raw
            val n = cube.size
            val width = n * n
            val bitmap = Bitmap.createBitmap(width, n, Bitmap.Config.ARGB_8888)
            val row = IntArray(width)

            for (b in 0 until n) {
                for (g in 0 until n) {
                    val base = g * n
                    for (r in 0 until n) {
                        val idx = ((b.toLong() * n * n) + (g.toLong() * n) + r).toInt() * 3
                        val cr = (cube.data[idx].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                        val cg = (cube.data[idx + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                        val cb = (cube.data[idx + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                        row[base + r] = (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
                    }
                }
                bitmap.setPixels(row, 0, width, 0, b, width, 1)
            }

            // 采样点都落在 texel 中心（+0.5），所以硬件插值模式取 NEAREST / LINEAR
            // 拿到的都是同一个精确值，不需要额外设置 filterMode
            val bs = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setInputShader("uLut", bs)
            shader.setFloatUniform("uLutN", n.toFloat())
            shader.setFloatUniform("uLutN2", (n * n).toFloat())

            lutAtlas?.recycle()
            lutAtlas = bitmap
            lutShader = bs
            true
        } catch (e: Exception) {
            Log.e(TAG, "LUT 图集上传失败", e)
            false
        }
    }

    /** 三线性重采样到较小的边长，给超大 LUT 用 */
    private fun resample(src: CUBEData, target: Int): CUBEData {
        val n = src.size
        val out = FloatArray(target * target * target * 3)
        var o = 0
        for (b in 0 until target) {
            val sb = b.toFloat() / (target - 1) * (n - 1)
            for (g in 0 until target) {
                val sg = g.toFloat() / (target - 1) * (n - 1)
                for (r in 0 until target) {
                    val sr = r.toFloat() / (target - 1) * (n - 1)
                    val v = sampleCube(src, sr, sg, sb)
                    out[o++] = v[0]
                    out[o++] = v[1]
                    out[o++] = v[2]
                }
            }
        }
        return CUBEData(src.title, target, out)
    }

    private fun sampleCube(cube: CUBEData, r: Float, g: Float, b: Float): FloatArray {
        val n = cube.size
        val r0 = r.toInt().coerceIn(0, n - 1)
        val g0 = g.toInt().coerceIn(0, n - 1)
        val b0 = b.toInt().coerceIn(0, n - 1)
        val r1 = (r0 + 1).coerceAtMost(n - 1)
        val g1 = (g0 + 1).coerceAtMost(n - 1)
        val b1 = (b0 + 1).coerceAtMost(n - 1)
        val fr = r - r0
        val fg = g - g0
        val fb = b - b0

        fun at(rr: Int, gg: Int, bb: Int, ch: Int): Float {
            val idx = ((bb.toLong() * n * n) + (gg.toLong() * n) + rr).toInt() * 3 + ch
            return cube.data.getOrElse(idx) { 0f }
        }

        val out = FloatArray(3)
        for (ch in 0 until 3) {
            val c00 = at(r0, g0, b0, ch) * (1 - fr) + at(r1, g0, b0, ch) * fr
            val c10 = at(r0, g1, b0, ch) * (1 - fr) + at(r1, g1, b0, ch) * fr
            val c01 = at(r0, g0, b1, ch) * (1 - fr) + at(r1, g0, b1, ch) * fr
            val c11 = at(r0, g1, b1, ch) * (1 - fr) + at(r1, g1, b1, ch) * fr
            val c0 = c00 * (1 - fg) + c10 * fg
            val c1 = c01 * (1 - fg) + c11 * fg
            out[ch] = c0 * (1 - fb) + c1 * fb
        }
        return out
    }

    /** 内置滤镜的 3x3 矩阵，系数与成片链路共用一份 */
    private fun builtinMatrix(name: String): FloatArray? = FilterManager.builtinMatrix(name)
}
