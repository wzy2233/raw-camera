package com.camera.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/**
 * 从 DNG 文件里读出厂商写进去的色彩描述，转成"白平衡后的相机 RGB -> sRGB 线性"矩阵。
 *
 * 为什么要这么做：
 * 我们出片一直用 `COLOR_CORRECTION_TRANSFORM`（ISP 当次用的 3x3），实测它和 DNG 里那套
 * 标准描述差别很大——同一块白屏，用它算出来偏蓝，用 DNG 里的 ForwardMatrix 算出来接近中性，
 * 而 Photoshop 打开同一张 DNG 显示的正是后者。也就是说 ISP 那套矩阵是"给 ISP 流水线调过"的，
 * 不是 DNG 规范意义下的相机特性描述。
 *
 * DNG 里有三套东西，优先级从高到低：
 *   1. ForwardMatrix1/2 —— 直接作用在"已白平衡"的相机 RGB 上，转 XYZ(D50)。规范推荐优先用这个
 *   2. ColorMatrix1/2   —— 作用在"未白平衡"的相机 RGB 上，转 XYZ(D50)
 *   3. 我们自己从 ISP 拿的 COLOR_CORRECTION_TRANSFORM
 *
 * 两套矩阵分别对应两个标定光源（CalibrationIlluminant1/2，通常是 D65 和标准光源 A）。
 * 严格做法要按当前白平衡的色温在两者之间插值；这里先取两者平均——它们对同一场景的差异
 * 很小（实测屏幕：FM1 给 173/231/255，FM2 给 182/252/255），不值得为插值引入色温估算的
 * 一圈误差。以后要更准再补。
 */
object DngProfile {

    private const val TAG = "DngProfile"
    private const val PREF = "dng_profile"

    /** DNG/TIFF 里我们关心的标签 */
    private const val TAG_COLOR_MATRIX1 = 50721
    private const val TAG_COLOR_MATRIX2 = 50722
    private const val TAG_FORWARD_MATRIX1 = 50964
    private const val TAG_FORWARD_MATRIX2 = 50965

    /**
     * XYZ(D50) -> sRGB 线性，已内含 Bradford D50->D65 色适应（Adobe 标准矩阵）。
     * 用它乘 ForwardMatrix，就得到"相机 RGB(已白平衡) -> sRGB 线性"。
     */
    private val D50_TO_SRGB = floatArrayOf(
        3.1338561f, -1.6168667f, -0.4906146f,
        -0.9787684f, 1.9161415f, 0.0334540f,
        0.0719453f, -0.2289914f, 1.4052427f
    )

    /** 直接可用的 cam(WB后) -> sRGB 线性矩阵，行优先 9 个数 */
    class Profile(
        val matrix: FloatArray,
        /** 来源描述，方便日志和排查 */
        val source: String
    )

    /**
     * 解析 DNG，返回可用的矩阵。失败返回 null（调用方回退到 ISP 矩阵）。
     */
    fun read(file: File): Profile? {
        if (!file.exists() || file.length() < 64) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(8)
                raf.readFully(head)
                val bo = when {
                    head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                    head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                    else -> return null
                }
                val buf = java.nio.ByteBuffer.wrap(head).order(bo)
                val ifdOffset = buf.getInt(4)
                val tags = HashMap<Int, FloatArray>()
                readIfd(raf, bo, ifdOffset.toLong(), tags, 0)

                val fm1 = tags[TAG_FORWARD_MATRIX1]
                val fm2 = tags[TAG_FORWARD_MATRIX2]
                val cm1 = tags[TAG_COLOR_MATRIX1]
                val cm2 = tags[TAG_COLOR_MATRIX2]

                // 优先 ForwardMatrix
                val fm = when {
                    fm1 != null && fm2 != null -> FloatArray(9) { (fm1[it] + fm2[it]) / 2f }
                    fm1 != null -> fm1
                    fm2 != null -> fm2
                    else -> null
                }
                if (fm != null) {
                    val m = mat3Mul(D50_TO_SRGB, fm)
                    Log.d(TAG, "用 ForwardMatrix，行和 %.3f/%.3f/%.3f".format(
                        m[0] + m[1] + m[2], m[3] + m[4] + m[5], m[6] + m[7] + m[8]
                    ))
                    return Profile(m, "ForwardMatrix")
                }

                // 没有 ForwardMatrix 时才退到 ColorMatrix。
                // 注意 ColorMatrix 作用在"未白平衡"的原始值上，直接拿来当 cam(WB后)->sRGB 用是错的，
                // 只能临时应付——所以这里只在有 CM 时给出，并标注来源，方便发现问题。
                val cm = when {
                    cm1 != null && cm2 != null -> FloatArray(9) { (cm1[it] + cm2[it]) / 2f }
                    cm1 != null -> cm1
                    cm2 != null -> cm2
                    else -> null
                } ?: return null
                val m = mat3Mul(D50_TO_SRGB, cm)
                Log.w(TAG, "只有 ColorMatrix，结果可能不准（未按白平衡换算）")
                Profile(m, "ColorMatrix")
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 DNG 色彩描述失败: ${e.message}")
            null
        }
    }

    // ---------------- 存储 ----------------

    fun save(context: Context, p: Profile) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("matrix", p.matrix.joinToString(","))
            .putString("source", p.source)
            .putLong("time", System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context): Profile? {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = prefs.getString("matrix", null) ?: return null
        val parts = raw.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (parts.size != 9) return null
        return Profile(parts.toFloatArray(), prefs.getString("source", "?") ?: "?")
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ---------------- TIFF 解析 ----------------

    private fun readIfd(
        raf: RandomAccessFile,
        bo: ByteOrder,
        offset: Long,
        out: HashMap<Int, FloatArray>,
        depth: Int
    ) {
        if (depth > 2 || offset <= 0 || offset > raf.length() - 2) return
        raf.seek(offset)
        val cntBuf = ByteArray(2)
        raf.readFully(cntBuf)
        val count = java.nio.ByteBuffer.wrap(cntBuf).order(bo).short.toInt() and 0xFFFF
        if (count <= 0 || count > 512) return

        val entry = ByteArray(12 * count + 4)
        raf.readFully(entry)
        val buf = java.nio.ByteBuffer.wrap(entry).order(bo)

        for (i in 0 until count) {
            val base = i * 12
            val tag = buf.getShort(base).toInt() and 0xFFFF
            val type = buf.getShort(base + 2).toInt() and 0xFFFF
            val n = buf.getInt(base + 4)
            if (tag != TAG_COLOR_MATRIX1 && tag != TAG_COLOR_MATRIX2 &&
                tag != TAG_FORWARD_MATRIX1 && tag != TAG_FORWARD_MATRIX2
            ) continue

            val size = typeSize(type) * n
            val dataOffset: Long = if (size <= 4) {
                (offset + 2 + base + 8)
            } else {
                buf.getInt(base + 8).toLong() and 0xFFFFFFFFL
            }
            val values = readRationals(raf, bo, dataOffset, type, n) ?: continue
            if (values.size == 9) out[tag] = values
        }
    }

    private fun typeSize(type: Int): Int = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11 -> 4
        5, 10, 12 -> 8
        else -> 1
    }

    /** 只支持我们需要的 SRATIONAL(10) / RATIONAL(5)；其余返回 null */
    private fun readRationals(
        raf: RandomAccessFile,
        bo: ByteOrder,
        offset: Long,
        type: Int,
        n: Int
    ): FloatArray? {
        if (type != 5 && type != 10) return null
        if (offset <= 0 || offset + 8L * n > raf.length()) return null
        val raw = ByteArray(8 * n)
        raf.seek(offset)
        raf.readFully(raw)
        val buf = java.nio.ByteBuffer.wrap(raw).order(bo)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val num = buf.getInt(i * 8)
            val den = buf.getInt(i * 8 + 4)
            out[i] = if (den != 0) num.toFloat() / den.toFloat() else 0f
        }
        return out
    }

    private fun mat3Mul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                var s = 0f
                for (k in 0 until 3) s += a[r * 3 + k] * b[k * 3 + c]
                out[r * 3 + c] = s
            }
        }
        return out
    }
}
