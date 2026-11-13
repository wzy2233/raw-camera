package com.camera.app

import java.util.concurrent.Future

/**
 * RCD 去马赛克（Ratio Corrected Demosaicing 思路）。
 *
 * 双线性的问题：它把上下左右四个同色邻居一平均，完全不看图像结构。
 * 结果是在边缘和细节处把不同颜色的光混在一起——于是出现"伪彩"（本来没有的紫边绿边）
 * 和"拉链纹"（边缘锯齿）。这是 RAW 出片里最假的部分。
 *
 * 这里的做法分两段：
 *
 * 1) 绿通道先重建（绿占亮度 60% 以上，先把它做对，红蓝才有依据）
 *    - 在红/蓝位置上，用上下左右四个已测的绿，加上同色 ±2 处的修正项（Hamilton-Adams 型）
 *    - 方向自适应：分别算水平/垂直两个方向的能量，按能量加权平均
 *      （不硬选一边——硬选在方向判错时会出现突兀的色块）
 *    - 限幅：插值不该超出邻域的合理范围，专门压掉过冲造成的亮暗点
 * 2) 红蓝用"色差"插值
 *    - 算 R−G、B−G 再插值，比直接插 R、B 稳定得多（去掉了亮度的影响）
 *    - 绿位置上红蓝在左右（或上下）；相反色位置上红蓝在对角四个
 *
 * 只依赖已测像素和第一段的绿平面，所以可以按行并行。
 */
object RcdDemosaic {

    const val R = 0
    const val G = 1
    const val B = 2

    /** 4 个 Bayer 位置各自是什么颜色 */
    class Cfa private constructor(private val colors: IntArray) {

        fun colorAt(x: Int, y: Int): Int = colors[((y and 1) shl 1) or (x and 1)]

        /** 供热循环直接下标访问（避免逐像素函数调用） */
        fun colors(): IntArray = colors

        companion object {
            fun of(name: String): Cfa {
                val colors = IntArray(4)
                for (i in 0 until 4) {
                    colors[i] = when (name.getOrNull(i) ?: 'G') {
                        'R' -> R
                        'B' -> B
                        else -> G
                    }
                }
                return Cfa(colors)
            }
        }
    }

    /**
     * 第一段：补出完整绿平面，已测的绿位置原样保留。
     *
     * @param norm 归一化到 0..1 的 Bayer 数据（已减黑电平、已做暗角补偿）
     * @param green 输出，长度 w*h
     */
    fun interpolateGreen(norm: FloatArray, w: Int, h: Int, cfa: Cfa, green: FloatArray) {
        val lanes = 6
        val laneH = (h + lanes - 1) / lanes
        val futures = ArrayList<Future<*>?>(lanes)
        for (lane in 0 until lanes) {
            val y0 = lane * laneH
            val y1 = kotlin.math.min(h, y0 + laneH)
            if (y0 >= y1) break
            futures.add(pool.submit(Runnable { greenRows(norm, w, h, cfa, green, y0, y1) }))
        }
        for (f in futures) f?.get()
    }

    private fun greenRows(
        norm: FloatArray,
        w: Int,
        h: Int,
        cfa: Cfa,
        green: FloatArray,
        y0: Int,
        y1: Int
    ) {
        val colors = cfa.colors()
        // 索引先算好，热循环里不再做边界夹取
        val xl1 = IntArray(w) { if (it > 0) it - 1 else 0 }
        val xr1 = IntArray(w) { if (it < w - 1) it + 1 else w - 1 }
        val xl2 = IntArray(w) { if (it > 1) it - 2 else 0 }
        val xr2 = IntArray(w) { if (it < w - 2) it + 2 else w - 1 }

        val eps = 1e-5f

        for (y in y0 until y1) {
            val row = y * w
            val rowUp1 = (if (y > 0) y - 1 else y) * w
            val rowDn1 = (if (y < h - 1) y + 1 else y) * w
            val rowUp2 = (if (y > 1) y - 2 else y) * w
            val rowDn2 = (if (y < h - 2) y + 2 else y) * w
            val parityRow = (y and 1) shl 1

            for (x in 0 until w) {
                val i = row + x
                if (colors[parityRow or (x and 1)] == G) {
                    green[i] = norm[i]
                    continue
                }

                val c = norm[i]
                val gl = norm[row + xl1[x]]
                val gr = norm[row + xr1[x]]
                val gu = norm[rowUp1 + x]
                val gd = norm[rowDn1 + x]
                val cl = norm[row + xl2[x]]
                val cr = norm[row + xr2[x]]
                val cu = norm[rowUp2 + x]
                val cd = norm[rowDn2 + x]

                // 方向能量：同色差 + 绿差
                val dh = kotlin.math.abs(cl - cr) + kotlin.math.abs(gl - gr)
                val dv = kotlin.math.abs(cu - cd) + kotlin.math.abs(gu - gd)

                // 两个方向的估计（同色修正项抵消颜色本身的影响）
                val gh = 0.5f * (gl + gr) + 0.25f * (2f * c - cl - cr)
                val gv = 0.5f * (gu + gd) + 0.25f * (2f * c - cu - cd)

                // 逆方差加权：方向越平坦权重越大
                val whRaw = 1f / (dh * dh + eps)
                val wvRaw = 1f / (dv * dv + eps)
                val wh = whRaw / (whRaw + wvRaw)
                var v = wh * gh + (1f - wh) * gv

                // 限幅，压制过冲
                val localMax = kotlin.math.max(kotlin.math.max(gl, gr), kotlin.math.max(gu, gd))
                val localMin = kotlin.math.min(kotlin.math.min(gl, gr), kotlin.math.min(gu, gd))
                // 光强不能为负，下界压 0
                val hi = localMax * 1.18f + 0.01f
                val lo = kotlin.math.max(0f, localMin * 0.82f - 0.01f)
                if (v > hi) v = hi
                if (v < lo) v = lo

                green[i] = v
            }
        }
    }

    private fun greenAt(green: FloatArray, x: Int, y: Int, w: Int, h: Int): Float =
        green[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

    private fun rawAt(norm: FloatArray, x: Int, y: Int, w: Int, h: Int): Float =
        norm[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

    /** 给定像素的红通道：已测位置直取，其余用"红-绿色差"从邻居推 */
    fun redAt(norm: FloatArray, green: FloatArray, w: Int, h: Int, cfa: Cfa, x: Int, y: Int): Float {
        val color = cfa.colorAt(x, y)
        if (color == R) return norm[y * w + x]

        val gHere = greenAt(green, x, y, w, h)

        if (color == G) {
            return if (cfa.colorAt(x - 1, y) == R || cfa.colorAt(x + 1, y) == R) {
                val dL = rawAt(norm, x - 1, y, w, h) - greenAt(green, x - 1, y, w, h)
                val dR = rawAt(norm, x + 1, y, w, h) - greenAt(green, x + 1, y, w, h)
                gHere + 0.5f * (dL + dR)
            } else {
                val dU = rawAt(norm, x, y - 1, w, h) - greenAt(green, x, y - 1, w, h)
                val dD = rawAt(norm, x, y + 1, w, h) - greenAt(green, x, y + 1, w, h)
                gHere + 0.5f * (dU + dD)
            }
        }

        // 对角四个取"中位数"而不是平均：亮斑/细节处只要有一个对角跨过边缘，
        // 平均值就会被带偏，中位数不受影响
        val d1 = rawAt(norm, x - 1, y - 1, w, h) - greenAt(green, x - 1, y - 1, w, h)
        val d2 = rawAt(norm, x + 1, y - 1, w, h) - greenAt(green, x + 1, y - 1, w, h)
        val d3 = rawAt(norm, x - 1, y + 1, w, h) - greenAt(green, x - 1, y + 1, w, h)
        val d4 = rawAt(norm, x + 1, y + 1, w, h) - greenAt(green, x + 1, y + 1, w, h)
        val lo = kotlin.math.min(kotlin.math.min(d1, d2), kotlin.math.min(d3, d4))
        val hi = kotlin.math.max(kotlin.math.max(d1, d2), kotlin.math.max(d3, d4))
        val mid = (kotlin.math.min(kotlin.math.max(d1, d2), kotlin.math.max(d3, d4)) +
            kotlin.math.max(kotlin.math.min(d1, d2), kotlin.math.min(d3, d4))) * 0.5f
        val med = mid.coerceIn(lo, hi)
        return gHere + med
    }

    /** 给定像素的蓝通道，逻辑与红对称 */
    fun blueAt(norm: FloatArray, green: FloatArray, w: Int, h: Int, cfa: Cfa, x: Int, y: Int): Float {
        val color = cfa.colorAt(x, y)
        if (color == B) return norm[y * w + x]

        val gHere = greenAt(green, x, y, w, h)

        if (color == G) {
            return if (cfa.colorAt(x - 1, y) == B || cfa.colorAt(x + 1, y) == B) {
                val dL = rawAt(norm, x - 1, y, w, h) - greenAt(green, x - 1, y, w, h)
                val dR = rawAt(norm, x + 1, y, w, h) - greenAt(green, x + 1, y, w, h)
                gHere + 0.5f * (dL + dR)
            } else {
                val dU = rawAt(norm, x, y - 1, w, h) - greenAt(green, x, y - 1, w, h)
                val dD = rawAt(norm, x, y + 1, w, h) - greenAt(green, x, y + 1, w, h)
                gHere + 0.5f * (dU + dD)
            }
        }

        // 对角四个取"中位数"而不是平均：亮斑/细节处只要有一个对角跨过边缘，
        // 平均值就会被带偏，中位数不受影响
        val d1 = rawAt(norm, x - 1, y - 1, w, h) - greenAt(green, x - 1, y - 1, w, h)
        val d2 = rawAt(norm, x + 1, y - 1, w, h) - greenAt(green, x + 1, y - 1, w, h)
        val d3 = rawAt(norm, x - 1, y + 1, w, h) - greenAt(green, x - 1, y + 1, w, h)
        val d4 = rawAt(norm, x + 1, y + 1, w, h) - greenAt(green, x + 1, y + 1, w, h)
        val lo = kotlin.math.min(kotlin.math.min(d1, d2), kotlin.math.min(d3, d4))
        val hi = kotlin.math.max(kotlin.math.max(d1, d2), kotlin.math.max(d3, d4))
        val mid = (kotlin.math.min(kotlin.math.max(d1, d2), kotlin.math.max(d3, d4)) +
            kotlin.math.max(kotlin.math.min(d1, d2), kotlin.math.min(d3, d4))) * 0.5f
        val med = mid.coerceIn(lo, hi)
        return gHere + med
    }

    private val pool = java.util.concurrent.Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    ) { r -> Thread(r, "rcd").apply { isDaemon = true } }
}
