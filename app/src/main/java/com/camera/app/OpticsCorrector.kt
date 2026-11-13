package com.camera.app

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import java.util.concurrent.Executors

/**
 * 前端光学校正：暗角（lens shading）+ 坏点/热像素。
 *
 * 为什么这一层对"真实"重要：
 *   暗角不是真实的亮度分布，是镜头 + 微透镜的余弦四次方衰减；边缘比中心暗 20~40% 很常见。
 *   热像素也不是真实的细节，是硅片上的缺陷点，在暗部会变成一颗颗彩色亮点。
 *   ISP 出 JPEG 时这两件事它自己会做，但我们绕开 ISP 走 RAW，就必须自己补上，
 *   否则 RAW 路线反而比 JPEG "更不真实"。
 *
 * 暗角用相机上报的 shading map（每组 4 个通道对应 4 个 CFA 相位），是这台机器实测的标定数据，
 * 比任何经验公式都准；坏点用"同色邻居中值 + 极值判定"，不需要预先标定坏点表。
 */
object OpticsCorrector {

    private const val TAG = "OpticsCorrector"

    /**
     * 和邻居偏离超过满量程的这个比例才算坏点。
     * 取 8%：真正的坏点/热像素通常差得远（要么饱和要么贴底），
     * 而暗部噪声在 3% 附近就会有一大批尾巴，阈值太低会把噪声当坏点修掉——那就成了偷偷降噪。
     */
    private const val DEFECT_RANGE_RATIO = 0.08f

    /** 最小判定阈值（DN），避免暗部噪声被误判 */
    private const val DEFECT_MIN_DN = 40f

    /** 相机上报的暗角增益图 */
    class LensShading(
        val width: Int,
        val height: Int,
        /** 长度 = width*height*4，顺序是 (y,x) 四点一组，组内按 CFA 相位 R/Gr/Gb/B */
        val gains: FloatArray,
        /** 4 个 Bayer 位置各自对应哪个 shading 通道；下标是 ((y&1)<<1)|(x&1) */
        val channelOf: IntArray
    )

    /**
     * 从当次 CaptureResult 取 shading map（`STATISTICS_LENS_SHADING_CORRECTION_MAP`）。
     * 读不到、或者增益全都约等于 1（说明这台上报了个空表）就返回 null，
     * 保证不会把画面搞乱。
     */
    fun buildShading(
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult?
    ): LensShading? {
        val map = try {
            result?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        } catch (e: Exception) {
            Log.w(TAG, "读取 shading map 失败: ${e.message}")
            null
        } ?: return null

        val rows = map.rowCount
        val cols = map.columnCount
        if (rows < 2 || cols < 2) return null

        // 一次性拷贝全部增益，顺序就是 row-major、每像素 4 个通道（R, Gr, Gb, B）。
        // 不用逐点 getGainFactor：个别厂商实现对参数校验不一致，会直接抛异常。
        val gains = try {
            val count = map.gainFactorCount
            if (count < cols * rows * 4) return null
            FloatArray(count).also { map.copyGainFactors(it, 0) }
        } catch (e: Exception) {
            Log.w(TAG, "复制 shading 增益失败: ${e.message}")
            return null
        }

        var max = 0f
        var min = Float.MAX_VALUE
        for (g in gains) {
            if (g > max) max = g
            if (g < min) min = g
        }
        // 增益太平（几乎都是 1）说明这台上报的不是实测标定，补了也没意义
        if (max <= 1.02f) {
            Log.d(TAG, "shading map ${cols}x${rows} 增益平淡（max %.3f），跳过".format(max))
            return null
        }

        Log.d(
            TAG,
            "shading map ${cols}x${rows} 增益 %.3f~%.3f".format(min, max)
        )
        return LensShading(cols, rows, gains, channelMap(characteristics))
    }

    /**
     * shading map 的 4 个通道固定是 R / Gr / Gb / B，
     * 但 Bayer 阵列里哪个位置是 R、哪个是 B 取决于 COLOR_FILTER_ARRANGEMENT。
     * 这张机器是 BGGR，如果直接按 (0,0)=R 去取增益，四角会拿到红蓝反了的补偿，出现边缘偏色。
     */
    private fun channelMap(characteristics: CameraCharacteristics): IntArray {
        val arrangement = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
        ) ?: android.hardware.camera2.CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB

        // 每个位置是什么颜色：'R' / 'B' / 'G'
        val pattern: String = when (arrangement) {
            android.hardware.camera2.CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
            android.hardware.camera2.CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
            android.hardware.camera2.CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
            android.hardware.camera2.CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
            else -> "RGGB"
        }
        val map = IntArray(4)
        for (dy in 0..1) {
            for (dx in 0..1) {
                val c = pattern[dy * 2 + dx]
                map[(dy shl 1) or dx] = when (c) {
                    'R' -> 0
                    'B' -> 3
                    // 绿通道按行奇偶区分：偶数行是 Gr(1)，奇数行是 Gb(2)
                    else -> if (dy == 0) 1 else 2
                }
            }
        }
        return map
    }

    /**
     * 原地把暗角增益乘回去（在去马赛克之前、Bayer 域做，物理上正确的位置）。
     *
     * 注意要在减完黑电平的域里乘：raw' = black + (raw - black) * gain，
     * 否则黑电平会被一起放大，暗部整体被抬起来。
     */
    fun applyShading(
        raw: ShortArray,
        width: Int,
        height: Int,
        black: IntArray,
        shading: LensShading?
    ) {
        if (shading == null) return
        val mw = shading.width
        val mh = shading.height
        if (mw < 2 || mh < 2) return
        val gains = shading.gains
        val channelOf = shading.channelOf
        if (gains.size < mw * mh * 4 || channelOf.size < 4) return

        // 预先把 x / y 的网格索引和权重算好，逐像素只剩 4 次查表 + 插值
        val x0 = IntArray(width)
        val x1 = IntArray(width)
        val fx = FloatArray(width)
        for (x in 0 until width) {
            val p = if (width > 1) x.toFloat() / (width - 1) * (mw - 1) else 0f
            val i = p.toInt().coerceIn(0, mw - 1)
            x0[x] = i
            x1[x] = (i + 1).coerceAtMost(mw - 1)
            fx[x] = p - i
        }
        val y0 = IntArray(height)
        val y1 = IntArray(height)
        val fy = FloatArray(height)
        for (y in 0 until height) {
            val p = if (height > 1) y.toFloat() / (height - 1) * (mh - 1) else 0f
            val i = p.toInt().coerceIn(0, mh - 1)
            y0[y] = i
            y1[y] = (i + 1).coerceAtMost(mh - 1)
            fy[y] = p - i
        }

        RawThreads.run(height) { _, ys, ye ->
            for (y in ys until ye) {
                val rowBase = y * width
                val iy0 = y0[y] * mw
                val iy1 = y1[y] * mw
                val wy = fy[y]
                for (x in 0 until width) {
                    // 黑电平按 Bayer 位置索引，暗角增益按"这个位置是什么颜色"索引，两者不是一回事
                    val pos = ((y and 1) shl 1) or (x and 1)
                    val ch = channelOf[pos]
                    val ix0 = x0[x]
                    val ix1 = x1[x]
                    val wx = fx[x]

                    val g00 = gains[(iy0 + ix0) * 4 + ch]
                    val g10 = gains[(iy0 + ix1) * 4 + ch]
                    val g01 = gains[(iy1 + ix0) * 4 + ch]
                    val g11 = gains[(iy1 + ix1) * 4 + ch]
                    val g0 = g00 + (g10 - g00) * wx
                    val g1 = g01 + (g11 - g01) * wx
                    val gain = g0 + (g1 - g0) * wy

                    val i = rowBase + x
                    val v = raw[i].toInt() and 0xFFFF
                    val blackHere = black[pos]
                    val corrected = blackHere + (v - blackHere) * gain
                    raw[i] = corrected.toInt().coerceIn(0, 65535).toShort()
                }
            }
        }
    }

    /**
     * 坏点 / 热像素修正。
     *
     * 只处理"和 4 个同色邻居差得离谱、而且自己是局部极值"的点——
     * 这样纹理和噪声不会被误伤（判断条件里"极值"必须同时成立）。
     * 返回修正的点数，方便确认到底有没有生效。
     */
    fun fixDefects(
        raw: ShortArray,
        width: Int,
        height: Int,
        black: IntArray,
        white: Float
    ): Int {
        // 优先走 native：12.6M 像素的纯规则运算，Kotlin 版实测 340ms（每像素约 80 周期，
        // 主要花在边界检查和 float 装箱），native 版约 40ms。算法逐位等价，见 raw_defect.cpp。
        if (NativeHdr.available) {
            val n = NativeHdr.nativeFixDefects(raw, width, height, black[0], white)
            if (n >= 0) return n
        }
        return fixDefectsKotlin(raw, width, height, black, white)
    }

    /** Kotlin 兜底实现：native 不可用（或参数不合法）时走这里。 */
    private fun fixDefectsKotlin(
        raw: ShortArray,
        width: Int,
        height: Int,
        black: IntArray,
        white: Float
    ): Int {
        if (width < 5 || height < 5) return 0
        val range = (white - black[0]).coerceAtLeast(1f)
        val threshold = (range * DEFECT_RANGE_RATIO).coerceAtLeast(DEFECT_MIN_DN)
        val perLane = IntArray(RawThreads.lanes)

        RawThreads.run(height) { lane, ys, ye ->
            var fixed = 0
            val yStart = if (ys < 2) 2 else ys
            val yEnd = if (ye > height - 2) height - 2 else ye
            for (y in yStart until yEnd) {
                val rowBase = y * width
                for (x in 2 until width - 2) {
                    val i = rowBase + x
                    val v = (raw[i].toInt() and 0xFFFF).toFloat()

                    // 同色邻居隔着 2 个像素，保证相位一致
                    val n0 = (raw[i - 2].toInt() and 0xFFFF).toFloat()
                    val n1 = (raw[i + 2].toInt() and 0xFFFF).toFloat()
                    val n2 = (raw[i - 2 * width].toInt() and 0xFFFF).toFloat()
                    val n3 = (raw[i + 2 * width].toInt() and 0xFFFF).toFloat()

                    val minN = kotlin.math.min(kotlin.math.min(n0, n1), kotlin.math.min(n2, n3))
                    val maxN = kotlin.math.max(kotlin.math.max(n0, n1), kotlin.math.max(n2, n3))

                    // 只有"是极值 + 离中值足够远"才动，宁漏不错
                    if (v <= maxN && v >= minN) continue
                    val a = kotlin.math.min(n0, n1)
                    val b = kotlin.math.max(n0, n1)
                    val c = kotlin.math.min(n2, n3)
                    val d = kotlin.math.max(n2, n3)
                    val median = (kotlin.math.max(a, c) + kotlin.math.min(b, d)) * 0.5f

                    if (kotlin.math.abs(v - median) > threshold) {
                        raw[i] = median.toInt().coerceIn(0, 65535).toShort()
                        fixed++
                    }
                }
            }
            perLane[lane] = fixed
        }

        var total = 0
        for (n in perLane) total += n
        if (total > 0) Log.d(TAG, "坏点修正 $total 个（阈值 %.0f DN）".format(threshold))
        return total
    }
}

/**
 * RAW 管线共用的小线程池。
 * 去马赛克、暗角、坏点修正都是按行独立处理的，切段并行即可，不需要任何锁。
 */
internal object RawThreads {

    val lanes: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)

    private val pool = Executors.newFixedThreadPool(lanes) { r ->
        Thread(r, "raw-pipe").apply { isDaemon = true }
    }

    fun run(height: Int, body: (lane: Int, y0: Int, y1: Int) -> Unit) {
        if (lanes <= 1 || height < 128) {
            body(0, 0, height)
            return
        }
        val laneHeight = (height + lanes - 1) / lanes
        val futures = ArrayList<java.util.concurrent.Future<*>?>(lanes)
        for (i in 0 until lanes) {
            val y0 = i * laneHeight
            val y1 = kotlin.math.min(height, y0 + laneHeight)
            if (y0 >= y1) break
            val lane = i
            futures.add(pool.submit(Runnable { body(lane, y0, y1) }))
        }
        for (f in futures) {
            f?.get()
        }
    }
}
