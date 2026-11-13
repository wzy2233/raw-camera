package com.camera.app

import android.graphics.Bitmap

/**
 * 暗部中性化（自适应，逐张照片计算）。
 *
 * 为什么不能在 ColorCalibration 里一次校准搞定：
 * 黑电平不是常数。它跟 ISO、曝光、ISP 的暗部提升曲线都有关，
 * 拿"盖住镜头"那张（AE 拉满）测出来的值去套正常曝光的照片，三个通道减多减少都不一样，
 * 结果就是黑的地方被推成绿色（绿通道减得最少，活下来了）。
 *
 * 这个类的做法是：每张照片自己找暗部，
 *   1) 取亮度最低的 1% 像素，算它们的中位数 RGB
 *   2) 算出这个"暗部色"相对它自己亮度的偏差 delta
 *   3) 在暗部按权重把 delta 减掉，让最暗处回到中性，到 knee 以上权重归零
 *
 * 这样黑是纯黑，中间调一个像素都不动，也不用换环境就重新校准。
 */
object ShadowNeutralizer {

    /** 超过这个亮度就算"没有真正的暗部"，直接不动 */
    private const val MAX_DARK_LUMA = 60f

    /** 权重从 1 衰减到 0 的亮度上限 */
    private const val KNEE = 72f

    /** 参与统计的暗部比例 */
    private const val DARK_PERCENTILE = 0.01f

    fun applyInPlace(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1) 亮度直方图，找 1% 分位
        val lumaHist = IntArray(256)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lumaHist[(r * 77 + g * 150 + b * 29) shr 8]++
        }

        val targetCount = (pixels.size * DARK_PERCENTILE).toInt().coerceAtLeast(1)
        var acc = 0
        var threshold = 0
        for (l in 0..255) {
            acc += lumaHist[l]
            if (acc >= targetCount) {
                threshold = l
                break
            }
        }

        // 2) 这一撮暗部像素的各通道中位数
        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        var count = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (((r * 77 + g * 150 + b * 29) shr 8) <= threshold) {
                histR[r]++
                histG[g]++
                histB[b]++
                count++
            }
        }
        if (count < 64) return

        val medR = medianOf(histR, count)
        val medG = medianOf(histG, count)
        val medB = medianOf(histB, count)
        val darkLuma = (medR * 0.299f + medG * 0.587f + medB * 0.114f)

        // 画面本来就没什么暗部（比如拍天空），不掺和
        if (darkLuma > MAX_DARK_LUMA) return

        val deltaR = medR - darkLuma
        val deltaG = medG - darkLuma
        val deltaB = medB - darkLuma

        // 三个通道本来就一样，没必要动
        if (kotlin.math.abs(deltaR) < 0.6f &&
            kotlin.math.abs(deltaG) < 0.6f &&
            kotlin.math.abs(deltaB) < 0.6f
        ) {
            return
        }

        // 3) 按亮度权重减掉色偏，最暗处权重 1，KNEE 以上 0
        val denom = (KNEE - darkLuma).coerceAtLeast(0.001f)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = (r * 77 + g * 150 + b * 29) shr 8

            val weight = (KNEE - luma) / denom
            if (weight <= 0f) continue
            val f = weight.coerceAtMost(1f)

            val nr = (r - deltaR * f + 0.5f).toInt().coerceIn(0, 255)
            val ng = (g - deltaG * f + 0.5f).toInt().coerceIn(0, 255)
            val nb = (b - deltaB * f + 0.5f).toInt().coerceIn(0, 255)
            pixels[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
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
