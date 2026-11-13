package com.camera.app

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 4:4:4 基线 JPEG 编码器。
 *
 * 为什么要自己写：Android 的 `Bitmap.compress(JPEG)` 走 Skia，色度固定 4:2:0
 * （Y 2x2、Cb/Cr 1x1），没有任何接口能改。4:2:0 会让色度分辨率降到四分之一，
 * 彩色边缘（红色键帽、文字描边）就会糊——这是"色彩压缩太狠"的直接原因。
 *
 * 这里输出标准基线 JPEG，三个分量都是全分辨率（sampling factor 全 1x1），
 * 用标准霍夫曼表和可调质量系数，主流软件都能读。
 *
 * 多线程：按 MCU 行切分，每行独立编码后用 restart marker (RSTn) 隔开。
 * JPEG 规范里 RST 的语义就是"重置 DC 预测器和字节对齐"，所以逐行独立编码是合法的，
 * 拼接时不需要任何额外处理。DRI 段的 restart interval 就设成一行的 MCU 数。
 */
object Jpeg444Encoder {

    private const val TAG = "Jpeg444"

    // ---------------- 标准量化表（ITU T.81 Annex K） ----------------

    private val LUMA_Q = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99
    )

    private val CHROMA_Q = intArrayOf(
        17, 18, 24, 47, 99, 99, 99, 99,
        18, 21, 26, 66, 99, 99, 99, 99,
        24, 26, 56, 99, 99, 99, 99, 99,
        47, 66, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99
    )

    /** 自然顺序下标 -> zigzag 顺序 */
    private val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10,
        17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34,
        27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36,
        29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46,
        53, 60, 61, 54, 47, 55, 62, 63
    )

    // 标准霍夫曼表（Annex K.3）
    private val DC_LUMA_BITS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
    private val DC_LUMA_VALS = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

    private val DC_CHROMA_BITS = intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
    private val DC_CHROMA_VALS = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

    private val AC_LUMA_BITS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D)
    private val AC_LUMA_VALS = intArrayOf(
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12,
        0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
        0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08,
        0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
        0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16,
        0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39,
        0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
        0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
        0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
        0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79,
        0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
        0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98,
        0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
        0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6,
        0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5,
        0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4,
        0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
        0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA,
        0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
        0xF9, 0xFA
    )

    private val AC_CHROMA_BITS = intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
    private val AC_CHROMA_VALS = intArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21,
        0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
        0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91,
        0xA1, 0xB1, 0xC1, 0x09, 0x23, 0x33, 0x52, 0xF0,
        0x15, 0x62, 0x72, 0xD1, 0x0A, 0x16, 0x24, 0x34,
        0xE1, 0x25, 0xF1, 0x17, 0x18, 0x19, 0x1A, 0x26,
        0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38,
        0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
        0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
        0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
        0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78,
        0x79, 0x7A, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
        0x88, 0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96,
        0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5,
        0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4,
        0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3,
        0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2,
        0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA,
        0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9,
        0xEA, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
        0xF9, 0xFA
    )

    /** DCT 余弦基表：COS[u][x] = C(u)/2 * cos((2x+1)uπ/16) */
    private val COS = Array(8) { u ->
        FloatArray(8) { x ->
            val cu = if (u == 0) 1f / kotlin.math.sqrt(2f) else 1f
            (cu * kotlin.math.cos((2 * x + 1) * u * Math.PI / 16.0) / 2.0).toFloat()
        }
    }

    // ---------------- 入口 ----------------

    /**
     * @param argb    ARGB_8888 像素，行优先
     * @param quality 1..100
     */
    fun encode(argb: IntArray, width: Int, height: Int, quality: Int): ByteArray {
        val q = quality.coerceIn(1, 100)
        val scale = if (q < 50) 5000 / q else 200 - 2 * q
        val qLuma = scaleTable(LUMA_Q, scale)
        // 色度表在高档位单独收紧。
        //
        // 标准 Annex K 表在 q95（scale=10）下给色度 DC 的量化步长是 **2 码**，
        // 而色度块均值一取整，块内那点 ±0.5 码的抖动就被抹平了——结果是平滑渐变里
        // 的色度变成一片片同值平台（实测最长 134~222 像素、占比 0.6%），
        // 肉眼就是"一圈圈彩色等高线"。亮度因为 DC 步长只有 1、抖动留得住，所以没这问题。
        // 这里高质量档把色度表再乘 0.7（DC 落到 1 码），代价是体积涨 10~20%。
        val chromaScale = if (q >= 90) (scale * 7 + 5) / 10 else scale
        val qChroma = scaleTable(CHROMA_Q, maxOf(1, chromaScale))

        val dcLuma = buildHuffman(DC_LUMA_BITS, DC_LUMA_VALS)
        val acLuma = buildHuffman(AC_LUMA_BITS, AC_LUMA_VALS)
        val dcChroma = buildHuffman(DC_CHROMA_BITS, DC_CHROMA_VALS)
        val acChroma = buildHuffman(AC_CHROMA_BITS, AC_CHROMA_VALS)

        val mcuX = (width + 7) / 8
        val mcuY = (height + 7) / 8

        val out = ByteArrayOutputStream(width * height / 2 + 65536)
        writeHeaders(out, width, height, qLuma, qChroma, mcuX)

        // 按 MCU 行切给线程池；每行独立编码（DC 预测器从 0 开始），行间插 RST
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)
        val pool = Executors.newFixedThreadPool(cores) { r ->
            Thread(r, "jpeg444").apply { isDaemon = true }
        }
        val tasks = ArrayList<Callable<ByteArray>>(mcuY)
        for (my in 0 until mcuY) {
            tasks.add(Callable {
                encodeRow(
                    argb, width, height, my, mcuX, qLuma, qChroma,
                    dcLuma, acLuma, dcChroma, acChroma
                )
            })
        }
        try {
            val results = pool.invokeAll(tasks)
            var idx = 0
            for (f in results) {
                out.write(f.get())
                // 除最后一行外，插入 RSTn（n 循环 0..7）
                if (idx < mcuY - 1) {
                    out.write(0xFF)
                    out.write(0xD0 + (idx and 7))
                }
                idx++
            }
        } finally {
            pool.shutdown()
        }

        out.write(0xFF)
        out.write(0xD9)   // EOI
        val bytes = out.toByteArray()
        try {
            Log.d(TAG, "4:4:4 编码完成 ${width}x${height} q=$q -> ${bytes.size / 1024}KB")
        } catch (t: Throwable) {
            // 单元测试环境没有 android.util.Log，忽略
        }
        return bytes
    }

    // ---------------- 单行编码 ----------------

    private fun encodeRow(
        argb: IntArray,
        width: Int,
        height: Int,
        my: Int,
        mcuX: Int,
        qLuma: IntArray,
        qChroma: IntArray,
        dcLuma: IntArray,
        acLuma: IntArray,
        dcChroma: IntArray,
        acChroma: IntArray
    ): ByteArray {
        val bw = BitWriter(mcuX * 256)
        val yBlock = FloatArray(64)
        val cbBlock = FloatArray(64)
        val crBlock = FloatArray(64)
        val coef = IntArray(64)      // 三块共用，复用而非每块分配
        var predY = 0
        var predCb = 0
        var predCr = 0

        for (mx in 0 until mcuX) {
            val x0 = mx * 8
            val y0 = my * 8

            // 8x8 取样 + RGB -> YCbCr（4:4:4：每个像素都算，不做下采样）
            for (yy in 0 until 8) {
                val py = (y0 + yy).coerceAtMost(height - 1)
                val rowBase = py * width
                for (xx in 0 until 8) {
                    val px = (x0 + xx).coerceAtMost(width - 1)
                    val c = argb[rowBase + px]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val i = yy * 8 + xx
                    val yv = 0.299f * r + 0.587f * g + 0.114f * b
                    yBlock[i] = yv - 128f
                    cbBlock[i] = (-0.168736f * r - 0.331264f * g + 0.5f * b)
                    crBlock[i] = (0.5f * r - 0.418688f * g - 0.081312f * b)
                }
            }

            predY = encodeBlock(yBlock, coef, qLuma, dcLuma, acLuma, predY, bw)
            predCb = encodeBlock(cbBlock, coef, qChroma, dcChroma, acChroma, predCb, bw)
            predCr = encodeBlock(crBlock, coef, qChroma, dcChroma, acChroma, predCr, bw)
        }
        bw.flush()
        return bw.toByteArray()
    }

    /** FDCT + 量化 + 熵编码，返回新的 DC 预测值。coef 由调用方复用，避免每块分配 */
    private fun encodeBlock(
        block: FloatArray,
        coef: IntArray,
        quant: IntArray,
        dcTable: IntArray,
        acTable: IntArray,
        pred: Int,
        bw: BitWriter
    ): Int {
        fdct(block)

        // 量化 + zigzag 重排。
        // coef 是调用方传进来的复用数组——原来这里每次 `IntArray(64)`，
        // 12MP 一张图是 59 万个 8x8 块，等于 59 万次分配 + 清零，是最大的单项浪费。
        for (k in 0 until 64) {
            val natural = ZIGZAG[k]
            val v = block[natural] / quant[k]
            coef[k] = Math.round(v)
        }

        // DC 差分
        val diff = coef[0] - pred
        writeDC(bw, dcTable, diff)

        // AC
        var run = 0
        for (k in 1 until 64) {
            val v = coef[k]
            if (v == 0) {
                run++
                continue
            }
            while (run > 15) {
                bw.writeHuff(acTable, 0xF0)   // ZRL
                run -= 16
            }
            val size = bitSize(v)
            bw.writeHuff(acTable, (run shl 4) or size)
            bw.writeBits(if (v < 0) v - 1 else v, size)
            run = 0
        }
        if (run > 0) bw.writeHuff(acTable, 0x00)   // EOB

        return coef[0]
    }

    private fun writeDC(bw: BitWriter, table: IntArray, diff: Int) {
        val size = bitSize(diff)
        bw.writeHuff(table, size)
        if (size > 0) bw.writeBits(if (diff < 0) diff - 1 else diff, size)
    }

    private fun bitSize(v: Int): Int {
        var a = if (v < 0) -v else v
        var n = 0
        while (a != 0) {
            n++
            a = a shr 1
        }
        return n
    }

    /** 行列分离的 8x8 DCT（原地） */
    private fun fdct(b: FloatArray) {
        val tmp = FloatArray(64)
        // 行
        for (y in 0 until 8) {
            val o = y * 8
            for (u in 0 until 8) {
                var s = 0f
                val cu = COS[u]
                for (x in 0 until 8) s += b[o + x] * cu[x]
                tmp[o + u] = s
            }
        }
        // 列
        for (x in 0 until 8) {
            for (v in 0 until 8) {
                var s = 0f
                val cv = COS[v]
                for (y in 0 until 8) s += tmp[y * 8 + x] * cv[y]
                b[v * 8 + x] = s
            }
        }
    }

    // ---------------- 头部 ----------------

    private fun scaleTable(base: IntArray, scale: Int): IntArray {
        // 输出是 zigzag 顺序
        val out = IntArray(64)
        for (k in 0 until 64) {
            val v = (base[ZIGZAG[k]] * scale + 50) / 100
            out[k] = v.coerceIn(1, 255)
        }
        return out
    }

    private fun buildHuffman(bits: IntArray, vals: IntArray): IntArray {
        // 返回 256 项查表：高 16 位是码长，低 16 位是码字
        val table = IntArray(256)
        var code = 0
        var k = 0
        for (len in 1..16) {
            for (i in 0 until bits[len - 1]) {
                val symbol = vals[k++]
                table[symbol] = (len shl 16) or code
                code++
            }
            code = code shl 1
        }
        return table
    }

    private fun writeHeaders(
        out: ByteArrayOutputStream,
        w: Int,
        h: Int,
        qLuma: IntArray,
        qChroma: IntArray,
        restartInterval: Int
    ) {
        out.write(0xFF); out.write(0xD8)          // SOI

        // APP0 JFIF
        out.write(0xFF); out.write(0xE0)
        writeShort(out, 16)
        out.write('J'.code); out.write('F'.code); out.write('I'.code); out.write('F'.code); out.write(0)
        out.write(1); out.write(1)                // 版本 1.1
        out.write(0)                              // 单位：无
        writeShort(out, 1); writeShort(out, 1)    // 密度
        out.write(0); out.write(0)                // 缩略图 0x0

        // DQT
        out.write(0xFF); out.write(0xDB)
        writeShort(out, 2 + 1 + 64)
        out.write(0x00)
        for (v in qLuma) out.write(v)
        out.write(0xFF); out.write(0xDB)
        writeShort(out, 2 + 1 + 64)
        out.write(0x01)
        for (v in qChroma) out.write(v)

        // SOF0：三个分量全 1x1 采样 = 4:4:4
        out.write(0xFF); out.write(0xC0)
        writeShort(out, 8 + 3 * 3)
        out.write(8)                              // 精度
        writeShort(out, h); writeShort(out, w)
        out.write(3)
        out.write(1); out.write(0x11); out.write(0)   // Y
        out.write(2); out.write(0x11); out.write(1)   // Cb
        out.write(3); out.write(0x11); out.write(1)   // Cr

        // DHT
        writeDht(out, 0x00, DC_LUMA_BITS, DC_LUMA_VALS)
        writeDht(out, 0x10, AC_LUMA_BITS, AC_LUMA_VALS)
        writeDht(out, 0x01, DC_CHROMA_BITS, DC_CHROMA_VALS)
        writeDht(out, 0x11, AC_CHROMA_BITS, AC_CHROMA_VALS)

        // DRI
        out.write(0xFF); out.write(0xDD)
        writeShort(out, 4)
        writeShort(out, restartInterval)

        // SOS
        out.write(0xFF); out.write(0xDA)
        writeShort(out, 6 + 2 * 3)
        out.write(3)
        out.write(1); out.write(0x00)
        out.write(2); out.write(0x11)
        out.write(3); out.write(0x11)
        out.write(0); out.write(63); out.write(0)
    }

    private fun writeDht(out: ByteArrayOutputStream, cls: Int, bits: IntArray, vals: IntArray) {
        out.write(0xFF); out.write(0xC4)
        writeShort(out, 2 + 1 + 16 + vals.size)
        out.write(cls)
        for (b in bits) out.write(b)
        for (v in vals) out.write(v)
    }

    private fun writeShort(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    // ---------------- 位写入（含 0xFF 转义） ----------------

    private class BitWriter(capacity: Int) {
        private var buf = ByteArray(capacity.coerceAtLeast(1024))
        private var len = 0
        private var acc = 0
        private var accBits = 0

        fun writeBits(value: Int, n: Int) {
            if (n <= 0) return
            // **整字节推进，不要逐比特循环。**
            // 原来的写法是 `for (i in n-1 downTo 0)` 一位一位塞，一张 12MP 的图
            // 大约要产出 3800 万比特，就是 3800 万次循环迭代——单独这一项就占了几百毫秒。
            // 现在用 32 位累加器：一次移位就把 n 位并进去，然后每次吐出完整的字节。
            // acc 里最多残留 7 位，n 最大 16，移位后不超过 23 位，Int 装得下。
            acc = (acc shl n) or (value and ((1 shl n) - 1))
            accBits += n
            while (accBits >= 8) {
                accBits -= 8
                emit((acc ushr accBits) and 0xFF)
            }
        }

        fun writeHuff(table: IntArray, symbol: Int) {
            val e = table[symbol and 0xFF]
            if (e == 0) return
            writeBits(e and 0xFFFF, e ushr 16)
        }

        private fun emit(b: Int) {
            if (len + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
            buf[len++] = b.toByte()
            // 熵编码段里 0xFF 后面必须跟 0x00
            if (b == 0xFF) {
                if (len + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
                buf[len++] = 0
            }
        }

        fun flush() {
            if (accBits > 0) {
                // 用 1 填充到字节边界
                val pad = 8 - accBits
                acc = (acc shl pad) or ((1 shl pad) - 1)
                emit(acc and 0xFF)
                acc = 0
                accBits = 0
            }
        }

        fun toByteArray(): ByteArray = buf.copyOf(len)
    }

    // ---------------- 便捷：编码 Bitmap ----------------

    fun encodeBitmap(bitmap: Bitmap, quality: Int): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        return encode(px, w, h, quality)
    }
}
