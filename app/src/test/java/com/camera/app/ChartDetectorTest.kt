package com.camera.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动识别 + 朝向判定的离线验证。
 *
 * 用代码画一张 24 色卡（带黑色间隙），模拟不同的拍摄朝向和偏色，
 * 检查检测器能不能正确找到 24 格、朝向能不能靠"解一遍看残差"自动纠正。
 */
class ChartDetectorTest {

    /** 画一张 6x4 色卡：黑底 + 亮色块 */
    private fun drawChart(
        cols: Int = 6,
        rows: Int = 4,
        rotate: Int = 0,
        cast: FloatArray = floatArrayOf(1f, 1f, 1f)
    ): Pair<IntArray, Pair<Int, Int>> {
        val patch = 40
        val gap = 10
        val margin = 20
        // 先画未旋转的
        val w0 = margin * 2 + cols * patch + (cols - 1) * gap
        val h0 = margin * 2 + rows * patch + (rows - 1) * gap

        val canvas = IntArray(w0 * h0) { 0 }   // 黑底
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val ref = ColorChecker.PATCHES_SRGB[r * cols + c]
                val x0 = margin + c * (patch + gap)
                val y0 = margin + r * (patch + gap)
                for (y in y0 until y0 + patch) {
                    for (x in x0 until x0 + patch) {
                        val rr = (ref[0] * cast[0]).toInt().coerceIn(0, 255)
                        val gg = (ref[1] * cast[1]).toInt().coerceIn(0, 255)
                        val bb = (ref[2] * cast[2]).toInt().coerceIn(0, 255)
                        canvas[y * w0 + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                    }
                }
            }
        }

        // 旋转
        return when (rotate % 4) {
            0 -> canvas to (w0 to h0)
            1 -> {
                val w1 = h0; val h1 = w0
                val out = IntArray(w1 * h1)
                for (y in 0 until h0) for (x in 0 until w0) {
                    val nx = h0 - 1 - y
                    val ny = x
                    out[ny * w1 + nx] = canvas[y * w0 + x]
                }
                out to (w1 to h1)
            }

            2 -> {
                val out = IntArray(w0 * h0)
                for (y in 0 until h0) for (x in 0 until w0) {
                    out[(h0 - 1 - y) * w0 + (w0 - 1 - x)] = canvas[y * w0 + x]
                }
                out to (w0 to h0)
            }

            else -> {
                val w1 = h0; val h1 = w0
                val out = IntArray(w1 * h1)
                for (y in 0 until h0) for (x in 0 until w0) {
                    val nx = y
                    val ny = w0 - 1 - x
                    out[ny * w1 + nx] = canvas[y * w0 + x]
                }
                out to (w1 to h1)
            }
        }
    }

    private fun lumaFrom(px: IntArray): IntArray = IntArray(px.size) {
        val p = px[it]
        (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
    }

    @Test
    fun detectsStraightChart() {
        val (px, size) = drawChart()
        val luma = lumaFrom(px)
        val grid = ChartAutoDetector.detectFromLuma(luma, size.first, size.second).firstOrNull()
        assertNotNull("应该能检测到色卡", grid)
        grid!!

        assertEquals("网格应该铺成 24 格", 24, grid.rows * grid.cols)
        assertEquals("列数", 6, grid.cols)
        assertEquals("行数", 4, grid.rows)
        // 黑格（luma 52）会被 Otsu 阈值挡掉，靠行列均值插值补位——所以允许 23
        assertTrue("应该检测到绝大多数色块，实际 ${grid.detected}", grid.detected >= 20)
    }

    /** 拍歪 90° 时，解一遍就应该自动纠正回来 */
    @Test
    fun correctsRotation() {
        val cast = floatArrayOf(1.10f, 0.95f, 0.92f)   // 模拟偏暖的机器
        for (rot in 0..3) {
            val (px, size) = drawChart(rotate = rot, cast = cast)
            val luma = lumaFrom(px)
            val grid = ChartAutoDetector.detectFromLuma(luma, size.first, size.second).firstOrNull()
            assertNotNull("旋转 $rot 度时应该能检测到", grid)
            grid!!

            // 按检测到的网格取样（直接取像素，不做中位数简化）
            val colors = Array(grid.rows * grid.cols) { i ->
                val x = (grid.centers[i * 2] * size.first).toInt().coerceIn(0, size.first - 1)
                val y = (grid.centers[i * 2 + 1] * size.second).toInt().coerceIn(0, size.second - 1)
                val p = px[y * size.first + x]
                floatArrayOf(
                    ((p shr 16) and 0xFF).toFloat(),
                    ((p shr 8) and 0xFF).toFloat(),
                    (p and 0xFF).toFloat()
                )
            }

            val result = ColorChecker.solveBestOrientation(colors, grid.rows, grid.cols)
            assertNotNull("旋转 $rot 度时应能解出矩阵", result)
            println("rotation=$rot  detected=${grid.detected}  ${result!!.summary()}")
            assertTrue(
                "旋转 $rot 度时校正后 ΔE 应该很小，实际 ${result.meanAfter}",
                result.meanAfter < 2.5f
            )
        }
    }
}
