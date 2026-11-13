package com.camera.app

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.util.Log
import java.nio.ByteOrder

/**
 * 自研 RAW 解码：Bayer RAW16 -> 位图。
 *
 * 走的是最标准的一条路，没有任何 ISP 参与：
 *   1. 按 CFA 位置减黑电平，除以 (白电平 - 黑电平) 归一化到 0..1
 *   2. 双线性去马赛克
 *   3. 乘白平衡增益（用相机当次采集算出来的增益）
 *   4. 颜色矩阵：sensor -> XYZ -> sRGB（用相机自带的 colorTransform + forwardMatrix）
 *   5. sRGB 伽马编码
 *
 * 输出会比手机直出"平"，因为没有 ISP 那条 S 形对比曲线和饱和度提升 —— 这是 RAW 的本色，
 * 想要讨喜的观感得自己调曲线。
 */
object RawToBitmap {

    private const val TAG = "RawToBitmap"

    /**
     * 当前这张图用的开关快照。
     *
     * 为什么要缓存：这些值在 processRows 里是逐像素要用的（尤其高光重建和肩部），
     * 直接调 RawSettings 每次都会走 SharedPreferences（加锁 + Map 查找）。
     * 12MP × 3 通道 = 3800 万次调用，实测直接把 2 秒的解码拖成 5 分钟以上。
     * 每张图开头读一次，之后只读这几个 volatile 字段。
     */
    @Volatile
    private var useShoulder = true

    /** 高光重建：任一通道到白电平就把该像素恢复成中性白 */
    @Volatile
    private var useHlRecon = true

    /** 曝光归一化的目标中位亮度（线性），让暗场景不至于被曲线压死 */
    private const val TARGET_MEDIAN = 0.06f

    /** 临时诊断：打印各阶段统计量，定位偏色来源 */
    private const val DEBUG_STATS = false

    /**
     * 是否自动做曝光归一化（把画面中位亮度拉到 TARGET_MEDIAN）。
     *
     * **已关闭**：这一步会替用户改整体亮度，暗场景最多拉亮 2.2 倍——
     * 表现就是"拍出来比看到的亮很多"，而且拉亮之后高光更容易爆。
     * 现在的策略是：RAW 里是多少就是多少，只做曝光/色调编码，不动亮度。
     * 需要提亮请在后期做，不要在这里偷偷做。
     */
    private const val AUTO_EXPOSURE = false

    /** 曝光系数上下限，别把夜景拉成白天 */
    private const val MIN_EXPOSURE = 0.7f
    private const val MAX_EXPOSURE = 2.2f

    /**
     * 对比曲线强度：0 = 不压，越大越硬。
     *
     * **已设为 0**：用户要求"RAW 里是什么样，JPG 就是什么样"，
     * S 曲线（smoothstep 混合）属于对影调的二次创作，不属于忠实还原。
     * 现在只做 linear -> sRGB 编码这一步，影调关系与 RAW 完全一致（只是换了编码）。
     */
    private const val CONTRAST = 0f

    /** 高光肩部起点（线性）：超过它的部分开始平滑压缩，不再线性增长 */
    private const val SHOULDER = 0.88f

    /** 色调 LUT 覆盖的线性上限；>1 的部分是为了给肩部留出余量 */
    private const val LUT_MAX = 4f

    private const val LUT_SIZE = 5120

    /** 判定"打到白电平"的阈值（归一化后）。留一点余量，避免插值误差漏判 */
    private const val CLIP_LEVEL = 0.99f

    /** 其中前多少级覆盖 0..1（其余覆盖 1..LUT_MAX） */
    private const val LUT_NORMAL = 4096

    /**
     * Photoshop Camera Raw 实测渲染曲线（反查表）。
     *
     * 怎么来的：拿同一张 RAW 和 PS 导出的 JPG 逐像素配对，横轴是 RAW 线性值、
     * 纵轴是 JPG 输出码值，几十万像素取中位数就得到 PS 实际用的那条曲线。
     * 四张不同曝光的照片给出完全相同的曲线，所以它不是"自动曝光"，而是固定渲染。
     *
     * 存的是**反查**方向：第 i 个锚点 = "输出码值 i*255/128 所对应的线性输入"。
     * 按输出等分的好处是每个间隔只差 2 个码值，插值误差必然 < 1 码，
     * 暗部（曲线弯得最厉害的地方）也不会被稀释。
     *
     * 实测关键点（对比纯 sRGB 编码）：
     *   中灰  code 128 <- lin 0.1147   （sRGB 需要 0.2158，差整整一档）
     *         code 192 <- lin 0.2480   （sRGB 需要 0.5270）
     * 也就是说 PS 把中间调整体提亮了约 1EV，高光再用软肩收回来。
     * 实测覆盖到 lin 0.647（code 240），之后是平滑饱和到 255。
     */
    private val PS_LIN_RAW = floatArrayOf(
        0.000000f, 0.000837f, 0.001674f, 0.002510f, 0.003347f, 0.004184f,
        0.005021f, 0.005857f, 0.006694f, 0.007531f, 0.008368f, 0.009205f,
        0.010041f, 0.010878f, 0.011715f, 0.016023f, 0.017078f, 0.018138f,
        0.022502f, 0.023569f, 0.024636f, 0.028746f, 0.029364f, 0.029963f,
        0.030562f, 0.031161f, 0.038107f, 0.038803f, 0.039232f, 0.039662f,
        0.040092f, 0.040521f, 0.040951f, 0.041381f, 0.045127f, 0.045701f,
        0.046249f, 0.046797f, 0.047344f, 0.047895f, 0.052359f, 0.054961f,
        0.056214f, 0.057413f, 0.061867f, 0.062910f, 0.063952f, 0.068190f,
        0.069221f, 0.070158f, 0.071095f, 0.075788f, 0.077432f, 0.079191f,
        0.081018f, 0.085679f, 0.086925f, 0.091461f, 0.092601f, 0.093742f,
        0.101538f, 0.102893f, 0.104253f, 0.108443f, 0.109233f, 0.110023f,
        0.110813f, 0.114836f, 0.115507f, 0.116178f, 0.116849f, 0.117523f,
        0.125208f, 0.126465f, 0.131059f, 0.133233f, 0.139381f, 0.144037f,
        0.149360f, 0.154687f, 0.156997f, 0.164362f, 0.171096f, 0.172745f,
        0.177735f, 0.179506f, 0.188114f, 0.190473f, 0.197062f, 0.203240f,
        0.212478f, 0.218172f, 0.223695f, 0.225949f, 0.234923f, 0.241031f,
        0.249241f, 0.258053f, 0.264218f, 0.274078f, 0.280811f, 0.287400f,
        0.297478f, 0.304638f, 0.311997f, 0.322727f, 0.334720f, 0.344999f,
        0.356920f, 0.368185f, 0.383951f, 0.396567f, 0.412964f, 0.430202f,
        0.446630f, 0.468813f, 0.492031f, 0.516776f, 0.540449f, 0.570009f,
        0.630374f, 0.675145f, 0.704983f, 0.736755f, 0.771024f, 0.808705f,
        0.851527f, 0.903727f, 1.000000f,
    )

    /**
     * 平滑后的锚点表（与 native 侧 psKnots() 完全同一套算法）。
     *
     * 原表是 129 个**按输出等间隔**的锚点，是逐像素反解出来的，带 ±1 码拟合噪声。
     * 曲线本身很平的段落，噪声会被除法放大成斜率锯齿（实测相邻段斜率在 1.1 与 18.2
     * 之间反复跳，最大跳变 11.6 倍）——平滑渐变上就出现一圈圈等高线，
     * 而逐通道查表又让 R/G/B 各自跳，颜色跟着亮度阶梯式变化。
     *
     * 八轮 [1,2,1]/4 + 强制单调之后：与原表输出偏差中位 0.4 码、最大 3.4 码，
     * 斜率跳变 11.6 倍 -> 1.4 倍。
     */
    private val PS_LIN: FloatArray = run {
        val s = PS_LIN_RAW.copyOf()
        repeat(8) {
            val n = s.copyOf()
            for (i in 1 until s.size - 1) n[i] = 0.25f * (s[i - 1] + 2f * s[i] + s[i + 1])
            for (i in 1 until s.size) if (n[i] < n[i - 1]) n[i] = n[i - 1]
            n[s.size - 1] = PS_LIN_RAW.last()
            for (i in s.indices) s[i] = n[i]
        }
        s
    }

    /**
     * 线性值 -> 输出（0..1），沿 PS 实测曲线。
     * 二分查找 129 个锚点，只需 7 步；调用方只在建 LUT 时用，不在像素循环里。
     *
     * 注意：这里没有"高光余量"分支了。HDR 的高光由 native 那边的 exposure fusion
     * 负责压（见 hdr_drc.cpp），轮不到这条曲线。以前那两段代码
     * （暗部二次提亮 + 超范围软滚降）是旧做法的残留，已经删掉。
     */
    /**
     * 三次样条的系数 [k, b, c, d]（与 native 侧 PsSpline 完全同一套算法）。
     * 段内 y(t) = y_i + b_i·t + c_i·t² + d_i·t³，t = x - k_i。
     * 自然边界（两端二阶导为 0），Thomas 算法解三对角。
     */
    private val PS_SPLINE: Array<FloatArray> = run {
        val n = PS_LIN.size
        val h = FloatArray(n - 1)
        val m = FloatArray(n - 1)
        for (i in 0 until n - 1) {
            h[i] = PS_LIN[i + 1] - PS_LIN[i]
            m[i] = (1f / (n - 1)) / h[i]
        }
        val lower = FloatArray(n); val diag = FloatArray(n)
        val upper = FloatArray(n); val rhs = FloatArray(n)
        diag[0] = 1f; diag[n - 1] = 1f
        for (i in 1 until n - 1) {
            lower[i] = h[i - 1]
            diag[i] = 2f * (h[i - 1] + h[i])
            upper[i] = h[i]
            rhs[i] = 3f * (m[i - 1] * h[i] + m[i] * h[i - 1])
        }
        val cp = FloatArray(n); val dp = FloatArray(n)
        cp[0] = upper[0] / diag[0]; dp[0] = rhs[0] / diag[0]
        for (i in 1 until n) {
            val den = diag[i] - lower[i] * cp[i - 1]
            cp[i] = upper[i] / den
            dp[i] = (rhs[i] - lower[i] * dp[i - 1]) / den
        }
        val c = FloatArray(n)
        c[n - 1] = dp[n - 1]
        for (i in n - 2 downTo 0) c[i] = dp[i] - cp[i] * c[i + 1]
        val b = FloatArray(n); val d = FloatArray(n)
        for (i in 0 until n - 1) {
            b[i] = (1f / (n - 1)) / h[i] - h[i] * (2f * c[i] + c[i + 1]) / 3f
            d[i] = (c[i + 1] - c[i]) / (3f * h[i])
        }
        arrayOf(PS_LIN, b, c, d)
    }

    private fun psCurve(lin: Float): Float {
        if (lin <= 0f) return 0f
        val last = PS_LIN.size - 1
        if (lin >= PS_LIN[last]) return 1f
        var lo = 0
        var hi = last
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (PS_LIN[mid] <= lin) lo = mid else hi = mid
        }
        val t = lin - PS_LIN[lo]
        val sp = PS_SPLINE
        return lo / last.toFloat() + sp[1][lo] * t + sp[2][lo] * t * t + sp[3][lo] * t * t * t
    }

    private const val SATURATION = 1f

    /**
     * 色调曲线查找表，输入范围 0..LUT_MAX（不是 0..1）。
     *
     * 为什么要扩到这里：原来把 >1 的线性值直接 coerceIn(0,1) 硬切成 1，
     * 高光一旦过界就变成一片死白——红色高光（矩阵 R 行稍微偏大时尤其容易）会整块糊成纯红，
     * 细节全丢。真实相机都有 highlight rolloff（肩部），这里在 LUT 里内置：
     * 超过 SHOULDER 的部分用 1-exp(-t) 平滑逼近 1，永远到不了 1，也就不会硬削顶。
     *
     * 级数从 1536 加到 4096：LUT 是**最近点取值**（没有插值），级数就是台阶高度。
     * 1536 级时暗部一级跨 1~2 个码值，配合 HDR 的强提亮会在平滑渐变上画出
     * 一圈圈等高线（用户看到的"从中心扩散的圆环"就是这个）。
     * 4096 级把台阶压到 1 码以内，剩下的一点交给最后的抖动。
     */
    /** 关掉"高光肩部"时用这条：0..1 线性，超过就切到 1 */
    private val TONE_LUT_HARD = FloatArray(LUT_SIZE).also { lut ->
        for (i in 0 until LUT_NORMAL) lut[i] = psCurve(i / (LUT_NORMAL - 1f))
        for (i in LUT_NORMAL until LUT_SIZE) {
            val t = (i - LUT_NORMAL) / (LUT_SIZE - LUT_NORMAL - 1f)
            lut[i] = psCurve(1f + t * (LUT_MAX - 1f))
        }
    }

    private val TONE_LUT = FloatArray(LUT_SIZE).also { lut ->
        // 分两段填：0..1 用前 LUT_NORMAL 级，1..LUT_MAX 用剩下的级。
        // 现在曲线来自 PS 实测，自带软肩，所以 1..LUT_MAX 段对 >1 的输入一律收敛到 1，
        // 与 PS 的行为一致（ACR 在 baseline 下就是削顶）。
        for (i in 0 until LUT_NORMAL) {
            val v = i / (LUT_NORMAL - 1f)
            lut[i] = psCurve(v)
        }
        for (i in LUT_NORMAL until LUT_SIZE) {
            val t = (i - LUT_NORMAL) / (LUT_SIZE - LUT_NORMAL - 1f)
            lut[i] = psCurve(1f + t * (LUT_MAX - 1f))
        }
    }

    /** 线性值 -> sRGB 编码 -> S 曲线，含高光肩部 */
    private fun toneCurve(v: Float, shoulder: Float = SHOULDER): Float {
        val lin = when {
            shoulder >= 0.999f -> v.coerceAtMost(1f)   // 无肩部：超过满量程直接切
            v <= shoulder -> v
            else -> shoulder + (1f - shoulder) * (1f - kotlin.math.exp(-(v - shoulder) / (1f - shoulder)))
        }
        val encoded = if (lin <= 0.0031308f) {
            12.92f * lin
        } else {
            1.055f * Math.pow(lin.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
        }
        val sm = encoded * encoded * (3f - 2f * encoded)
        return encoded + (sm - encoded) * CONTRAST
    }

    fun convert(
        image: Image,
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult?,
        shading: OpticsCorrector.LensShading? = null,
        /** 用户用色卡标出来的传感器矩阵 */
        userMatrix: FloatArray? = null,
        /** 从 DNG 里读出的厂商 ForwardMatrix（cam白平衡后 -> sRGB 线性），优先级最高 */
        dngMatrix: FloatArray? = null,
        /**
         * HDR 多帧合并后的数据（native 合并的产物）。给了就用它，不再从 image 里读。
         * 数值仍按单帧满量程归一化，所以 1.0 对应"比正常曝光白亮 K 倍"的位置。
         */
        mergedRaw: ShortArray? = null,
        /**
         * 动态范围压缩比 K，等于采集时的欠曝倍数（1.0 = 不压缩）。
         * 它同时喂给 native 的 exposure fusion：短/长两个合成曝光相差 K 倍。
         */
        hdrScale: Float = 1f,
        /**
         * 原始连拍帧（未合并）。超分分支需要它——SR 不能用合并结果，
         * 因为合并已经把亚像素信息平均掉了。
         */
        burstFrames: Array<ShortArray>? = null,
        /**
         * 非空时：走**高位深直接落盘**（HEIF 10bit / PNG16），并把 8bit 预览图照常返回。
         * deepFormat 决定用哪个编码器。
         */
        deepOut: java.io.File? = null,
        deepFormat: Int = RawSettings.FORMAT_HEIF
    ): Bitmap? {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return null

        val tStart = System.currentTimeMillis()
        // HDR 的合并结果只有 native 管线能渲染（exposure fusion 在那里）。
        // native 不可用时退回单帧，宁可少一层也不要用旧算法糊一张出来。
        val merged = if (NativeHdr.available) mergedRaw else null
        if (mergedRaw != null && merged == null) {
            Log.w(TAG, "native 管线不可用，HDR 退回单帧渲染")
        }
        val raw = merged ?: readRaw16(image) ?: return null

        // BlackLevelPattern 是按 (column, row) 取值的，这里展开成 4 个 CFA 相位
        val blackPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val black = IntArray(4) { i ->
            try {
                blackPattern?.getOffsetForIndex(i and 1, i shr 1) ?: 64
            } catch (e: Exception) {
                64
            }
        }
        // 黑电平诊断：pattern 是整数、dynamic 是 Rational（可能带小数），
        // 两者都可能逐相位不同。G 相位差 1 DN，在欠曝 2 倍的暗部就是 20% 的比例误差（= 暗部发红）。
        run {
            val dbl = result?.get(android.hardware.camera2.CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            Log.d(TAG, "black pattern=[${black.joinToString(",")}]  dynamic=[${
                dbl?.joinToString(",") { "%.2f".format(it) } ?: "null"
            }]")
        }

        // 这张图的开关快照（见字段注释：逐像素读 SharedPreferences 会拖垮性能）
        useShoulder = RawSettings.shoulder
        // 这条 Kotlin 退路只处理单帧：HDR 的 exposure fusion 只在 native 里有。
        useHlRecon = RawSettings.highlightRecon
        val useRcdNow = RawSettings.rcd
        val useBlackNow = RawSettings.blackLevel

        val whiteLevel: Int = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val white = whiteLevel.toFloat()
        // HDR 合并后数据可以超过单帧满量程：先把分母乘上倍数，归一化才不会被削顶
        val scale = 1f / (white - black[0]).coerceAtLeast(1f)

        // 噪声地板：光减黑电平不够——归一化要 clamp 到 0，负半边被切掉后噪声被"整流"，
        // 均值从 0 抬到约 +5DN，再被白平衡增益和曝光系数放大，遮住镜头就是紫红底（实测 R43/G12/B44）。
        // 减掉 2σ 让噪声整体落到 0 以下再被 clamp 掉。必须在乘暗角增益之前估 σ。
        val pedestal = if (NoiseFloor.ENABLED) NoiseFloor.pedestal(raw, w, h, white, black[0]) else 0f
        Log.d(TAG, "读出噪声地板 = %.1f DN（%s）".format(
            pedestal, if (NoiseFloor.ENABLED) "启用" else "已关闭"
        ))

        // 前端光学校正：先修坏点，再补暗角。
        // 顺序很关键——坏点判定要在"还没乘暗角增益"的原始 DN 上做，
        // 否则四角被放大两三倍之后，普通噪声也会超过阈值被当成坏点修掉（等于偷偷降噪）。
        if (RawSettings.defectFix) {
            val tf = System.currentTimeMillis()
            val fixed = OpticsCorrector.fixDefects(raw, w, h, black, white)
            Log.d(TAG, "坏点修正 ${System.currentTimeMillis() - tf}ms（修 $fixed 个，${if (NativeHdr.available) "native" else "kotlin"}）")
        } else {
            Log.d(TAG, "坏点修正：已关闭")
        }
        // 暗角**只能乘一次**。
        //
        // native 渲染在归一化那一步就会乘这张增益表（hdr_render.cpp normalizePass），
        // Kotlin 这边再乘一遍等于 gain²——实测白纸照的边角比中心亮 20% 以上，
        // 而且越靠角越亮。所以这里只在"不走 native"时才做，native 路径把表交给它。
        val useNativeNow = RawSettings.nativePipeline && NativeHdr.available
        if (RawSettings.shading && !useNativeNow) {
            OpticsCorrector.applyShading(raw, w, h, black, shading)
        } else if (!RawSettings.shading) {
            Log.d(TAG, "暗角补偿：已关闭")
        }

        // 白平衡增益：优先用 NEUTRAL_COLOR_POINT（DNG 的 AsShotNeutral 就是它，libraw 也按它解），
        // 取倒数就是增益；拿不到再退回 COLOR_CORRECTION_GAINS
        val neutral = result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        val gains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)

        val gainR: Float
        val gainG: Float
        val gainB: Float
        val nR = neutral?.getOrNull(0)?.let { it.numerator.toFloat() / it.denominator.toFloat() } ?: 0f
        val nG = neutral?.getOrNull(1)?.let { it.numerator.toFloat() / it.denominator.toFloat() } ?: 0f
        val nB = neutral?.getOrNull(2)?.let { it.numerator.toFloat() / it.denominator.toFloat() } ?: 0f
        val ccR = gains?.red ?: 0f
        val ccG = ((gains?.greenEven ?: 0f) + (gains?.greenOdd ?: 0f)) / 2f
        val ccB = gains?.blue ?: 0f
        Log.d(TAG, String.format(
            "WB 来源对比：neutral=(%.4f,%.4f,%.4f) -> 增益 (%.3f,%.3f,%.3f) ; CC_gains=(%.3f,%.3f,%.3f)",
            nR, nG, nB,
            if (nR > 0.01f) 1f / nR else 0f, if (nG > 0.01f) 1f / nG else 0f, if (nB > 0.01f) 1f / nB else 0f,
            ccR, ccG, ccB))
        if (!RawSettings.whiteBalance) {
            // 关掉白平衡：保留传感器原生色偏（暖光下偏黄、日光下偏青）
            gainR = 1f; gainG = 1f; gainB = 1f
            Log.d(TAG, "白平衡：已关闭")
        } else if (RawSettings.wbFromCcGains && ccR > 0.01f && ccG > 0.01f && ccB > 0.01f) {
            // 用 COLOR_CORRECTION_GAINS：**DngCreator 写进 DNG 的 AsShotNeutral 就是它的倒数**，
            // 也就是 Lightroom / 各家看图软件打开同一张 DNG 时用的白平衡。
            // 实测这台机器上两条路差 6.6%（neutral 反推 R=1.580，CC 反推 R=1.482），
            // 后者才是"打开 DNG 看到的样子"。用前者会整体偏红。
            gainR = ccR / ccG
            gainG = 1f
            gainB = ccB / ccG
        } else if (nR > 0.01f && nG > 0.01f && nB > 0.01f) {
            gainR = 1f / nR
            gainG = 1f / nG
            gainB = 1f / nB
        } else {
            gainR = gains?.red ?: 1f
            gainG = ((gains?.greenEven ?: 1f) + (gains?.greenOdd ?: 1f)) / 2f
            gainB = gains?.blue ?: 1f
        }

        // 优先用用户色卡标出来的矩阵（那是这颗传感器的真实特性），
        // 其次才是相机当次真正用的色彩矩阵（行和约等于 1，是标准 CCM），
        // 最后退回 sensor->XYZ->sRGB 的静态矩阵
        // 矩阵优先级：DNG 厂商描述 > 用户色卡标定 > ISP 当次矩阵
        //
        // 为什么 DNG 优先：同一块白屏，用 ISP 的 COLOR_CORRECTION_TRANSFORM 算出来偏蓝
        // （152/197/251），用 DNG 里的 ForwardMatrix 算出来接近中性（173/231/255），
        // 而 Photoshop 打开同一张 DNG 显示的正是后者。说明 ISP 那套是给自家流水线调过的，
        // 不是规范意义的相机特性描述。
        //
        // 行和归一照做：3x3 作用在已白平衡的数据上，白必须是白，三行之和都该是 1。
        val chosen = dngMatrix ?: userMatrix ?: readAppliedTransform(result)
        if (dngMatrix != null) Log.d(TAG, "使用 DNG 厂商矩阵")
        val matrix = if (!RawSettings.colorMatrix) {
            // 关掉矩阵：直接输出传感器三通道，颜色会非常怪（偏青绿、错位），只用于诊断
            Log.d(TAG, "3x3 色彩矩阵：已关闭")
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        } else {
            chosen?.let { normalizeRowSums(it) } ?: buildColorMatrix(characteristics)
        }
        Log.d(
            TAG,
            "gains=(%.3f, %.3f, %.3f) matrix=[%.3f %.3f %.3f / %.3f %.3f %.3f / %.3f %.3f %.3f]".format(
                gainR, gainG, gainB,
                matrix[0], matrix[1], matrix[2],
                matrix[3], matrix[4], matrix[5],
                matrix[6], matrix[7], matrix[8]
            )
        )

        // 先粗采样估一下画面中位亮度，再决定曝光系数。
        // 不这么做的话，暗场景套上 S 曲线会被压成一团黑。
        val expBase = estimateExposure(raw, w, h, black, scale, gainR, gainG, gainB, pedestal)
        val exposure = expBase
        Log.d(TAG, "曝光系数=%.2f".format(exposure))

        // ---------------- native 管线 ----------------
        //
        // 同样的数学，C++/NEON 多线程。这里只做参数搬运，
        // 出问题可以在设置里关掉 nativePipe 退回下面的 Kotlin 实现（两边逐式一致）。
        if (RawSettings.nativePipeline && NativeHdr.available) {
            val t0 = System.currentTimeMillis()
            val out = IntArray(w * h)
            val cfaName = cfaNameOf(characteristics)
            val cfaN = RcdDemosaic.Cfa.of(cfaName)
            val cfaColors = IntArray(4) { cfaN.colorAt(it and 1, it shr 1) }
            // 暗角表交给 native 在归一化时乘（也只在这里乘一次）。
            // 关掉 shading 就必须传 null，否则这个开关在 native 路径里根本不起作用。
            val sh = if (RawSettings.shading) shading else null

            // ---- 超分分支 ----
            // 用原始连拍（而不是合并结果）重建全 RGB：亚像素信息一旦被平均掉就没了。
            // 失败就静默退回普通路径——SR 是增强，不能成为拍照失败的原因。
            var rgb3: ShortArray? = null
            // SR 模式合并结果本来就不存在（采集端跳过了），所以只看 burstFrames
            if (RawSettings.superRes && burstFrames != null && burstFrames.size >= 2) {
                val tsr = System.currentTimeMillis()
                try {
                    val buf = ShortArray(w * h * 3)
                    val rc = NativeHdr.nativeSuperRes(
                        burstFrames, w, h, black[0], whiteLevel, cfaColors,
                        RawSettings.srSigma,
                        sh?.gains, sh?.width ?: 0, sh?.height ?: 0,
                        sh?.channelOf ?: IntArray(4),
                        RawSettings.srLanes,
                        buf
                    )
                    if (rc == 0) {
                        rgb3 = buf
                        Log.d(TAG, "超分合并 ${System.currentTimeMillis() - tsr}ms")
                    } else {
                        Log.w(TAG, "超分失败 rc=$rc，退回普通 HDR 合并")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "超分异常，退回普通 HDR 合并: ${e.message}")
                }
            }

            NativeHdr.nativeRender(
                raw, w, h, black, white,
                sh?.gains, sh?.width ?: 0, sh?.height ?: 0,
                sh?.channelOf ?: IntArray(4),
                cfaColors,
                floatArrayOf(gainR * exposure, gainG * exposure, gainB * exposure), matrix,
                if (useRcdNow) 1 else 0,
                if (useHlRecon) 1 else 0,
                // 动态范围压缩比：等于采集时的欠曝倍数（1.0 = 不压缩）。
                // 合并后的数据按单帧满量程归一化，所以 1.0 对应"比正常曝光白亮 K 倍"的位置，
                // 这就是高光余量；同一个 K 也是 §6 两个合成曝光之间的倍数。
                // DRC 倍数：SR 模式下 merged 为空但 hdrScale 照样要传
                if (merged != null || burstFrames != null) hdrScale else 1f,
                // 色相保持修正（1 = 开）。关掉就是纯逐通道曲线，用于 A/B。
                if (RawSettings.huePreserve) 1f else 0f,
                if (RawSettings.wbRefine) 1 else 0,
                rgb3,
                out
            )
            val tRender = System.currentTimeMillis()
            // hdr = 走了多帧（不管走的是合并还是超分）
            val hdr = merged != null || burstFrames != null

            // ---- 白平衡 A/B：同一份数据、只差"要不要重估 WB"，额外存一张 ----
            // 只改 wbRefine 一个参数，其余（含 SR）完全一样，这样两版之间的差异
            // 只可能来自白平衡，是判断"重估让中性色更准还是更偏"的唯一干净办法。
            if (RawSettings.abWb) {
                try {
                    val alt = IntArray(w * h)
                    NativeHdr.nativeRender(
                        raw, w, h, black, white,
                        sh?.gains, sh?.width ?: 0, sh?.height ?: 0,
                        sh?.channelOf ?: IntArray(4),
                        cfaColors,
                        floatArrayOf(gainR * exposure, gainG * exposure, gainB * exposure), matrix,
                        if (useRcdNow) 1 else 0,
                        if (useHlRecon) 1 else 0,
                        if (merged != null || burstFrames != null) hdrScale else 1f,
                        if (RawSettings.huePreserve) 1f else 0f,
                        0,                                     // ← 只用 ISP 增益
                        rgb3,                                  // 与主图同一份 SR 数据
                        alt
                    )
                    NativeHdr.nativeFinish(
                        alt, w, h,
                        RawSettings.hdrStrength / 100f * 0.65f, 0.75f,
                        RawSettings.sharpenAmount / 100f,
                        if (RawSettings.caFix) 1f else 0f,
                        if (RawSettings.hueTune) 1f else 0f,
                        if (RawSettings.hdrLocalContrast) 1 else 0,
                        if (RawSettings.hdrChromaNr) 1 else 0,
                        1
                    )
                    val bm = makeBitmap(alt, w, h, characteristics)
                    val f = java.io.File(
                        RawSettings.appDir(),
                        "_ab_wbisp_${System.currentTimeMillis() % 100000}.jpg"
                    )
                    java.io.FileOutputStream(f).use {
                        bm.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    }
                    bm.recycle()
                    Log.d(TAG, "A/B 纯 ISP 白平衡版 -> ${f.name}")
                } catch (e: Throwable) {
                    Log.w(TAG, "A/B(WB) 导出失败: ${e.message}")
                }
            }

            // ---- 超分 A/B：同一份数据、只差"走不走 SR"，额外存一张 ----
            // 必须在 finish(out) 之前：out 马上要被就地改写。
            if (rgb3 != null && RawSettings.abSrTest && merged != null) {
                // 注意：SR 模式下 merged 一定是 null（采集端跳过合并），
                // 所以这个 A/B 分支只在"手动打开 SR 但采集端仍合并"时才有意义。
                // 现在 SR 开启就不合并了，A/B 用下面的 absr 逻辑走不到——
                // 需要对比时请关掉 SR 拍一张、再开 SR 拍一张（同场景同机位）。
                try {
                    val alt = IntArray(w * h)
                    NativeHdr.nativeRender(
                        merged, w, h, black, white,
                        sh?.gains, sh?.width ?: 0, sh?.height ?: 0,
                        sh?.channelOf ?: IntArray(4),
                        cfaColors,
                        floatArrayOf(gainR * exposure, gainG * exposure, gainB * exposure), matrix,
                        if (useRcdNow) 1 else 0,
                        if (useHlRecon) 1 else 0,
                        hdrScale,
                        if (RawSettings.huePreserve) 1f else 0f,
                        if (RawSettings.wbRefine) 1 else 0,     // A/B 两版用同一个 WB 设置
                        null,                                  // 不走 SR
                        alt
                    )
                    NativeHdr.nativeFinish(
                        alt, w, h,
                        RawSettings.hdrStrength / 100f * 0.65f, 0.75f,
                        RawSettings.sharpenAmount / 100f,
                        if (RawSettings.caFix) 1f else 0f,
                        if (RawSettings.hueTune) 1f else 0f,
                        if (RawSettings.hdrLocalContrast) 1 else 0,
                        if (RawSettings.hdrChromaNr) 1 else 0,
                        1
                    )
                    val bm = makeBitmap(alt, w, h, characteristics)
                    val f = java.io.File(
                        RawSettings.appDir(),
                        "_ab_nosr_${System.currentTimeMillis() % 100000}.jpg"
                    )
                    java.io.FileOutputStream(f).use {
                        bm.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    }
                    bm.recycle()
                    Log.d(TAG, "A/B 无超分版 -> ${f.name}")
                } catch (e: Throwable) {
                    Log.w(TAG, "A/B(无 SR) 导出失败: ${e.message}")
                }
            }

            // ---- A/B：同一份渲染结果、只差锐化，额外存一张 ----
            // 必须在 finish 之前复制：finish 是就地改 out 的，之后就回不到"未锐化"状态了。
            if (hdr && RawSettings.abTest) {
                try {
                    val copy = out.copyOf()
                    NativeHdr.nativeFinish(
                        copy, w, h,
                        RawSettings.hdrStrength / 100f * 0.65f, 0.75f,
                        0f,                                   // 锐化 = 0
                        if (RawSettings.caFix) 1f else 0f,
                        if (RawSettings.hueTune) 1f else 0f,
                        if (RawSettings.hdrLocalContrast) 1 else 0,
                        if (RawSettings.hdrChromaNr) 1 else 0,
                        1
                    )
                    val bm = makeBitmap(copy, w, h, characteristics)
                    val f = java.io.File(
                        RawSettings.appDir(),
                        "_ab_nosharp_${System.currentTimeMillis() % 100000}.jpg"
                    )
                    java.io.FileOutputStream(f).use {
                        bm.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    }
                    bm.recycle()
                    Log.d(TAG, "A/B 无锐化版 -> ${f.name}")
                } catch (e: Throwable) {
                    Log.w(TAG, "A/B 导出失败: ${e.message}")
                }
            }

            NativeHdr.nativeFinish(
                out, w, h,
                // HDR 时把局部影调降到 65%：整体影调已经由 exposure fusion 负责，
                // 收尾这一步只补局部对比。两边都满强度会叠成"提亮两次"，画面发灰发雾。
                RawSettings.hdrStrength / 100f * (if (hdr) 0.65f else 1f),
                0.75f,
                // 锐化只在 HDR 路径上开：单帧 RAW 本来就是传感器最锐的状态，
                // 再锐化只会放大噪点。
                if (hdr) RawSettings.sharpenAmount / 100f else 0f,
                if (RawSettings.caFix) 1f else 0f,
                if (RawSettings.hueTune) 1f else 0f,
                if (hdr && RawSettings.hdrLocalContrast) 1 else 0,
                if (hdr && RawSettings.hdrChromaNr) 1 else 0,
                // 高位深（HEIF/PNG）必须留着 10bit 交给编码器；
                // JPEG444 走 native 编码器时则需要先落到 8bit（编码器吃 ARGB）。
                if (deepOut == null || deepFormat == RawSettings.FORMAT_JPEG444) 1 else 0
            )
            if (deepOut != null) {
                val rot = characteristics.get(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION
                ) ?: 0
                val rc = when (deepFormat) {
                    // JPEG 4:4:4：native 编码器在采样时直接旋转，不需要那张旋转的 Bitmap
                    RawSettings.FORMAT_JPEG444 -> {
                        val tj = System.currentTimeMillis()
                        val r = NativeHdr.nativeJpeg444(out, w, h, rot, RawSettings.jpegQuality,
                            deepOut.absolutePath)
                        Log.d(TAG, "JPEG444 native 编码 ${System.currentTimeMillis() - tj}ms rc=$r")
                        r
                    }
                    RawSettings.FORMAT_HEIF ->
                        NativeHdr.nativeHeifEncode(out, w, h, rot, RawSettings.jpegQuality,
                            deepOut.absolutePath)
                    else ->
                        // zlib 级别 3：级别 6 在 12MP 上要 13.6 秒，3 大约是 1/3 的时间、体积只多 10%
                        NativeHdr.nativePng16(out, w, h, rot, 3, deepOut.absolutePath)
                }
                Log.d(TAG, "高位深输出 format=$deepFormat rot=$rot rc=$rc file=${deepOut.name} " +
                        "${if (deepOut.exists()) deepOut.length() else 0} 字节")
                // **不再生成 Bitmap**。调用方走 deep 路径时只用文件，那张 Bitmap 建完
                // 立刻就被 recycle 了——12MP 是 48MB 分配 + 一次全图拷贝，纯浪费。
            }
            Log.d(TAG, "native 分段耗时：渲染 ${tRender - t0}ms，收尾 ${System.currentTimeMillis() - tRender}ms")
            if (deepOut != null) return null
            return makeBitmap(out, w, h, characteristics)
        }

        // 归一化到 0..1（减黑电平、按白电平缩放）。RCD 在浮点域里算，精度比直接搬 16bit 整数好
        val norm = FloatArray(w * h)
        RawThreads.run(h) { _, y0, y1 ->
            for (y in y0 until y1) {
                val rowBase = y * w
                for (x in 0 until w) {
                    val i = rowBase + x
                    val v = raw[i].toInt() and 0xFFFF
                    val cfaIndex = ((y and 1) shl 1) or (x and 1)
                    // 必须压 0：负偏置不能带进 3x3 矩阵。
                    // 矩阵的 G 行带两个负系数（-0.52 / -0.37），三通道不等时会把负噪声翻成正信号，
                    // 表现为暗场整体偏绿、满是绿点（实测过 G 7.2 vs R/B 1.7）。
                    val bl = if (useBlackNow) black[cfaIndex] else 0
                    norm[i] = ((v - bl - pedestal) * scale).coerceIn(0f, 1f)
                }
            }
        }

        val tNorm = System.currentTimeMillis()

        // RCD 第一段：补绿
        val cfaName = cfaNameOf(characteristics)
        val cfa = RcdDemosaic.Cfa.of(cfaName)
        val green = FloatArray(w * h)
        if (useRcdNow) {
            RcdDemosaic.interpolateGreen(norm, w, h, cfa, green)
        } else {
            Log.d(TAG, "去马赛克：双线性")
            bilinearGreen(norm, w, h, cfa, green)
        }

        val tGreen = System.currentTimeMillis()
        Log.d(TAG, "分段耗时：归一化 ${tNorm - tStart}ms，补绿 ${tGreen - tNorm}ms")

        // 第二段：上色 + 白平衡 + 矩阵 + 色调曲线
        val out = IntArray(w * h)
        val expR = gainR * exposure
        val expG = gainG * exposure
        val expB = gainB * exposure
        RawThreads.run(h) { _, y0, y1 ->
            processRows(y0, y1, norm, green, w, h, cfa, expR, expG, expB, matrix, out, useRcdNow)
        }

        if (DEBUG_STATS) {
            fun stat(name: String, arr: FloatArray) {
                var sum = 0.0
                var pos = 0
                var max = -1e9f
                val step = 37   // 质数步长，避免和 CFA 相位共振
                var n = 0
                var i = 0
                while (i < arr.size) {
                    val v = arr[i]
                    sum += v
                    if (v > 0f) pos++
                    if (v > max) max = v
                    n++
                    i += step
                }
                Log.d(TAG, "%s: 均值 %.5f  正值占比 %.1f%%  最大 %.5f".format(
                    name, sum / n, pos * 100.0 / n, max
                ))
            }
            stat("norm", norm)
            stat("green", green)

            var sr = 0.0; var sg = 0.0; var sb = 0.0; var n = 0
            var i = 0
            while (i < out.size) {
                val px = out[i]
                sr += (px shr 16) and 0xFF
                sg += (px shr 8) and 0xFF
                sb += px and 0xFF
                n++
                i += 37
            }
            Log.d(TAG, "out: R %.2f  G %.2f  B %.2f  (曝光 %.2f)".format(
                sr / n, sg / n, sb / n, exposure
            ))
        }

        HdrFinish.dither(out, w, h)
        Log.d(TAG, "分段耗时：上色 ${System.currentTimeMillis() - tGreen}ms")
        return makeBitmap(out, w, h, characteristics)
    }

    /** ARGB 数组 -> Bitmap（按传感器方向旋转）。两条路径共用。 */
    private fun makeBitmap(
        out: IntArray,
        w: Int,
        h: Int,
        characteristics: CameraCharacteristics
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        return if (orientation == 0) {
            bitmap
        } else {
            val matrixRotate = android.graphics.Matrix().apply { postRotate(orientation.toFloat()) }
            // **filter 必须为 false**：90/180/270 度是纯像素置换，不需要重采样。
            // 传 true 会走双线性重采样路径——实测 12MP 要 250ms 左右，而且会在
            // 每个像素上做一次插值，白白抹掉一点锐度（整数旋转本应像素级无损）。
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrixRotate, false)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        }
    }

    /**
     * 双线性补绿（关掉 RCD 时用）。
     * 简单到没有方向判断，所以边缘会出伪彩——这正是 RCD 要解决的问题，
     * 留它在这里是为了让"关掉 RCD"真的能看到差别。
     */
    private fun bilinearGreen(
        norm: FloatArray,
        w: Int,
        h: Int,
        cfa: RcdDemosaic.Cfa,
        green: FloatArray
    ) {
        val colors = cfa.colors()
        for (y in 0 until h) {
            val row = y * w
            val up = (if (y > 0) y - 1 else y) * w
            val dn = (if (y < h - 1) y + 1 else y) * w
            val pr = (y and 1) shl 1
            val xl = IntArray(w) { if (it > 0) it - 1 else 0 }
            val xr = IntArray(w) { if (it < w - 1) it + 1 else w - 1 }
            for (x in 0 until w) {
                val i = row + x
                if (colors[pr or (x and 1)] == RcdDemosaic.G) {
                    green[i] = norm[i]
                } else {
                    green[i] = (norm[row + xl[x]] + norm[row + xr[x]] + norm[up + x] + norm[dn + x]) * 0.25f
                }
            }
        }
    }

    /** 传感器 CFA 排布 -> "RGGB" 这类字符串 */
    private fun cfaNameOf(characteristics: CameraCharacteristics): String =
        when (characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            android.hardware.camera2.CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
            android.hardware.camera2.CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
            android.hardware.camera2.CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
            else -> "RGGB"
        }

    /**
     * 第二段：逐像素取 R/G/B（RCD 的红蓝插值），再做白平衡增益、色彩矩阵、色调曲线。
     *
     * 这里把 RcdDemosaic 的逻辑就地展开了一份"快路径"：
     * 逐像素调 safe 版本时，每次寻址都要做 4 次 coerceIn，12MP 下来会多花一倍的墙钟时间。
     * 边界索引在行内预先算好，热循环里只剩数组下标和算术。
     * 算法与 RcdDemosaic 的 safe 版完全一致（单测覆盖 safe 版，这里只做同构展开）。
     */
    private fun processRows(
        y0: Int,
        y1: Int,
        norm: FloatArray,
        green: FloatArray,
        w: Int,
        h: Int,
        cfa: RcdDemosaic.Cfa,
        gainR: Float,
        gainG: Float,
        gainB: Float,
        matrix: FloatArray,
        out: IntArray,
        useRcd: Boolean
    ) {
        val m0 = matrix[0]; val m1 = matrix[1]; val m2 = matrix[2]
        val m3 = matrix[3]; val m4 = matrix[4]; val m5 = matrix[5]
        val m6 = matrix[6]; val m7 = matrix[7]; val m8 = matrix[8]
        val colors = cfa.colors()

        val xl = IntArray(w) { if (it > 0) it - 1 else 0 }
        val xr = IntArray(w) { if (it < w - 1) it + 1 else w - 1 }

        for (y in y0 until y1) {
            val row = y * w
            val rowUp = (if (y > 0) y - 1 else y) * w
            val rowDn = (if (y < h - 1) y + 1 else y) * w
            val parityRow = (y and 1) shl 1

            for (x in 0 until w) {
                val i = row + x
                val color = colors[parityRow or (x and 1)]
                val gHere = green[i]

                val l = row + xl[x]
                val rIdx = row + xr[x]
                val u = rowUp + x
                val d = rowDn + x

                val r: Float
                val b: Float

                if (useRcd) {
                    // RCD：在色差域插值（同色 − 绿），避免把亮度变化当成颜色
                    if (color == RcdDemosaic.R) {
                        r = norm[i]
                        val d1 = norm[rowUp + xl[x]] - green[rowUp + xl[x]]
                        val d2 = norm[rowUp + xr[x]] - green[rowUp + xr[x]]
                        val d3 = norm[rowDn + xl[x]] - green[rowDn + xl[x]]
                        val d4 = norm[rowDn + xr[x]] - green[rowDn + xr[x]]
                        b = gHere + median4(d1, d2, d3, d4)
                    } else if (color == RcdDemosaic.B) {
                        b = norm[i]
                        val d1 = norm[rowUp + xl[x]] - green[rowUp + xl[x]]
                        val d2 = norm[rowUp + xr[x]] - green[rowUp + xr[x]]
                        val d3 = norm[rowDn + xl[x]] - green[rowDn + xl[x]]
                        val d4 = norm[rowDn + xr[x]] - green[rowDn + xr[x]]
                        r = gHere + median4(d1, d2, d3, d4)
                    } else {
                        val hColor = colors[parityRow or ((x + 1) and 1)]
                        if (hColor == RcdDemosaic.R) {
                            r = gHere + 0.5f * ((norm[l] - green[l]) + (norm[rIdx] - green[rIdx]))
                            b = gHere + 0.5f * ((norm[u] - green[u]) + (norm[d] - green[d]))
                        } else {
                            b = gHere + 0.5f * ((norm[l] - green[l]) + (norm[rIdx] - green[rIdx]))
                            r = gHere + 0.5f * ((norm[u] - green[u]) + (norm[d] - green[d]))
                        }
                    }
                } else {
                    // 纯双线性：直接对原始值取平均，不做色差修正 → 边缘会出伪彩
                    if (color == RcdDemosaic.R) {
                        r = norm[i]
                        b = (norm[rowUp + xl[x]] + norm[rowUp + xr[x]] +
                            norm[rowDn + xl[x]] + norm[rowDn + xr[x]]) * 0.25f
                    } else if (color == RcdDemosaic.B) {
                        b = norm[i]
                        r = (norm[rowUp + xl[x]] + norm[rowUp + xr[x]] +
                            norm[rowDn + xl[x]] + norm[rowDn + xr[x]]) * 0.25f
                    } else {
                        val hColor = colors[parityRow or ((x + 1) and 1)]
                        if (hColor == RcdDemosaic.R) {
                            r = (norm[l] + norm[rIdx]) * 0.5f
                            b = (norm[u] + norm[d]) * 0.5f
                        } else {
                            b = (norm[l] + norm[rIdx]) * 0.5f
                            r = (norm[u] + norm[d]) * 0.5f
                        }
                    }
                }

                val rG = r * gainR
                val gG = gHere * gainG
                val bG = b * gainB
                var rr = rG * m0 + gG * m1 + bG * m2
                var gg = rG * m3 + gG * m4 + bG * m5
                var bb = rG * m6 + gG * m7 + bG * m8

                // 高光软去饱和：在进色调曲线**之前**，按"离满量程还有多远"逐步收向中性。
                //
                // 为什么必须有这一步：白平衡给 R×1.81、B×1.68，G 只有 1.0。
                // 亮部往满量程爬的时候 G 先到顶，R/B 还在继续涨，三通道比例被破坏，
                // 颜色就滑向品红/粉紫（实测白键缝、屏幕高光的过渡边一圈紫）。
                // 只在削顶后才修是来不及的——那时比例已经错了，只能推出一个假的白色。
                // 正确做法是接近削顶就开始渐变中性，这也是一般相机的标准处理。
                val mx = if (rr > gg) (if (rr > bb) rr else bb) else (if (gg > bb) gg else bb)
                if (mx > 0.75f) {
                    val t = ((mx - 0.75f) / 0.85f).coerceIn(0f, 1f)
                    val k = 0.9f * t * t * (3f - 2f * t)
                    val yl = 0.299f * rr + 0.587f * gg + 0.114f * bb
                    rr += (yl - rr) * k
                    gg += (yl - gg) * k
                    bb += (yl - bb) * k
                }

                // 高光重建：任一通道打到白电平，就把这个像素按"无彩白"处理。
                //
                // 为什么必须做：白平衡增益是三通道各乘各的（这台机器 B 要 ×2.4），
                // 一旦某个通道先到顶，比例就被破坏——B 顶到 1.0、R/G 还在 0.3~0.7，
                // 矩阵一过就变成明亮的粉紫/蓝（实测键盘背光 R227/G153/B255、屏幕 R145/G194/B255）。
                // 溢出的那个通道本来就"有多少不知道了"，再按比例算出来的颜色是假的。
                // 真实相机的做法都是把这类像素恢复成中性白，宁可白也不要假颜色。
                if (useHlRecon &&
                    (r >= CLIP_LEVEL || gHere >= CLIP_LEVEL || b >= CLIP_LEVEL)
                ) {
                    val m = if (rr > gg) (if (rr > bb) rr else bb) else (if (gg > bb) gg else bb)
                    rr = m
                    gg = m
                    bb = m
                }

                var r8 = tone(rr)
                var g8 = tone(gg)
                var b8 = tone(bb)

                // 高光处收着点饱和度：亮部本来就该向白趋近（filmic 的常规做法），
                // 而且这里的 +6% 会直接把已经接近满值的通道推过 1，造成色相偏移
                val luma = 0.299f * r8 + 0.587f * g8 + 0.114f * b8
                val sat = 1f + (SATURATION - 1f) * (1f - luma).coerceIn(0f, 1f)
                r8 = luma + (r8 - luma) * sat
                g8 = luma + (g8 - luma) * sat
                b8 = luma + (b8 - luma) * sat

                // 暗部色度收敛。
                // 一开始按"暗部色度主要是噪声"设成 65%，实测是错的：拿同场景的 PS 版做
                // 分布对比，25% 分位 PS 的 R/G 是 2.146，我们完全不收敛才 1.716 —— PS 的
                // 暗部比我们更红。也就是说之前看到的"红"来自旧曲线把暗部整段抬亮 10 码，
                // 不是色度本身。收敛反而把暗部推向灰蓝。
                // 现在只保留很轻的一档（92%），专门压极暗处的彩色颗粒，不动颜色。
                val calm = (luma / 0.10f).coerceIn(0f, 1f)
                val keep = 0.92f + 0.08f * calm
                if (keep < 0.999f) {
                    r8 = luma + (r8 - luma) * keep
                    g8 = luma + (g8 - luma) * keep
                    b8 = luma + (b8 - luma) * keep
                }

                // 分段暗部色相修正。
                // 数据来自 PS 同场景实测（按 Lab 亮度分层比较 a/b 中位差）：
                //   L 12-24: Δa -0.5  Δb +3.8
                //   L 24-36: Δa -2.8  Δb +4.4
                //   L 36-48: Δa -1.3  Δb +0.9
                //   L 48+  : 不动
                // 注意需求随亮度非单调变化，所以不能用单一线性权重——之前那版在极暗处
                // 会改过头、中段又不够。这里用编码域 luma 近似 Lab L，四点线性插值。
                //
                // 为什么不用 3D LUT：单张照片只覆盖 RGB 空间的 38%，62% 的格子要插值猜，
                // 换场景（天空、绿植）会输出没校验过的颜色。LUT 需要 5-8 个不同场景才成立。
                //
                // 为什么改 Cb/Cr 而不是乘 RGB：在 gamma 编码域里 B 减 5.7% 只让 b 涨 0.3，
                // 要达到 +4.4 得砍掉 88% 的蓝，不可行。直接推色度通道、Y 保持不变才有效。
                val segL = floatArrayOf(0.10f, 0.20f, 0.30f, 0.45f)
                val segA = floatArrayOf(-0.5f, -2.8f, -1.3f, 0.0f)
                val segB = floatArrayOf(3.8f, 4.4f, 0.9f, 0.0f)
                var da = 0f
                var db = 0f
                val y0 = 0.299f * r8 + 0.587f * g8 + 0.114f * b8
                if (y0 < segL[segL.size - 1]) {
                    var i = 0
                    while (i < segL.size - 2 && y0 > segL[i + 1]) i++
                    val span = segL[i + 1] - segL[i]
                    val t = if (span <= 1e-4f) 0f else ((y0 - segL[i]) / span).coerceIn(0f, 1f)
                    da = segA[i] + (segA[i + 1] - segA[i]) * t
                    db = segB[i] + (segB[i + 1] - segB[i]) * t
                }
                if (da != 0f || db != 0f) {
                    var cb = (b8 - y0) * 0.564f
                    var cr = (r8 - y0) * 0.713f
                    cb -= (db / 255f)
                    cr += (da / 255f)
                    val nb = y0 + cb / 0.564f
                    val nr = y0 + cr / 0.713f
                    val ng = (y0 - 0.299f * nr - 0.114f * nb) / 0.587f
                    r8 = nr.coerceIn(0f, 1f)
                    g8 = ng.coerceIn(0f, 1f)
                    b8 = nb.coerceIn(0f, 1f)
                }

                out[i] = (0xFF shl 24) or
                    ((r8 * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((g8 * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    ((b8 * 255f + 0.5f).toInt().coerceIn(0, 255))
            }
        }
    }

    /** 4 个数取中位数（取中间两个的平均），比均值抗单个异常邻居 */
    private fun median4(d1: Float, d2: Float, d3: Float, d4: Float): Float {
        val a = if (d1 < d2) d1 else d2
        val b = if (d1 < d2) d2 else d1
        val c = if (d3 < d4) d3 else d4
        val e = if (d3 < d4) d4 else d3
        val loMid = if (a > c) a else c
        val hiMid = if (b < e) b else e
        return (loMid + hiMid) * 0.5f
    }

    private fun tone(linear: Float): Float {
        val lut = when {
            useShoulder -> TONE_LUT
            else -> TONE_LUT_HARD
        }
        if (linear <= 0f) return lut[0]
        if (linear >= LUT_MAX) return lut[LUT_SIZE - 1]
        // 线性插值（不是取最近点）：把"半级 ≈ 1 个码"的台阶降成 <0.01 码
        val pos = if (linear < 1f) {
            linear * (LUT_NORMAL - 1)
        } else {
            LUT_NORMAL + (linear - 1f) / (LUT_MAX - 1f) * (LUT_SIZE - LUT_NORMAL - 1)
        }
        val i0 = pos.toInt()
        if (i0 >= LUT_SIZE - 1) return lut[LUT_SIZE - 1]
        val f = pos - i0
        return lut[i0] + (lut[i0 + 1] - lut[i0]) * f
    }

    /** 取某个坐标的归一化值，注意它自己的 CFA 相位和当前位置不同 */
    private fun norm(
        raw: ShortArray, y: Int, x: Int, w: Int,
        black: IntArray, scale: Float,
        @Suppress("UNUSED_PARAMETER") curY: Int,
        @Suppress("UNUSED_PARAMETER") curX: Int
    ): Float {
        val v = raw[y * w + x].toInt() and 0xFFFF
        val cfaIndex = ((y and 1) shl 1) or (x and 1)
        return ((v - black[cfaIndex]) * scale).coerceIn(0f, 1f)
    }

    private fun readRaw16(image: Image): ShortArray? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.order(ByteOrder.nativeOrder())
            val w = image.width
            val h = image.height
            val rowStride = plane.rowStride
            val out = ShortArray(w * h)

            if (rowStride == w * 2 && plane.pixelStride == 2) {
                buffer.asShortBuffer().get(out, 0, w * h)
            } else {
                // 有行尾 padding，逐行搬
                val rowShorts = ShortArray(rowStride / 2)
                for (y in 0 until h) {
                    buffer.position(y * rowStride)
                    val slice = buffer.slice()
                    slice.order(ByteOrder.nativeOrder())
                    slice.asShortBuffer().get(rowShorts, 0, rowStride / 2)
                    System.arraycopy(rowShorts, 0, out, y * w, w)
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "读取 RAW 数据失败", e)
            null
        }
    }

    /** 用 1/64 的采样点估中位亮度，换算出一个温和的曝光系数 */
    private fun estimateExposure(
        raw: ShortArray, w: Int, h: Int,
        black: IntArray, scale: Float,
        gainR: Float, gainG: Float, gainB: Float,
        pedestal: Float
    ): Float {
        if (!AUTO_EXPOSURE) return 1f
        val step = 8
        val hist = IntArray(256)
        var count = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val v = raw[y * w + x].toInt() and 0xFFFF
                val cfa = ((y and 1) shl 1) or (x and 1)
                // 只用绿通道估亮度就够了（绿本来就占亮度的大头），不必先做去马赛克
                val n = ((v - black[cfa] - pedestal) * scale * gainG).coerceAtMost(1f)
                val luma = n
                hist[(luma * 255f).toInt().coerceIn(0, 255)]++
                count++
                x += step
            }
            y += step
        }
        if (count == 0) return 1f

        val half = count / 2
        var acc = 0
        var medianBin = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= half) {
                medianBin = i
                break
            }
        }
        val medianLinear = medianBin / 255f
        // 画面几乎是纯黑时不放大：放大纯噪声只会把噪声顶成可见的偏色底，
        // 而且此时"中位数"本身没有信息量（以前这里返回 MAX_EXPOSURE，等于 ×2.2 放大噪声）
        if (medianLinear <= 1e-3f) return 1f
        return (TARGET_MEDIAN / medianLinear).coerceIn(MIN_EXPOSURE, MAX_EXPOSURE)
    }

    /** 相机当次实际应用的色彩矩阵 */
    /** 把 3x3 每一行缩放到和=1，保证白还是白。偏离小于 2% 就不动 */
    private fun normalizeRowSums(m: FloatArray): FloatArray {
        val out = m.copyOf()
        val sums = floatArrayOf(
            out[0] + out[1] + out[2],
            out[3] + out[4] + out[5],
            out[6] + out[7] + out[8]
        )
        var changed = false
        for (r in 0 until 3) {
            val sum = sums[r]
            if (sum <= 1e-3f) continue
            if (kotlin.math.abs(sum - 1f) <= 0.02f) continue
            for (c in 0 until 3) out[r * 3 + c] /= sum
            changed = true
        }
        if (changed) {
            Log.d(
                TAG,
                "标定矩阵行和 %.3f/%.3f/%.3f → 已归一".format(sums[0], sums[1], sums[2])
            )
        }
        return out
    }

    private fun readAppliedTransform(result: TotalCaptureResult?): FloatArray? {
        val t = result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: return null
        val out = FloatArray(9)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val r = t.getElement(col, row)
                out[row * 3 + col] = r.numerator.toFloat() / r.denominator.toFloat()
            }
        }
        return out
    }

    /** forwardMatrix(XYZ->sRGB) * colorTransform(sensor->XYZ) */
    private fun buildColorMatrix(ch: CameraCharacteristics): FloatArray {
        val ct: ColorSpaceTransform? = ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        val fm: ColorSpaceTransform? = ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)

        if (ct == null && fm == null) {
            // 拿不到就用单位矩阵，至少不会把颜色搞乱
            return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }

        val a = toRowMajor(ct)
        val b = toRowMajor(fm)

        // m = b * a
        val m = FloatArray(9)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) {
                    sum += b[row * 3 + k] * a[k * 3 + col]
                }
                m[row * 3 + col] = sum
            }
        }
        return m
    }

    /** ColorSpaceTransform 是按列存的，转成行优先；null 则返回单位矩阵 */
    private fun toRowMajor(t: ColorSpaceTransform?): FloatArray {
        val out = FloatArray(9)
        if (t == null) {
            out[0] = 1f
            out[4] = 1f
            out[8] = 1f
            return out
        }
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val r = t.getElement(col, row)
                out[col * 3 + row] = r.numerator.toFloat() / r.denominator.toFloat()
            }
        }
        return out
    }
}
