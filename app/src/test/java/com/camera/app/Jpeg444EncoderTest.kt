package com.camera.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只做结构校验：段顺序、采样因子、DRI。
 * 像素正确性没法在这里验（Android 单元测试的 classpath 没有 javax.imageio），
 * 放到真机上编码再用 PIL 解码对比。
 */
class Jpeg444EncoderTest {

    private fun makeImage(w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r: Int
                val g: Int
                val b: Int
                if (x < w / 2) {
                    r = x * 255 / (w / 2)
                    g = y * 255 / h
                    b = 128
                } else {
                    val s = (x / 4) % 3
                    r = if (s == 0) 230 else 30
                    g = if (s == 1) 220 else 40
                    b = if (s == 2) 210 else 50
                }
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return px
    }

    private class Seg(val marker: Int, val offset: Int, val len: Int)

    private fun segments(b: ByteArray): List<Seg> {
        val out = ArrayList<Seg>()
        var i = 2
        while (i < b.size - 3) {
            if ((b[i].toInt() and 0xFF) != 0xFF) break
            val m = b[i + 1].toInt() and 0xFF
            if (m == 0xD9) break
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            out.add(Seg(m, i, len))
            if (m == 0xDA) break
            i += 2 + len
        }
        return out
    }

    @Test
    fun hasExpectedStructure() {
        val w = 64
        val h = 48
        val b = Jpeg444Encoder.encode(makeImage(w, h), w, h, 95)

        assertEquals("SOI", 0xD8, b[1].toInt() and 0xFF)
        assertEquals("EOI", 0xD9, b[b.size - 1].toInt() and 0xFF)

        val segs = segments(b)
        val markers = segs.map { it.marker }
        assertTrue("应有 SOF0", markers.contains(0xC0))
        assertEquals("应有 DQT x2", 2, markers.count { it == 0xDB })
        assertEquals("应有 DHT x4", 4, markers.count { it == 0xC4 })
        assertEquals("应有 DRI", 1, markers.count { it == 0xDD })

        // 段顺序：APP0 -> DQT -> SOF0 -> DHT -> DRI -> SOS
        val order = markers.filter { it in listOf(0xE0, 0xDB, 0xC0, 0xC4, 0xDD, 0xDA) }
        assertEquals("APP0 必须最先", 0xE0, order.first())
        assertTrue("SOF0 应在所有 DQT 之后", order.indexOf(0xC0) > order.lastIndexOf(0xDB))
        assertTrue("SOS 必须最后", order.last() == 0xDA)

        val sof = segs.first { it.marker == 0xC0 }
        val base = sof.offset + 4
        assertEquals("宽度", w, ((b[base + 3].toInt() and 0xFF) shl 8) or (b[base + 4].toInt() and 0xFF))
        assertEquals("高度", h, ((b[base + 1].toInt() and 0xFF) shl 8) or (b[base + 2].toInt() and 0xFF))
        assertEquals("分量数", 3, b[base + 5].toInt())
        for (c in 0 until 3) {
            assertEquals(
                "分量 $c 采样因子必须是 1x1（4:4:4）",
                0x11, b[base + 6 + c * 3 + 1].toInt() and 0xFF
            )
        }
    }

    @Test
    fun restartIntervalMatchesMcusPerRow() {
        val w = 64
        val h = 48
        val b = Jpeg444Encoder.encode(makeImage(w, h), w, h, 90)
        val dri = segments(b).first { it.marker == 0xDD }
        val interval = ((b[dri.offset + 4].toInt() and 0xFF) shl 8) or (b[dri.offset + 5].toInt() and 0xFF)
        assertEquals("每行 MCU 数 = 64/8", 8, interval)

        // 5 行 MCU，行间 4 个 RST
        var rst = 0
        var i = 0
        while (i < b.size - 1) {
            if ((b[i].toInt() and 0xFF) == 0xFF) {
                val m = b[i + 1].toInt() and 0xFF
                if (m in 0xD0..0xD7) rst++
            }
            i++
        }
        assertEquals("RST 数量 = 行数 - 1", 5, rst)
    }

    @Test
    fun oddSizeIsHandled() {
        val w = 37
        val h = 21
        val b = Jpeg444Encoder.encode(makeImage(w, h), w, h, 90)
        assertEquals("EOI", 0xD9, b[b.size - 1].toInt() and 0xFF)
        val sof = segments(b).first { it.marker == 0xC0 }
        val base = sof.offset + 4
        assertEquals("宽度按原值写入", w, ((b[base + 3].toInt() and 0xFF) shl 8) or (b[base + 4].toInt() and 0xFF))
    }
}
