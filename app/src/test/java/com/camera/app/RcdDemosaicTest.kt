package com.camera.app

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 去马赛克的量化验证：用已知图像造 Bayer 数据，再重建回来比误差。
 *
 * 重点不是整体 RMSE（那主要反映亮度），而是**色度误差**：
 * 伪彩就是"本该没有的颜色被造出来"，所以专门比 (R−G, B−G) 的误差。
 * 双线性在斜边和细纹上会把颜色混在一起，这一项应该明显更差。
 */
class RcdDemosaicTest {

    private val w = 96
    private val h = 72
    private val cfa = RcdDemosaic.Cfa.of("BGGR")

    /**
     * 造一张贴近真实拍摄的测试图：
     *  - 一条「等亮度的色相边界」（左右亮度相同、只有色相不同）——这是伪彩最容易出现的地方
     *  - 细条纹、亮斑、渐变
     *  - 最后做一次 3x3 轻微模糊：真实照片经过镜头后是带限信号，硬边只存在于合成图里，
     *    拿硬边去比会低估双线性（它对带限信号本来就不差）
     */
    private fun makeReference(): FloatArray {
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 3
                val base = 0.15f + 0.7f * x / (w - 1f)

                var r = base
                var g = base
                var b = base

                // 等亮度色相边界：左边偏黄绿，右边偏青，亮度都在 0.55 左右
                if ((x + y) > (w + h) * 0.62f) {
                    r = 0.34f
                    g = 0.62f
                    b = 0.55f
                } else if ((x + y) > (w + h) * 0.48f) {
                    r = 0.55f
                    g = 0.60f
                    b = 0.33f
                }

                // 细条纹
                if (y in 20..26 && (x / 3) % 2 == 0) {
                    r *= 0.45f
                    g *= 0.45f
                    b *= 0.45f
                }

                // 亮斑
                val dx = x - 24f
                val dy = y - 54f
                if (dx * dx + dy * dy < 36f) {
                    r = 0.95f
                    g = 0.9f
                    b = 0.35f
                }

                rgb[i] = r.coerceIn(0f, 1f)
                rgb[i + 1] = g.coerceIn(0f, 1f)
                rgb[i + 2] = b.coerceIn(0f, 1f)
            }
        }
        return blur3(rgb)
    }

    /** 3x3 二项式模糊，模拟镜头 MTF */
    private fun blur3(src: FloatArray): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (c in 0 until 3) {
                    val l = src[(y * w + (x - 1).coerceAtLeast(0)) * 3 + c]
                    val m = src[(y * w + x) * 3 + c]
                    val r = src[(y * w + (x + 1).coerceAtMost(w - 1)) * 3 + c]
                    tmp[(y * w + x) * 3 + c] = (l + 2 * m + r) * 0.25f
                }
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (c in 0 until 3) {
                    val u = tmp[((y - 1).coerceAtLeast(0) * w + x) * 3 + c]
                    val m = tmp[(y * w + x) * 3 + c]
                    val d = tmp[((y + 1).coerceAtMost(h - 1) * w + x) * 3 + c]
                    out[(y * w + x) * 3 + c] = (u + 2 * m + d) * 0.25f
                }
            }
        }
        return out
    }

    /** 按 CFA 把 RGB 打成 Bayer */
    private fun mosaic(rgb: FloatArray): FloatArray {
        val raw = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                raw[i] = when (cfa.colorAt(x, y)) {
                    RcdDemosaic.R -> rgb[i * 3]
                    RcdDemosaic.B -> rgb[i * 3 + 2]
                    else -> rgb[i * 3 + 1]
                }
            }
        }
        return raw
    }

    /** 参照实现：纯双线性 */
    private fun bilinear(raw: FloatArray): FloatArray {
        val out = FloatArray(w * h * 3)
        fun at(x: Int, y: Int) = raw[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

        val green = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                green[i] = if (cfa.colorAt(x, y) == RcdDemosaic.G) raw[i]
                else 0.25f * (at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1))
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = cfa.colorAt(x, y)
                val g = green[i]
                val r: Float
                val b: Float
                when (c) {
                    RcdDemosaic.R -> {
                        r = raw[i]
                        b = 0.25f * (at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1))
                    }

                    RcdDemosaic.B -> {
                        b = raw[i]
                        r = 0.25f * (at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1))
                    }

                    else -> {
                        if (cfa.colorAt(x - 1, y) == RcdDemosaic.R) {
                            r = 0.5f * (at(x - 1, y) + at(x + 1, y))
                            b = 0.5f * (at(x, y - 1) + at(x, y + 1))
                        } else {
                            b = 0.5f * (at(x - 1, y) + at(x + 1, y))
                            r = 0.5f * (at(x, y - 1) + at(x, y + 1))
                        }
                    }
                }
                out[i * 3] = r
                out[i * 3 + 1] = g
                out[i * 3 + 2] = b
            }
        }
        return out
    }

    private fun rcd(raw: FloatArray): FloatArray {
        val green = FloatArray(w * h)
        RcdDemosaic.interpolateGreen(raw, w, h, cfa, green)
        val out = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                out[i * 3] = RcdDemosaic.redAt(raw, green, w, h, cfa, x, y)
                out[i * 3 + 1] = green[i]
                out[i * 3 + 2] = RcdDemosaic.blueAt(raw, green, w, h, cfa, x, y)
            }
        }
        return out
    }

    private fun metrics(name: String, recon: FloatArray, ref: FloatArray): Triple<Double, Double, Double> {
        var se = 0.0
        var chromaSe = 0.0
        var maxErr = 0.0
        val n = w * h
        for (i in 0 until n) {
            var dr = 0.0
            var dg = 0.0
            var db = 0.0
            for (c in 0 until 3) {
            val d = (recon[i * 3 + c] - ref[i * 3 + c]).toDouble()
            if (c == 0) dr = d else if (c == 1) dg = d else db = d
            se += d * d
            if (abs(d) > maxErr) maxErr = abs(d)
            }
            // 色度误差：去掉亮度成分，只看颜色
            val c1 = (dr - dg)
            val c2 = (db - dg)
            chromaSe += c1 * c1 + c2 * c2
        }
        val rmse = sqrt(se / (n * 3))
        val chroma = sqrt(chromaSe / (n * 2))
        println("$name: RMSE=%.4f  色度RMSE=%.4f  最大误差=%.3f".format(rmse, chroma, maxErr))
        return Triple(rmse, chroma, maxErr)
    }

    @Test
    fun rcdBeatsBilinear() {
        val ref = makeReference()
        val raw = mosaic(ref)

        val (biRmse, biChroma, _) = metrics("双线性", bilinear(raw), ref)
        val (rcRmse, rcChroma, _) = metrics("RCD  ", rcd(raw), ref)

        println("提升: RMSE %.1f%%  色度 %.1f%%".format(
            (1 - rcRmse / biRmse) * 100, (1 - rcChroma / biChroma) * 100
        ))

        // 真实照片是带限信号，双线性本来就不算太差，所以这里的提升是"百分点级别"的，
        // 不是教科书里那种数倍差距。要求：总误差至少降 5%，色度误差至少降 15%。
        assertTrue(
            "RCD 的总误差应低于双线性（实际 %.4f vs %.4f）".format(rcRmse, biRmse),
            rcRmse < biRmse * 0.95
        )
        assertTrue(
            "RCD 的色度误差应明显低于双线性（伪彩更少）：双线性 %.4f vs RCD %.4f".format(biChroma, rcChroma),
            rcChroma < biChroma * 0.85
        )
    }

    /** 平坦灰场不该被插值搞出颜色 */
    @Test
    fun neutralFieldStaysNeutral() {
        val rgb = FloatArray(w * h * 3)
        for (i in 0 until w * h) {
            val v = 0.2f + 0.5f * (i % w) / (w - 1f)
            rgb[i * 3] = v
            rgb[i * 3 + 1] = v
            rgb[i * 3 + 2] = v
        }
        val out = rcd(mosaic(rgb))

        var maxChroma = 0f
        for (i in 0 until w * h) {
            val r = out[i * 3]
            val g = out[i * 3 + 1]
            val b = out[i * 3 + 2]
            maxChroma = kotlin.math.max(maxChroma, kotlin.math.max(abs(r - g), abs(b - g)))
        }
        println("灰场最大色偏: %.5f".format(maxChroma))
        assertTrue("纯灰画面不该被插出颜色，实际最大偏差 $maxChroma", maxChroma < 0.01f)
    }
}
