package com.camera.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.Arrays

/**
 * 取景画面的实时暗部统计。
 *
 * ShadowNeutralizer 是"逐张自适应"的：它要先知道这一帧最暗的那撮像素偏什么色，
 * 才能决定减多少。所以想让预览和成片一致，就必须在预览流上实时算同一个量。
 *
 * 做法是在 ImageAnalysis 上挂一路 320x240 的 YUV：
 *   1) 抽样若干像素转成 RGB（BT.601，和 ISP 出 JPEG 时的口径接近）
 *   2) 套上和白平衡校准同一套 LUT，保证统计发生在"校准之后"的域里，和成片顺序一致
 *   3) 亮度 1% 分位以下那撮像素取各通道中位数 -> 暗部色 -> 相对亮度的偏差 delta
 *   4) 做指数平滑，避免取景时黑场数字抖动
 *
 * 代价很小：320x240 抽 1/9 的像素，一帧不到一万个点，10fps 左右完全可以忽略。
 */
class PreviewStatsAnalyzer(
    private val onStats: (delta: FloatArray?, darkLuma: Float) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "PreviewStats"

        /** 超过这个亮度就不认为画面里有真正的暗部（和 ShadowNeutralizer 一致） */
        private const val MAX_DARK_LUMA = 60f

        /** 参与统计的暗部比例 */
        private const val DARK_PERCENTILE = 0.01f

        /** 分析节流，120ms 一次足够跟得上场景变化 */
        private const val INTERVAL_MS = 120L

        /** 平滑系数，越小越稳、越迟钝 */
        private const val SMOOTHING = 0.28f
    }

    @Volatile
    private var luts: Array<IntArray>? = null

    private var lastTs = 0L
    private var primed = false
    private val smoothDelta = FloatArray(3)
    private var smoothLuma = 255f

    private var pixels = IntArray(0)
    private val lumaHist = IntArray(256)
    private val histR = IntArray(256)
    private val histG = IntArray(256)
    private val histB = IntArray(256)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 校准数据变了要同步，否则统计口径和成片对不上 */
    fun setCalibration(calibration: ColorCalibration?) {
        luts = if (calibration == null || calibration.isIdentity) null
        else calibration.buildChannelLuts()
    }

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastTs < INTERVAL_MS) {
            image.close()
            return
        }
        lastTs = now

        try {
            publish(sample(image))
        } catch (e: Exception) {
            Log.w(TAG, "预览统计失败: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun sample(image: ImageProxy): FloatArray? {
        val w = image.width
        val h = image.height
        if (w < 16 || h < 16) return null

        val planes = image.planes
        if (planes.size < 3) return null

        // 目标 8000 个采样点，太大没必要，太小统计不稳
        val step = kotlin.math.max(1, kotlin.math.sqrt((w * h / 8000f)).toInt())

        val cols = (w + step - 1) / step
        val rows = (h + step - 1) / step
        val need = cols * rows
        if (pixels.size < need) pixels = IntArray(need)

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val calibration = luts
        val lutR = calibration?.get(0)
        val lutG = calibration?.get(1)
        val lutB = calibration?.get(2)

        Arrays.fill(lumaHist, 0)
        var n = 0

        var y = 0
        while (y < h) {
            val yBase = y * yRowStride
            val uvRow = (y / 2)
            val uBase = uvRow * uRowStride
            val vBase = uvRow * vRowStride

            var x = 0
            while (x < w) {
                val yy = yBuf.get(yBase + x * yPixelStride).toInt() and 0xFF
                val uu = (uBuf.get(uBase + (x / 2) * uPixelStride).toInt() and 0xFF) - 128
                val vv = (vBuf.get(vBase + (x / 2) * vPixelStride).toInt() and 0xFF) - 128

                // BT.601 limited range，和相机 YUV 的口径一致
                val yv = (yy - 16) * 1.164f
                var r = (yv + 1.596f * vv + 0.5f).toInt().coerceIn(0, 255)
                var g = (yv - 0.391f * uu - 0.813f * vv + 0.5f).toInt().coerceIn(0, 255)
                var b = (yv + 2.018f * uu + 0.5f).toInt().coerceIn(0, 255)

                if (lutR != null && lutG != null && lutB != null) {
                    r = lutR[r]
                    g = lutG[g]
                    b = lutB[b]
                }

                pixels[n++] = (r shl 16) or (g shl 8) or b
                lumaHist[(r * 77 + g * 150 + b * 29) shr 8]++

                x += step
            }
            y += step
        }

        if (n < 256) return null

        // 1% 分位作为"暗部"门限
        val target = (n * DARK_PERCENTILE).toInt().coerceAtLeast(1)
        var acc = 0
        var threshold = 0
        for (l in 0..255) {
            acc += lumaHist[l]
            if (acc >= target) {
                threshold = l
                break
            }
        }

        Arrays.fill(histR, 0)
        Arrays.fill(histG, 0)
        Arrays.fill(histB, 0)
        var darkCount = 0
        for (i in 0 until n) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (((r * 77 + g * 150 + b * 29) shr 8) <= threshold) {
                histR[r]++
                histG[g]++
                histB[b]++
                darkCount++
            }
        }
        if (darkCount < 64) return null

        val medR = medianOf(histR, darkCount)
        val medG = medianOf(histG, darkCount)
        val medB = medianOf(histB, darkCount)
        val darkLuma = medR * 0.299f + medG * 0.587f + medB * 0.114f

        // 画面里根本没有暗部（比如对着天空），不该硬减
        if (darkLuma > MAX_DARK_LUMA) return null

        val dR = medR - darkLuma
        val dG = medG - darkLuma
        val dB = medB - darkLuma
        if (kotlin.math.abs(dR) < 0.6f && kotlin.math.abs(dG) < 0.6f && kotlin.math.abs(dB) < 0.6f) {
            return null
        }

        return floatArrayOf(dR / 255f, dG / 255f, dB / 255f, darkLuma)
    }

    private fun publish(sample: FloatArray?) {
        if (sample == null) {
            if (primed) {
                primed = false
                mainHandler.post { onStats(null, 255f) }
            }
            return
        }

        if (!primed) {
            smoothDelta[0] = sample[0]
            smoothDelta[1] = sample[1]
            smoothDelta[2] = sample[2]
            smoothLuma = sample[3]
            primed = true
        } else {
            for (i in 0..2) {
                smoothDelta[i] += (sample[i] - smoothDelta[i]) * SMOOTHING
            }
            smoothLuma += (sample[3] - smoothLuma) * SMOOTHING
        }

        val delta = floatArrayOf(smoothDelta[0], smoothDelta[1], smoothDelta[2])
        val luma = smoothLuma
        mainHandler.post { onStats(delta, luma) }
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
