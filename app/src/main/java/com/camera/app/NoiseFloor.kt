package com.camera.app

import kotlin.math.min

/**
 * 估算单张 RAW 的读出噪声，用来定"噪声地板"。
 *
 * 为什么需要：只减传感器黑电平是不够的。黑电平只保证噪声以 0 为中心，
 * 但归一化时要 clamp 到 0，负半边被切掉 → 噪声被"整流"，均值从 0 抬到约 +5 DN。
 * 之后白平衡增益（这台机器 R 1.82 / B 2.17）和曝光系数（最高 ×2.2）一路放大，
 * 遮住镜头拍出来就是明显的紫红底（实测 R43/G12/B44）。
 *
 * 做法：减去 k·σ 的噪声地板，让噪声整体落到 0 以下再被 clamp 掉。
 *
 * σ 的估法：同一 CFA 相位、相隔 4 像素的两点做差，取 |d| 的中位数。
 *   - 同相位保证比的是同一颜色通道
 *   - 中位数对真实边缘（占少数）不敏感，不会被纹理带偏
 *   - 两点独立同分布时 |d| 的中位数 = 0.954·σ（√2σ 分布的 50% 分位），所以要除回去
 */
object NoiseFloor {

    /**
     * 总开关。
     *
     * 目前已**关闭**：2σ 地板要一次性压掉 25 DN（σ=12.6），换算到线性是 0.026，
     * 相当于把 sRGB 51 以下的暗部整段归零——1/3 的码值区间没了。
     * 暗场确实干净了，但代价是暗部细节明显变差，而且噪点被"削平"得很不自然。
     * 权衡下来不值得：暗场发紫只是不好看，暗部细节丢失是信息损失。
     *
     * 保留实现是为了以后能按 ISO 自适应地开（低 ISO 用 1.5σ，高 ISO 才用 2σ），
     * 或者改成软膝（soft knee）而不是硬减。
     */
    const val ENABLED = false

    private const val TAG = "NoiseFloor"

    /** 取几倍 σ 当噪声地板。2σ 能压掉约 95% 的噪声，同时不至于吃掉真实暗部 */
    private const val K_SIGMA = 2.0f

    /** 地板最多占满量程的比例，防止极高 ISO 下把暗部整段抹平 */
    private const val MAX_FRACTION = 0.05f

    /**
     * @return 需要减掉的 DN 值（0 表示几乎没噪声，不用减）
     */
    fun pedestal(raw: ShortArray, w: Int, h: Int, white: Float, black: Int): Float {
        val sigma = sigma(raw, w, h)
        if (sigma <= 0.5f) return 0f
        val cap = (white - black) * MAX_FRACTION
        val p = min(K_SIGMA * sigma, cap)
        return p.coerceAtLeast(0f)
    }

    /** 读出噪声 σ（单位 DN） */
    fun sigma(raw: ShortArray, w: Int, h: Int): Float {
        if (w < 16 || h < 16) return 0f
        val step = 4
        val stride = 8
        val hist = IntArray(256)
        var count = 0
        var y = 0
        while (y < h) {
            val row = y * w
            var x = 0
            while (x + step < w) {
                val a = raw[row + x].toInt() and 0xFFFF
                val b = raw[row + x + step].toInt() and 0xFFFF
                var d = a - b
                if (d < 0) d = -d
                hist[if (d > 255) 255 else d]++
                count++
                x += stride
            }
            y += stride
        }
        if (count < 100) return 0f

        // 中位数（>255 的样本都落在最后一格，暗场里几乎不会出现，影响可忽略）
        val half = count / 2
        var acc = 0
        var med = 0
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= half) {
                med = i
                break
            }
        }
        return med / 0.954f
    }
}
