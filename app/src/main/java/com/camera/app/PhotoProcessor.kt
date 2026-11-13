package com.camera.app

import android.graphics.Bitmap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 成片后处理：两点色彩校准 + 暗部中性化 + 内置滤镜，合成一次像素遍历。
 *
 * 原来这几步各自 getPixels/setPixels 一次，12MP 的照片光是把 48MB 的像素数组
 * 搬进搬出就要好几次，加上各自的直方图和逐像素循环，一张要一秒上下。
 * 这里做两件事：
 *   1) 只读一次、只写一次，中间不分配第二个 48MB 数组
 *   2) 三段遍历都按行切给线程池，几核就跑几份（像素数组各段互不重叠，不需要加锁）
 *
 * 公式与 PreviewPipeline 里的 AGSL 着色器逐项对应，所以取景和成片能对上。
 */
object PhotoProcessor {

    /** 超过这个亮度就算画面里没有真正的暗部 */
    private const val MAX_DARK_LUMA = 60f

    /** 暗部权重衰减到 0 的亮度 */
    private const val KNEE = 72f

    /** 参与统计的暗部比例 */
    private const val DARK_PERCENTILE = 0.01f

    private val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)

    private val pool = Executors.newFixedThreadPool(cores) { r ->
        Thread(r, "photo-proc").apply { isDaemon = true }
    }

    /** sRGB 8bit -> 线性光的查表（CCM 必须在线性光里做） */
    private val TO_LINEAR = FloatArray(256) { ColorChecker.linearizeOne(it / 255f) }

    /** 线性光 -> sRGB 8bit 的查表，1024 级足够 */
    private val TO_SRGB = IntArray(1024) {
        (ColorChecker.encode(it / 1023f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * 原地处理。
     *
     * @param calibration 两点校准，null 表示不校正
     * @param matrix 可选的内置滤镜 3x3 矩阵（作用在 sRGB 编码值上），null 表示不套
     * @param ccm 色卡解出的 3x3 色彩校正矩阵（作用在线性光上），null 表示不套
     * @return true 表示像素真的被改过；false 时调用方可以完全跳过重新编码
     */
    fun applyInPlace(
        bitmap: Bitmap,
        calibration: ColorCalibration?,
        matrix: FloatArray? = null,
        ccm: FloatArray? = null
    ): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false

        val luts = calibration?.takeIf { !it.isIdentity }?.buildChannelLuts()
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val lanes = laneCount(h)
        var modified = false
        val laneLuma = Array(lanes) { IntArray(256) }
        var histDone = false

        // 1) 白平衡校准（顺带建亮度直方图；如果后面还要过 CCM，直方图留到那一步再建）
        if (luts != null) {
            val lr = luts[0]
            val lg = luts[1]
            val lb = luts[2]
            runLanes(lanes, h) { lane, y0, y1 ->
                val hist = laneLuma[lane]
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in 0 until w) {
                        val i = row + x
                        val p = pixels[i]
                        val r = lr[(p shr 16) and 0xFF]
                        val g = lg[(p shr 8) and 0xFF]
                        val b = lb[p and 0xFF]
                        pixels[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                        if (ccm == null) hist[(r * 77 + g * 150 + b * 29) shr 8]++
                    }
                }
            }
            modified = true
            histDone = ccm == null
        }

        // 2) 色彩校正矩阵：解码到线性 -> 乘 3x3 -> 编码回来
        if (ccm != null) {
            val c0 = ccm[0]; val c1 = ccm[1]; val c2 = ccm[2]
            val c3 = ccm[3]; val c4 = ccm[4]; val c5 = ccm[5]
            val c6 = ccm[6]; val c7 = ccm[7]; val c8 = ccm[8]
            runLanes(lanes, h) { lane, y0, y1 ->
                val hist = laneLuma[lane]
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in 0 until w) {
                        val i = row + x
                        val p = pixels[i]
                        val lr = TO_LINEAR[(p shr 16) and 0xFF]
                        val lg = TO_LINEAR[(p shr 8) and 0xFF]
                        val lb = TO_LINEAR[p and 0xFF]

                        val nr = (c0 * lr + c1 * lg + c2 * lb).coerceIn(0f, 1f)
                        val ng = (c3 * lr + c4 * lg + c5 * lb).coerceIn(0f, 1f)
                        val nb = (c6 * lr + c7 * lg + c8 * lb).coerceIn(0f, 1f)

                        val r8 = TO_SRGB[(nr * 1023f).toInt()]
                        val g8 = TO_SRGB[(ng * 1023f).toInt()]
                        val b8 = TO_SRGB[(nb * 1023f).toInt()]
                        pixels[i] = (p and 0xFF000000.toInt()) or (r8 shl 16) or (g8 shl 8) or b8
                        hist[(r8 * 77 + g8 * 150 + b8 * 29) shr 8]++
                    }
                }
            }
            modified = true
            histDone = true
        }

        // 3) 没做过上面任何一步，直方图还得单独算
        if (!histDone) {
            runLanes(lanes, h) { lane, y0, y1 ->
                val hist = laneLuma[lane]
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in 0 until w) {
                        val p = pixels[row + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        hist[(r * 77 + g * 150 + b * 29) shr 8]++
                    }
                }
            }
        }

        val lumaHist = IntArray(256)
        for (lane in 0 until lanes) {
            val src = laneLuma[lane]
            for (v in 0..255) lumaHist[v] += src[v]
        }

        // 2) 1% 分位作为暗部门限
        val target = (pixels.size * DARK_PERCENTILE).toInt().coerceAtLeast(1)
        var acc = 0
        var threshold = 0
        for (l in 0..255) {
            acc += lumaHist[l]
            if (acc >= target) {
                threshold = l
                break
            }
        }

        // 3) 暗部像素各通道中位数 -> 色偏
        val laneR = Array(lanes) { IntArray(256) }
        val laneG = Array(lanes) { IntArray(256) }
        val laneB = Array(lanes) { IntArray(256) }
        val laneCount = IntArray(lanes)

        runLanes(lanes, h) { lane, y0, y1 ->
            val hr = laneR[lane]
            val hg = laneG[lane]
            val hb = laneB[lane]
            var n = 0
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    val p = pixels[row + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    if (((r * 77 + g * 150 + b * 29) shr 8) <= threshold) {
                        hr[r]++
                        hg[g]++
                        hb[b]++
                        n++
                    }
                }
            }
            laneCount[lane] = n
        }

        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        var count = 0
        for (lane in 0 until lanes) {
            count += laneCount[lane]
            val r = laneR[lane]
            val g = laneG[lane]
            val b = laneB[lane]
            for (v in 0..255) {
                histR[v] += r[v]
                histG[v] += g[v]
                histB[v] += b[v]
            }
        }

        var deltaR = 0f
        var deltaG = 0f
        var deltaB = 0f
        var denom = 1f
        var hasShadow = false

        if (count >= 64) {
            val medR = medianOf(histR, count)
            val medG = medianOf(histG, count)
            val medB = medianOf(histB, count)
            val darkLuma = medR * 0.299f + medG * 0.587f + medB * 0.114f

            if (darkLuma <= MAX_DARK_LUMA) {
                deltaR = medR - darkLuma
                deltaG = medG - darkLuma
                deltaB = medB - darkLuma
                hasShadow = kotlin.math.abs(deltaR) >= 0.6f ||
                    kotlin.math.abs(deltaG) >= 0.6f ||
                    kotlin.math.abs(deltaB) >= 0.6f
                if (hasShadow) denom = (KNEE - darkLuma).coerceAtLeast(0.001f)
            }
        }

        if (!hasShadow && matrix == null) {
            if (modified) {
                bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            }
            return modified
        }

        // 4) 最后一遍：减暗部色偏 + 套滤镜矩阵
        val m0 = matrix?.get(0) ?: 0f
        val m1 = matrix?.get(1) ?: 0f
        val m2 = matrix?.get(2) ?: 0f
        val m3 = matrix?.get(3) ?: 0f
        val m4 = matrix?.get(4) ?: 0f
        val m5 = matrix?.get(5) ?: 0f
        val m6 = matrix?.get(6) ?: 0f
        val m7 = matrix?.get(7) ?: 0f
        val m8 = matrix?.get(8) ?: 0f

        val dR = deltaR
        val dG = deltaG
        val dB = deltaB
        val den = denom
        val shadow = hasShadow

        runLanes(lanes, h) { _, y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    val p = pixels[i]
                    var r = (p shr 16) and 0xFF
                    var g = (p shr 8) and 0xFF
                    var b = p and 0xFF

                    if (shadow) {
                        val luma = (r * 77 + g * 150 + b * 29) shr 8
                        val weight = (KNEE - luma) / den
                        if (weight > 0f) {
                            val f = weight.coerceAtMost(1f)
                            r = (r - dR * f + 0.5f).toInt().coerceIn(0, 255)
                            g = (g - dG * f + 0.5f).toInt().coerceIn(0, 255)
                            b = (b - dB * f + 0.5f).toInt().coerceIn(0, 255)
                        }
                    }

                    if (matrix != null) {
                        val nr = (m0 * r + m1 * g + m2 * b + 0.5f).toInt().coerceIn(0, 255)
                        val ng = (m3 * r + m4 * g + m5 * b + 0.5f).toInt().coerceIn(0, 255)
                        val nb = (m6 * r + m7 * g + m8 * b + 0.5f).toInt().coerceIn(0, 255)
                        r = nr
                        g = ng
                        b = nb
                    }

                    pixels[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return true
    }

    private fun laneCount(h: Int): Int = cores.coerceAtMost(kotlin.math.max(1, h / 64))

    /**
     * 把 [0, h) 按行切成 lanes 段并发执行。每段只碰自己那部分像素，
     * 所以不需要任何同步；统计量各自记在 lane 自己的槽里，最后合并。
     */
    private inline fun runLanes(lanes: Int, h: Int, crossinline body: (Int, Int, Int) -> Unit) {
        if (lanes <= 1) {
            body(0, 0, h)
            return
        }
        val laneHeight = (h + lanes - 1) / lanes
        val futures = ArrayList<Future<*>?>(lanes)
        for (i in 0 until lanes) {
            val y0 = i * laneHeight
            val y1 = kotlin.math.min(h, y0 + laneHeight)
            if (y0 >= y1) break
            val lane = i
            futures.add(pool.submit(Runnable { body(lane, y0, y1) }))
        }
        for (f in futures) {
            f?.get()
        }
    }

    private fun medianOf(hist: IntArray, total: Int): Float {
        val half = total / 2
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= half) return v.toFloat()
        }
        return 0f
    }
}
