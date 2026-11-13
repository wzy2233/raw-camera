package com.camera.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import kotlin.math.pow

/**
 * 两点线性色彩校准。
 *
 * 手机 ISP 出来的 JPEG 在接近全黑时并不真的是 0，而是每个通道带一个"底"（暗电流 + 色阶抬升），
 * 三个通道的底不一样大，肉眼看就是"深蓝紫"而不是黑。白平衡再好也压不掉它，因为它不是增益问题，
 * 是偏置问题。
 *
 * 这里用最朴素也最有效的办法做两点线性化：
 *   1) 暗场采样 -> 得到每通道的黑电平 black[c]
 *   2) 中性面采样 -> 得到每通道的读数 white[c]
 *   3) out = clamp((in - black[c]) * gain[c])
 *
 * 增益怎么取，决定了这是"白平衡"还是"曝光拉伸"：
 *   - 早先的版本把参考面硬映射到 235，等于把灰卡当白纸用，整张照片被拉亮两档，不是还原；
 *   - 现在改成【亮度守恒】：三个通道都映射到"参考面当前的中性亮度"
 *        gain[c] = Y / (linear(white[c]) - linear(black[c]))
 *     这样只把色偏去掉，整体亮度分毫不动，曝光仍然由相机的 AE 决定。
 *
 * 好处是参考面不再有讲究：白纸、18% 灰卡、灰色墙、屏幕灰场都行，
 * 因为 gain 的【比值】只跟三通道读数之比有关，跟参考面本身多亮无关。
 */
class ColorCalibration(
    /** 暗场（遮住镜头）每通道平均值 0..255，null 表示没做过 */
    val dark: FloatArray?,
    /** 中性面（白纸/灰卡/灰墙）每通道实测值 0..255，null 表示没做过 */
    val white: FloatArray?
) {

    private fun blackAt(i: Int): Float = dark?.getOrNull(i) ?: 0f

    /**
     * 亮度守恒的线性光增益。
     *
     * 先把三个通道各自减掉黑电平，得到 (r, g, b)；
     * 再用它们自己的亮度 Y 当作中性目标，于是三者被拉到同一个 Y 上 —— 色偏消除、亮度不变。
     */
    private val gains: FloatArray by lazy {
        val w = white ?: return@lazy floatArrayOf(1f, 1f, 1f)
        if (w.size < 3) return@lazy floatArrayOf(1f, 1f, 1f)

        val lin = FloatArray(3) { i ->
            (toLinear(w[i]) - toLinear(blackAt(i))).coerceAtLeast(0f)
        }
        val y = 0.299f * lin[0] + 0.587f * lin[1] + 0.114f * lin[2]
        if (y < 1e-5f) return@lazy floatArrayOf(1f, 1f, 1f)

        FloatArray(3) { i ->
            // span 太小说明白场采样无效（比如对着墙拍的），避免放大成噪点
            if (lin[i] < 0.004f) 1f else (y / lin[i]).coerceIn(0.2f, 5f)
        }
    }

    /** 给 UI 显示用的三通道增益 */
    fun gains(): FloatArray = gains.copyOf()

    val isIdentity: Boolean
        get() = isLutIdentity(buildLut(0)) &&
            isLutIdentity(buildLut(1)) &&
            isLutIdentity(buildLut(2))

    private fun isLutIdentity(lut: IntArray): Boolean {
        for (v in 0..255) {
            if (lut[v] != v) return false
        }
        return true
    }

    fun hasDark(): Boolean = dark != null && dark.any { it >= 0.5f }

    fun hasWhite(): Boolean = white != null

    /** 逐通道 256 项 LUT，给实时统计（YUV 采样）复用同一套映射 */
    fun buildChannelLuts(): Array<IntArray> = arrayOf(buildLut(0), buildLut(1), buildLut(2))

    /**
     * 给预览着色器用的参数：线性光里的黑电平(0..1) + 线性光增益。
     * 和 buildLut() 用的是同一组公式，所以预览和成片的映射一致。
     */
    fun shaderParams(): FloatArray = floatArrayOf(
        toLinear(blackAt(0)), toLinear(blackAt(1)), toLinear(blackAt(2)),
        gains[0], gains[1], gains[2]
    )

    /** 原地校正，不额外分配整张 bitmap，省内存 */
    fun applyInPlace(bitmap: Bitmap) {
        if (isIdentity) return

        val lutR = buildLut(0)
        val lutG = buildLut(1)
        val lutB = buildLut(2)

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = lutR[(p shr 16) and 0xFF]
            val g = lutG[(p shr 8) and 0xFF]
            val b = lutB[p and 0xFF]
            pixels[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /**
     * 逐通道 256 项查找表：解码 -> 线性光里减黑电平、乘增益 -> 重新编码。
     * 整条链路只在建表时算幂，逐像素时就是数组查表，开销可以忽略。
     */
    private fun buildLut(channel: Int): IntArray {
        val blackLin = toLinear(blackAt(channel))
        val gain = gains[channel]
        val lut = IntArray(256)
        for (v in 0..255) {
            val linear = toLinear(v.toFloat())
            val corrected = ((linear - blackLin) * gain).coerceIn(0f, 1f)
            lut[v] = (toEncoded(corrected) * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return lut
    }

    private fun toLinear(v: Float): Float =
        (v.coerceIn(0f, 255f) / 255f).pow(GAMMA)

    private fun toEncoded(linear: Float): Float =
        linear.coerceIn(0f, 1f).pow(1f / GAMMA)

    /** 预览退化路径（API 31~32）用的伽马空间近似增益 */
    private val gammaGains: FloatArray by lazy {
        val w = white ?: return@lazy floatArrayOf(1f, 1f, 1f)
        if (w.size < 3) return@lazy floatArrayOf(1f, 1f, 1f)
        val y = 0.299f * w[0] + 0.587f * w[1] + 0.114f * w[2]
        FloatArray(3) { i ->
            val span = w[i] - blackAt(i)
            if (span < 8f) 1f else (y / span).coerceIn(0.2f, 5f)
        }
    }

    /**
     * 同一条公式的 4x5 色彩矩阵版本，给预览层用：
     *   out = gain * in - gain * black
     * ColorMatrix 的偏移量是 0..255 量纲，正好对上。
     */
    fun toColorMatrix(): ColorMatrix {
        val values = FloatArray(20)
        values[0] = gammaGains[0]
        values[4] = -blackAt(0) * gammaGains[0]
        values[6] = gammaGains[1]
        values[9] = -blackAt(1) * gammaGains[1]
        values[12] = gammaGains[2]
        values[14] = -blackAt(2) * gammaGains[2]
        values[18] = 1f
        return ColorMatrix(values)
    }

    fun summary(): String {
        val sb = StringBuilder()
        if (dark != null) {
            sb.append("暗场 ").append(dark.joinToString("/") { String.format("%.1f", it) })
        } else {
            sb.append("暗场 未校准")
        }
        sb.append("    ")
        if (white != null) {
            sb.append("中性面 ").append(white.joinToString("/") { String.format("%.0f", it) })
                .append("    ")
            sb.append("增益 ").append(gains.joinToString("/") { String.format("%.2f", it) })
        } else {
            sb.append("中性面 未校准")
        }
        return sb.toString()
    }

    fun copy(dark: FloatArray? = this.dark, white: FloatArray? = this.white) =
        ColorCalibration(dark, white)

    companion object {
        /** 近似 sRGB 的伽马指数（比逐段 sRGB 曲线便宜，误差可以忽略） */
        private const val GAMMA = 2.2f
    }
}

/** 校准数据的持久化 */
object CalibrationStore {

    private const val PREF = "color_calibration"
    private const val KEY_DARK = "dark"
    private const val KEY_WHITE = "white"
    private const val KEY_AWB = "awb_mode"

    fun load(context: Context): ColorCalibration? {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val dark = prefs.getString(KEY_DARK, null)?.let { parse(it) }
        val white = prefs.getString(KEY_WHITE, null)?.let { parse(it) }
        if (dark == null && white == null) return null
        return ColorCalibration(dark, white)
    }

    fun save(context: Context, calibration: ColorCalibration) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        if (calibration.dark != null) {
            prefs.putString(KEY_DARK, calibration.dark.joinToString(","))
        } else {
            prefs.remove(KEY_DARK)
        }
        if (calibration.white != null) {
            prefs.putString(KEY_WHITE, calibration.white.joinToString(","))
        } else {
            prefs.remove(KEY_WHITE)
        }
        prefs.apply()
    }

    /**
     * 白卡校准时的 AWB 档位。必须固定下来：
     * 如果测量时 AWB 还在自动漂，存下的固定增益下一张就对不上了。
     */
    fun loadAwbMode(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_AWB)) prefs.getInt(KEY_AWB, 0) else null
    }

    fun saveAwbMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY_AWB, mode).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun parse(raw: String): FloatArray? {
        val parts = raw.split(",").mapNotNull { it.toFloatOrNull() }
        return if (parts.size == 3) parts.toFloatArray() else null
    }
}
