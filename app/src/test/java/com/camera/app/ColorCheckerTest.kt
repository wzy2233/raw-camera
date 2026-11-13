package com.camera.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 色卡解算器的离线验证。
 *
 * 不需要真机也不需要色卡：造一组"已知偏差"的实测值，看解出来的矩阵能不能把它还原。
 * 这比拿屏幕拍一张更有说服力——屏幕本身还有它自己的颜色误差。
 */
class ColorCheckerTest {

    /** 参考值本身：解出来的应当是单位阵，ΔE 应当≈0 */
    @Test
    fun identityWhenMeasuredEqualsReference() {
        val measured = Array(ColorChecker.COUNT) {
            FloatArray(3) { c -> ColorChecker.PATCHES_SRGB[it][c].toFloat() }
        }
        val result = ColorChecker.solve(measured)
        assertNotNull(result)
        result!!

        println("identity: ${result.summary()}")
        assertTrue("校正后平均 ΔE 应该接近 0，实际 ${result.meanAfter}", result.meanAfter < 0.5f)

        val m = result.matrix
        assertEquals(1f, m[0], 0.02f)
        assertEquals(1f, m[4], 0.02f)
        assertEquals(1f, m[8], 0.02f)
        assertEquals(0f, m[1], 0.02f)
    }

    /**
     * 造一台"偏色机器"：实测 = A × 参考，A 就是这台机器的色彩误差。
     * 解出来的 M 应当接近 A⁻¹，ΔE 应当从几降到 1 以内。
     */
    @Test
    fun recoversKnownCameraError() {
        // 偏绿、蓝通道偏弱的一台假想相机
        val a = floatArrayOf(
            1.12f, 0.05f, -0.02f,
            0.00f, 0.94f, 0.04f,
            -0.03f, 0.03f, 1.10f
        )

        val measured = Array(ColorChecker.COUNT) { i ->
            val lin = FloatArray(3) { c ->
                ColorChecker.linearizeOne(ColorChecker.PATCHES_SRGB[i][c] / 255f)
            }
            val out = FloatArray(3) { r ->
                (a[r * 3] * lin[0] + a[r * 3 + 1] * lin[1] + a[r * 3 + 2] * lin[2])
                    .coerceIn(0f, 1f)
            }
            FloatArray(3) { c -> ColorChecker.encode(out[c]) * 255f }
        }

        val result = ColorChecker.solve(measured)
        assertNotNull(result)
        result!!

        println("known-error: ${result.summary()}")
        assertTrue("校正前的 ΔE 应当明显偏大，实际 ${result.meanBefore}", result.meanBefore > 3f)
        assertTrue("校正后平均 ΔE 应 < 1.5，实际 ${result.meanAfter}", result.meanAfter < 1.5f)
        assertTrue("校正后应当比校正前好得多", result.meanAfter < result.meanBefore / 3f)
    }

    /** 噪声下的稳定性：加一点随机噪声，结果不应该崩 */
    @Test
    fun stableUnderNoise() {
        val rnd = java.util.Random(7)
        val measured = Array(ColorChecker.COUNT) { i ->
            val lin = FloatArray(3) { c ->
                ColorChecker.linearizeOne(ColorChecker.PATCHES_SRGB[i][c] / 255f) * 0.95f
            }
            FloatArray(3) { c ->
                val noisy = (lin[c] + (rnd.nextFloat() - 0.5f) * 0.02f).coerceIn(0f, 1f)
                ColorChecker.encode(noisy) * 255f
            }
        }
        val result = ColorChecker.solve(measured)
        assertNotNull(result)
        val r = result!!
        println("noisy: ${r.summary()}")
        for (v in r.matrix) {
            assertTrue("矩阵元素应该有限且温和: $v", v.isFinite() && kotlin.math.abs(v) < 1.8f)
        }
        assertTrue("噪声下平均 ΔE 应 < 3，实际 ${r.meanAfter}", r.meanAfter < 3f)
    }
}
