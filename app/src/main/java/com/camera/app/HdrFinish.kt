package com.camera.app

import kotlin.random.Random

/**
 * 单帧路径的收尾：只保留抖动。
 *
 * 局部影调 / 色度降噪 / 锐化 / 色差校正 / 色相微调**全部**在 native 的
 * hdr_finish.cpp 里做——那边有并行和 NEON，而且 HDR 只能走 native。
 * 这里留着的是 Kotlin 退路（native 不可用时）唯一需要的一步：
 * 8bit 量化前加抖动，避免平滑渐变出现等高线。
 */
object HdrFinish {

/**
     * 输出前的抖动（dither）。
     *
     * 8bit 只有 256 级，强提亮之后的平滑渐变（桌面、墙面、暗部天空）很容易被量化成
     * 一圈圈等高线。惯例做法是在量化前加半个 LSB 的噪声，把"台阶"打散成看不见的颗粒。
     * 这里在已经量化的结果上做：随机让 1/4 像素 +1、1/4 像素 -1，
     * 等效于把量化误差白化成噪声，肉眼看不见，但等高线消失了。
     */
    fun dither(out: IntArray, w: Int, h: Int) {
        RawThreads.run(h) { _, y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                var seed = y * 1103515245 + 12345
                for (x in 0 until w) {
                    seed = seed * 1103515245 + 12345
                    val u = (seed ushr 18) and 0xFF
                    val d = when {
                        u < 64 -> -1
                        u >= 192 -> 1
                        else -> 0
                    }
                    // 色度单独抖一次：只给三通道加同一个 d 等于只抖亮度，
                    // 色度会留下一片片同值平台（实测最长 140 像素），
                    // 在平滑渐变上就是一圈圈彩色等高线。R 加、B 减，亮度几乎不变。
                    seed = seed * 1103515245 + 12345
                    val u2 = (seed ushr 18) and 0xFF
                    val dc = when {
                        u2 < 48 -> -1
                        u2 >= 208 -> 1
                        else -> 0
                    }
                    if (d != 0 || dc != 0) {
                        val px = out[row + x]
                        val r = (((px shr 16) and 0xFF) + d + dc).coerceIn(0, 255)
                        val g = (((px shr 8) and 0xFF) + d).coerceIn(0, 255)
                        val b = ((px and 0xFF) + d - dc).coerceIn(0, 255)
                        out[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }
    }
}
