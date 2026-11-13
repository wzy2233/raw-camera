package com.camera.app

import android.content.Context
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 色卡标定：从 24 色卡解出 3x3 色彩校正矩阵（CCM）。
 *
 * 为什么需要这一步：
 *   手机 ISP 出的 JPEG 用的是厂商自己的色彩矩阵，目的是"讨喜"（饱和度偏高、肤色偏红），
 *   不是"准"。想让颜色准，只能自己拿已知颜色去反解：拍到什么 vs 应该是什么，中间那个
 *   3x3 线性变换就是这台机器（这颗镜头 + 这个 ISP）的真实偏差。
 *
 * 数学上是最小二乘：
 *   X = 24 个色块的实测值（线性光、3 x N）
 *   Y = 24 个色块的参考值（线性光、3 x N）
 *   求 M（3x3）使 ‖M·X − Y‖ 最小：M = Y·Xᵀ·(X·Xᵀ)⁻¹
 *
 * 必须在线性光里解、在线性光里用。伽马空间里做矩阵乘法是错的（会把中间调压坏）。
 */
object ColorChecker {

    /**
     * ColorChecker Classic 24 色的标准 sRGB 参考值（BabelColor 公开数据）。
     * 顺序：4 行 x 6 列，从左到右、从上到下。
     */
    val PATCHES_SRGB: Array<IntArray> = arrayOf(
        intArrayOf(115, 82, 68),      // 1  深肤色
        intArrayOf(194, 150, 130),    // 2  浅肤色
        intArrayOf(98, 122, 157),     // 3  蓝天
        intArrayOf(87, 108, 67),      // 4  树叶
        intArrayOf(133, 128, 177),    // 5  蓝花
        intArrayOf(103, 189, 170),    // 6  蓝绿

        intArrayOf(214, 126, 44),     // 7  橙
        intArrayOf(80, 91, 166),      // 8  紫蓝
        intArrayOf(193, 90, 99),      // 9  中等红
        intArrayOf(94, 60, 108),      // 10 紫
        intArrayOf(157, 188, 64),     // 11 黄绿
        intArrayOf(224, 163, 46),     // 12 橙黄

        intArrayOf(56, 61, 150),      // 13 蓝
        intArrayOf(70, 148, 73),      // 14 绿
        intArrayOf(175, 54, 60),      // 15 红
        intArrayOf(231, 199, 31),     // 16 黄
        intArrayOf(187, 86, 149),     // 17 品红
        intArrayOf(8, 133, 161),      // 18 青

        intArrayOf(243, 243, 242),    // 19 白 9.5
        intArrayOf(200, 200, 200),    // 20 中性 8
        intArrayOf(160, 160, 160),    // 21 中性 6.5
        intArrayOf(122, 122, 121),    // 22 中性 5
        intArrayOf(85, 85, 85),       // 23 中性 3.5
        intArrayOf(52, 52, 52)        // 24 黑 2
    )

    val PATCH_NAMES: Array<String> = arrayOf(
        "深肤色", "浅肤色", "蓝天", "树叶", "蓝花", "蓝绿",
        "橙", "紫蓝", "中等红", "紫", "黄绿", "橙黄",
        "蓝", "绿", "红", "黄", "品红", "青",
        "白", "中性8", "中性6.5", "中性5", "中性3.5", "黑"
    )

    const val COLS = 6
    const val ROWS = 4
    const val COUNT = 24

    /** 解算结果，含前后误差，用来判断这次标定到底有没有效果 */
    class Result(
        /** 3x3，行优先，作用在线性光上 */
        val matrix: FloatArray,
        val meanBefore: Float,
        val meanAfter: Float,
        val maxBefore: Float,
        val maxAfter: Float,
        val perPatchBefore: FloatArray,
        val perPatchAfter: FloatArray,
        /** 解矩阵前用于对齐曝光的系数（1 表示曝光正好） */
        val exposure: Float = 1f
    ) {
        /** 三行之和应该接近相等：说明矩阵没有在整体提亮/压暗，只改了颜色 */
        fun rowSums(): FloatArray = floatArrayOf(
            matrix[0] + matrix[1] + matrix[2],
            matrix[3] + matrix[4] + matrix[5],
            matrix[6] + matrix[7] + matrix[8]
        )

        fun summary(): String =
            "平均 ΔE76：%.1f → %.1f    最差 %.1f → %.1f".format(
                meanBefore, meanAfter, maxBefore, maxAfter
            )
    }

    /** 灰阶从第 18 块开始（白、中性8、6.5、5、3.5、黑） */
    const val GRAY_START = 18

    /**
     * @param measured 24 组实测值，已经过白平衡校准，范围 0..255（sRGB 编码值）
     *
     * 曝光对齐（重要）：最小二乘会顺便把"整体偏暗"也当成色彩误差来修，
     * 于是解出来的矩阵行和变成 1.13 / 1.64 / 1.29 —— 矩阵在偷偷提亮，
     * 拍出来每张照片都比原来亮一档，颜色反而更不准（实测遇到过）。
     * 做法：用最后一排 6 个灰阶把实测的平均亮度对齐到参考值，再解矩阵。
     * 这样矩阵只负责颜色，亮度仍然由 AE 决定，跟白平衡校准的"亮度守恒"一个原则。
     */
    fun solve(measured: Array<FloatArray>): Result? {
        if (measured.size != COUNT) return null
        return solveLinear(Array(COUNT) { linearize(measured[it]) })
    }

    /**
     * 直接吃线性光输入（0..1）。RAW 标定走这条——RAW 采样本来就是线性的，
     * 再编码成 sRGB 再解码纯属来回折腾，还会把暗部量化两次。
     */
    fun solveLinear(
        xs: Array<FloatArray>,
        /** 矩阵元素的绝对值上限。JPEG 的纠偏矩阵很小（1.8 够），RAW→sRGB 可能大到 3~4 */
        maxElement: Float = 1.8f,
        /** 对角元素下限，防止某个通道被压死 */
        minDiagonal: Float = 0.35f
    ): Result? {
        if (xs.size != COUNT) return null

        // Y·Xᵀ 和 X·Xᵀ
        val xtx = Array(3) { FloatArray(3) }
        val yxt = Array(3) { FloatArray(3) }

        val ys = Array(COUNT) { linearizeFloats(PATCHES_SRGB[it]) }

        // 灰阶亮度比 -> 把参考值缩放到"和实拍一样的亮度"再比对。
        // 方向不能反：目标是"矩阵只改颜色、不改亮度"，所以要让参考跟着实拍走。
        // 反过来（把实拍拉到参考亮度）会让矩阵承担曝光，行和变成 1.1~2.4，
        // 等于每张照片都被偷偷提亮（实测踩过这个坑）。
        var yMeas = 0f
        var yRef = 0f
        for (i in GRAY_START until COUNT) {
            yMeas += lumaOf(xs[i])
            yRef += lumaOf(ys[i])
        }
        val exposure = if (yRef > 1e-4f) (yMeas / yRef).coerceIn(0.25f, 4f) else 1f

        val target = Array(COUNT) { i -> FloatArray(3) { c -> ys[i][c] * exposure } }

        for (i in 0 until COUNT) {
            val x = xs[i]
            val y = target[i]
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    xtx[r][c] += x[r] * x[c]
                    yxt[r][c] += y[r] * x[c]
                }
            }
        }

        // 加一点对角正则，避免某些通道几乎全黑时矩阵病态
        val trace = (xtx[0][0] + xtx[1][1] + xtx[2][2]) / 3f
        for (i in 0 until 3) xtx[i][i] += trace * 1e-4f

        val inv = invert3(xtx) ?: return null
        val m = mul3(yxt, inv)

        // 合理性检查：偏离太多就别用了，宁可告诉用户这次没校好
        for (i in 0 until 9) {
            if (!m[i].isFinite() || abs(m[i]) > maxElement) return null
        }
        if (m[0] < minDiagonal || m[4] < minDiagonal || m[8] < minDiagonal) return null

        val before = FloatArray(COUNT)
        val after = FloatArray(COUNT)
        for (i in 0 until COUNT) {
            val refLab = labOf(target[i])
            before[i] = deltaE76(labOf(xs[i]), refLab)
            val corrected = FloatArray(3) { r ->
                (m[r * 3] * xs[i][0] + m[r * 3 + 1] * xs[i][1] + m[r * 3 + 2] * xs[i][2])
                    .coerceIn(0f, 1f)
            }
            after[i] = deltaE76(labOf(corrected), refLab)
        }

        return Result(
            matrix = m,
            meanBefore = before.average().toFloat(),
            meanAfter = after.average().toFloat(),
            maxBefore = before.maxOrNull() ?: 0f,
            maxAfter = after.maxOrNull() ?: 0f,
            perPatchBefore = before,
            perPatchAfter = after,
            exposure = exposure
        )
    }

    /** 线性光里的亮度 */
    private fun lumaOf(linear: FloatArray): Float =
        0.2126f * linear[0] + 0.7152f * linear[1] + 0.0722f * linear[2]

    /** 把 3x3 矩阵作用在一组 0..255 的 sRGB 值上（用于逐块报告） */
    fun applyToSrgb(m: FloatArray, rgb: FloatArray): FloatArray {
        val lin = linearize(rgb)
        val out = FloatArray(3) { r ->
            m[r * 3] * lin[0] + m[r * 3 + 1] * lin[1] + m[r * 3 + 2] * lin[2]
        }
        return FloatArray(3) { encode(out[it].coerceIn(0f, 1f)) * 255f }
    }

    /**
     * 不知道色卡哪边朝上时，把 4 种旋转 × 镜像都解一遍，取残差最小的。
     *
     * 判断"朝向"很麻烦（照片可能是横的、竖的、倒的，检测出来的网格也可能已经转置），
     * 而解一次只要几十毫秒——用结果说话比用几何猜更可靠。
     */
    fun solveBestOrientation(colors: Array<FloatArray>, rows: Int, cols: Int): Result? {
        var best: Result? = null
        for (seq in orientationVariants(colors, rows, cols)) {
            val r = solve(seq) ?: continue
            if (best == null || r.meanAfter < best.meanAfter) best = r
        }
        return best
    }

    /** 线性光输入的朝向解算（RAW 标定用） */
    fun solveBestOrientationLinear(
        linearColors: Array<FloatArray>,
        rows: Int,
        cols: Int,
        maxElement: Float = 1.8f,
        minDiagonal: Float = 0.35f
    ): Result? {
        var best: Result? = null
        for (seq in orientationVariants(linearColors, rows, cols)) {
            val r = solveLinear(seq, maxElement, minDiagonal) ?: continue
            if (best == null || r.meanAfter < best.meanAfter) best = r
        }
        return best
    }

    internal fun orientationVariants(
        colors: Array<FloatArray>,
        rows: Int,
        cols: Int
    ): List<Array<FloatArray>> {
        if (rows * cols != colors.size) return emptyList()

        var grid = Array(rows) { r -> Array(cols) { c -> colors[r * cols + c] } }
        var h = rows
        var w = cols
        val out = ArrayList<Array<FloatArray>>(8)

        repeat(4) {
            out.add(readRowMajor(grid, h, w))
            val mirrored = Array(h) { r -> Array(w) { c -> grid[r][w - 1 - c] } }
            out.add(readRowMajor(mirrored, h, w))

            // 顺时针转 90°：维度互换，new[i][j] = old[h-1-j][i]
            val old = grid
            val oldH = h
            grid = Array(w) { i -> Array(oldH) { j -> old[oldH - 1 - j][i] } }
            val t = h
            h = w
            w = t
        }
        return out
    }

    private fun readRowMajor(grid: Array<Array<FloatArray>>, rows: Int, cols: Int): Array<FloatArray> =
        Array(rows * cols) { grid[it / cols][it % cols] }

    // ---------------- 色彩空间 ----------------

    private fun linearize(rgb: FloatArray): FloatArray =
        FloatArray(3) { linearizeOne(rgb[it] / 255f) }

    private fun linearizeFloats(rgb: IntArray): FloatArray =
        FloatArray(3) { linearizeOne(rgb[it] / 255f) }

    fun linearizeOne(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    fun encode(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }

    /** 线性 sRGB -> CIE Lab（D65） */
    fun labOf(linear: FloatArray): FloatArray {
        val x = 0.4124f * linear[0] + 0.3576f * linear[1] + 0.1805f * linear[2]
        val y = 0.2126f * linear[0] + 0.7152f * linear[1] + 0.0722f * linear[2]
        val z = 0.0193f * linear[0] + 0.1192f * linear[1] + 0.9505f * linear[2]

        val fx = f(x / 0.95047f)
        val fy = f(y / 1.0f)
        val fz = f(z / 1.08883f)

        return floatArrayOf(
            116f * fy - 16f,
            500f * (fx - fy),
            200f * (fy - fz)
        )
    }

    private fun f(t: Float): Float {
        val v = t.coerceAtLeast(0f)
        return if (v > 0.008856f) cbrt(v.toDouble()).toFloat() else 7.787f * v + 16f / 116f
    }

    fun deltaE76(a: FloatArray, b: FloatArray): Float {
        val dl = a[0] - b[0]
        val da = a[1] - b[1]
        val db = a[2] - b[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    // ---------------- 3x3 线性代数 ----------------

    fun invert3(m: Array<FloatArray>): Array<FloatArray>? {
        val a = m[0][0]; val b = m[0][1]; val c = m[0][2]
        val d = m[1][0]; val e = m[1][1]; val f = m[1][2]
        val g = m[2][0]; val h = m[2][1]; val i = m[2][2]

        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-9f) return null
        val inv = 1f / det

        return arrayOf(
            floatArrayOf((e * i - f * h) * inv, (c * h - b * i) * inv, (b * f - c * e) * inv),
            floatArrayOf((f * g - d * i) * inv, (a * i - c * g) * inv, (c * d - a * f) * inv),
            floatArrayOf((d * h - e * g) * inv, (b * g - a * h) * inv, (a * e - b * d) * inv)
        )
    }

    /** 3x3 相乘，返回行优先的 9 个浮点 */
    private fun mul3(a: Array<FloatArray>, b: Array<FloatArray>): FloatArray {
        val out = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) sum += a[r][k] * b[k][c]
                out[r * 3 + c] = sum
            }
        }
        return out
    }
}

/** CCM 的持久化（只存一行 9 个数 + 标定时间） */
object CcmStore {

    private const val PREF = "color_ccm"
    private const val KEY_MATRIX = "matrix"
    private const val KEY_TIME = "time"
    private const val KEY_DELTA = "delta"

    class Profile(val matrix: FloatArray, val timestamp: Long, val meanDeltaE: Float)

    fun load(context: Context): Profile? = loadFrom(context, PREF)

    fun save(context: Context, matrix: FloatArray, meanDeltaE: Float) =
        saveTo(context, PREF, matrix, meanDeltaE)

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ---- 内部：RAW 矩阵用同一套读写，只是换一个 pref 文件 ----

    internal fun loadFrom(context: Context, pref: String): Profile? {
        val prefs = context.getSharedPreferences(pref, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_MATRIX, null) ?: return null
        val parts = raw.split(",").mapNotNull { it.toFloatOrNull() }
        if (parts.size != 9) return null
        return Profile(
            parts.toFloatArray(),
            prefs.getLong(KEY_TIME, 0L),
            prefs.getFloat(KEY_DELTA, 0f)
        )
    }

    internal fun saveTo(context: Context, pref: String, matrix: FloatArray, meanDeltaE: Float) {
        context.getSharedPreferences(pref, Context.MODE_PRIVATE).edit()
            .putString(KEY_MATRIX, matrix.joinToString(","))
            .putLong(KEY_TIME, System.currentTimeMillis())
            .putFloat(KEY_DELTA, meanDeltaE)
            .apply()
    }
}

/**
 * RAW 管线的颜色矩阵（sensor 线性 → sRGB 线性），和 JPEG 那份完全分开存。
 *
 * 为什么不共用一份：两者输入空间不同。
 *  - JPEG 那份是"ISP 输出 → 参考值"的纠偏，只能作用在 ISP 处理过的图上
 *  - RAW 这份是这颗传感器本身的特性，作用在去马赛克之后的线性光上
 * 混用会把颜色彻底搞坏，所以宁可两份。
 */
object RawCcmStore {

    private const val PREF = "raw_ccm"

    fun load(context: Context): CcmStore.Profile? = CcmStore.loadFrom(context, PREF)

    fun save(context: Context, matrix: FloatArray, meanDeltaE: Float) =
        CcmStore.saveTo(context, PREF, matrix, meanDeltaE)

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
