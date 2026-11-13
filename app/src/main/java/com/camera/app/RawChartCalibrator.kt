package com.camera.app

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.util.Log
import java.nio.ByteOrder

/**
 * 用 RAW 标定色卡 —— 这是唯一"准"的路径。
 *
 * 为什么不能用 JPEG：色卡标定要的是**传感器原始响应**。
 * JPEG 走完 ISP，去马赛克（AHD/双线性）、降噪、锐化、饱和度提升、S 形色调曲线全都加上了，
 * 高饱和色还可能已经削顶。拿它解出来的矩阵只能算"对 ISP 输出的二次纠偏"，
 * 换一台机器、换一版固件就失效，也无法解释。
 *
 * RAW 路径做的事：
 *   1. 直接读 RAW16（不经过任何 ISP 处理）
 *   2. 减黑电平、按白电平归一化、坏点修正、暗角补偿 —— 与 RawToBitmap 完全同一套
 *   3. 从 RAW 现造一张灰度位图给自动识别用（2x2 合并），拿到 24 格网格
 *   4. **不去马赛克**：色块是均匀面，直接在 Bayer 域分别统计 R / Gr / Gb / B 的中位数，
 *      比先插值再取平均更准，也更快
 *   5. 乘白平衡增益（用当次采集的 SENSOR_NEUTRAL_COLOR_POINT 反算），
 *      与管线里的顺序一致：WB 在矩阵之前
 *   6. 解 3x3：sensor 线性 → sRGB 线性（复用 ColorChecker.solveLinear，含曝光对齐）
 *
 * 产出的矩阵直接可以用在 RawToBitmap 的线性段，替换掉相机自带的 ISP 矩阵。
 */
object RawChartCalibrator {

    private const val TAG = "RawChartCalib"

    private const val DEBUG_GRID = true

    /** 对照实验：同一次采集里分别关掉暗角/坏点，看 ΔE 变化 */
    private const val DEBUG_COMPARE = false

    /** 出问题时才会用到：把识别源和中心点导出到存储（每张 ~30MB，平时关掉） */
    private const val DEBUG_DUMP = false

    class Result(
        val matrix: FloatArray,
        val meanBefore: Float,
        val meanAfter: Float,
        val maxBefore: Float,
        val maxAfter: Float,
        val perPatchAfter: FloatArray,
        val detected: Int,
        val gainR: Float,
        val gainG: Float,
        val gainB: Float,
        val clipped: Int
    )

    fun calibrate(
        image: Image,
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult?,
        /** 同一帧的 YUV 亮度图（传感器方向，半分辨率）。有就优先用它做识别 */
        luma: Bitmap? = null,
        /** 调试用：把识别结果画到这张灰度图上存出来，方便离线调参 */
        debugDir: java.io.File? = null
    ): Result? {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return null

        val raw = readRaw16(image) ?: return null
        // 对照实验用：留一份完全未处理的原始数据
        val pristine = if (DEBUG_COMPARE) raw.copyOf() else null

        val blackPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val black = IntArray(4) { i ->
            try {
                blackPattern?.getOffsetForIndex(i and 1, i shr 1) ?: 64
            } catch (e: Exception) {
                64
            }
        }
        val whiteLevel: Int = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val white = whiteLevel.toFloat()
        val scale = 1f / (white - black[0]).coerceAtLeast(1f)

        // 与出片管线用同一个噪声地板，否则矩阵拟合的空间和用它的空间不一致
        val pedestal = if (NoiseFloor.ENABLED) NoiseFloor.pedestal(raw, w, h, white, black[0]) else 0f

        // 与 RawToBitmap 相同的顺序：先修坏点，再补暗角
        OpticsCorrector.fixDefects(raw, w, h, black, white)
        val shading = OpticsCorrector.buildShading(characteristics, result)
        OpticsCorrector.applyShading(raw, w, h, black, shading)

        // 白平衡增益：和管线一样从 NEUTRAL_COLOR_POINT 反算
        val gains = wbGains(result)

        // CFA 相位 -> R/G/B 的映射，灰度图和采样都要用，提前算好
        val cfaName = cfaNameOf(characteristics)
        val cfa = RcdDemosaic.Cfa.of(cfaName)
        val colors = cfa.colors()   // [相位0..3] -> R/G/B
        Log.d(TAG, "CFA=$cfaName 相位映射=${colors.joinToString(",")}")

        // ---- 1. 准备一张"给识别用"的图 ----
        // 首选同帧 YUV 的亮度：ISP 处理过，对比度正常，而且和 RAW 同方向、同视野，
        // 认出网格后坐标只要按比例缩放就能落到 RAW 上，不用做旋转映射。
        //
        // 兜底才用自研管线解出来的预览图——但它太"平"，色块之间的黑缝在工作分辨率下
        // 只剩 4-5 像素，被 3x3 模糊抹平后 24 格会连成一片（实测只剩 7-9 个连通域）。
        val detectBmp: Bitmap
        val useYuv = luma != null
        if (luma != null) {
            detectBmp = luma
        } else {
            val preview = RawToBitmap.convert(image, characteristics, result, shading)
            if (preview == null) {
                Log.w(TAG, "RAW 预览解码失败")
                return null
            }
            detectBmp = preview
        }

        // ---- 2. 自动找网格 ----
        val grids = ChartAutoDetector.detectCandidates(detectBmp)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        if (debugDir != null && DEBUG_DUMP) {
            try {
                val vis = detectBmp.copy(Bitmap.Config.ARGB_8888, true) ?: detectBmp
                val canvas = android.graphics.Canvas(vis)
                val paint = android.graphics.Paint().apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 6f
                    color = 0xFF00FF00.toInt()
                }
                for (grid in grids) {
                    for (k in 0 until 24) {
                        val cx = grid.centers[k * 2] * vis.width
                        val cy = grid.centers[k * 2 + 1] * vis.height
                        val hx = grid.cellW * vis.width * 0.26f
                        val hy = grid.cellH * vis.height * 0.26f
                        canvas.drawRect(cx - hx, cy - hy, cx + hx, cy + hy, paint)
                    }
                    break
                }
                val out = java.io.File(debugDir, "raw_debug.png")
                java.io.FileOutputStream(out).use { fos ->
                    vis.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                java.io.File(debugDir, "raw_preview.png").let { clean ->
                    java.io.FileOutputStream(clean).use { fos ->
                        detectBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                }
                java.io.File(debugDir, "raw_centers.txt").writeText(
                    grids.joinToString("\n") { g ->
                        "grid cellW=%.5f cellH=%.5f detected=%d\n".format(g.cellW, g.cellH, g.detected) +
                            (0 until 24).joinToString(" ") {
                                "%.5f,%.5f".format(g.centers[it * 2], g.centers[it * 2 + 1])
                            }
                    }
                )
                if (vis !== detectBmp) vis.recycle()
                Log.d(TAG, "调试图: ${out.absolutePath}（识别源=%s）".format(if (useYuv) "YUV" else "自研预览"))
            } catch (e: Exception) {
                Log.w(TAG, "调试图保存失败: ${e.message}")
            }
        }
        if (!useYuv) detectBmp.recycle()

        if (grids.isEmpty()) {
            Log.w(TAG, "RAW 上没找到色卡: ${ChartAutoDetector.lastDiagnostic}")
            return null
        }

        val rawW = w
        val rawH = h

        // YUV 与 RAW 同方向同视野，归一化坐标乘上 RAW 尺寸就是 RAW 像素坐标。
        // 兜底路径（自研预览）转过 SENSOR_ORIENTATION，要反着转回去。
        fun toRaw(nx: Float, ny: Float): FloatArray {
            if (useYuv) return floatArrayOf(nx * rawW, ny * rawH)
            val rotatedW = if (sensorOrientation == 90 || sensorOrientation == 270) rawH else rawW
            val rotatedH = if (sensorOrientation == 90 || sensorOrientation == 270) rawW else rawH
            val x = nx * rotatedW
            val y = ny * rotatedH
            return when (sensorOrientation) {
                90 -> floatArrayOf(y, rawH - x)
                180 -> floatArrayOf(rawW - x, rawH - y)
                270 -> floatArrayOf(rawW - y, x)
                else -> floatArrayOf(x, y)
            }
        }
        val pitchRefW = if (useYuv) rawW else
            (if (sensorOrientation == 90 || sensorOrientation == 270) rawH else rawW)

        // ---- 3. 逐格在 Bayer 域采样 + 解矩阵 ----
        Log.d(TAG, "候选网格 ${grids.size} 个")

        if (DEBUG_GRID) {
            for ((gi2, g2) in grids.withIndex()) {
                val zb = g2.centers.indices.step(2).count {
                    g2.centers[it] < 0f || g2.centers[it] > 1f
                }
                Log.d(
                    TAG,
                    "网格#$gi2 detected=${g2.detected} cellW=%.4f cellH=%.4f 越界轴数=$zb 首点=(%.3f,%.3f)".format(
                        g2.cellW, g2.cellH, g2.centers[0], g2.centers[1]
                    )
                )
            }
        }

        var best: Result? = null
        var bestGrid: ChartAutoDetector.Grid? = null
        for ((index, grid) in grids.withIndex()) {
            val cellPitch = grid.cellW * pitchRefW
            val sampled = samplePatches(
                raw, w, h, black, scale, grid, colors, white, ::toRaw, cellPitch, pedestal
            )
            if (sampled == null) {
                Log.w(TAG, "候选#$index: 采样失败（窗口越界或相位缺数）")
                continue
            }
            val linear = sampled.first
            val clipped = sampled.second

            Log.d(
                TAG,
                "候选#$index: %d 块落点，窗 %dpx，前3格 R/G/B = %s".format(
                    grid.detected,
                    (grid.cellW * pitchRefW * 0.52f).toInt(),
                    (0 until 3).joinToString(" | ") { i ->
                        "%.3f/%.3f/%.3f".format(linear[i][0], linear[i][1], linear[i][2])
                    }
                )
            )

            // 必须在**乘过白平衡增益**的数据上拟合：管线里的顺序就是 WB -> 矩阵。
            // 这样拟合出来的矩阵对任何光源都成立（WB 归一化之后，中性面在任何光下都是 1,1,1），
            // 也正是 DNG ColorMatrix 的定义方式。
            val wbApplied = Array(ColorChecker.COUNT) { i ->
                floatArrayOf(
                    linear[i][0] * gains[0],
                    linear[i][1] * gains[1],
                    linear[i][2] * gains[2]
                )
            }
            // RAW→sRGB 的矩阵元素可以比 ISP 那套大得多（尤其 WB 增益大的通道），
            // 这里把闸门放宽，否则会把正确答案判成"病态矩阵"
            val solved = ColorChecker.solveBestOrientationLinear(
                wbApplied, grid.rows, grid.cols, maxElement = 4.5f, minDiagonal = 0.12f
            )
            if (solved == null) {
                Log.w(TAG, "候选#$index: 解矩阵失败（矩阵超出闸门或数值退化）")
                continue
            }

            Log.d(
                TAG,
                "候选#$index: ${grid.detected} 块，ΔE %.1f → %.1f".format(
                    solved.meanBefore, solved.meanAfter
                )
            )
            val out = Result(
                solved.matrix,
                solved.meanBefore,
                solved.meanAfter,
                solved.maxBefore,
                solved.maxAfter,
                solved.perPatchAfter,
                grid.detected,
                gains[0],
                gains[1],
                gains[2],
                clipped
            )
            if (best == null || out.meanAfter < best.meanAfter) {
                best = out
                bestGrid = grid
            }
        }

        best?.let {
            Log.d(
                TAG,
                "RAW 标定完成：ΔE %.1f → %.1f，%d 块，削顶 %d".format(
                    it.meanBefore, it.meanAfter, it.detected, it.clipped
                )
            )
        }

        // ---- 对照实验：预处理各步骤对 ΔE 的影响 ----
        // 只看同一张 RAW，分别关掉暗角/坏点，看结果差别有多大
        val cmpGrid = bestGrid ?: grids.firstOrNull()
        if (DEBUG_COMPARE && pristine != null && cmpGrid != null) {
            val grid = cmpGrid
            val cellPitch = grid.cellW * pitchRefW
            val scratch = pristine.copyOf()

            fun evalVariant(name: String, doseDefect: Boolean, doseShade: Boolean) {
                pristine.copyInto(scratch)
                if (doseDefect) OpticsCorrector.fixDefects(scratch, w, h, black, white)
                if (doseShade) OpticsCorrector.applyShading(scratch, w, h, black, shading)
                val sm = samplePatches(
                    scratch, w, h, black, scale, grid, colors, white, ::toRaw, cellPitch, pedestal
                ) ?: run {
                    Log.d(TAG, "对比[$name]: 采样失败")
                    return
                }
                val wb = Array(ColorChecker.COUNT) { i ->
                    floatArrayOf(
                        sm.first[i][0] * gains[0],
                        sm.first[i][1] * gains[1],
                        sm.first[i][2] * gains[2]
                    )
                }
                val r = ColorChecker.solveBestOrientationLinear(
                    wb, grid.rows, grid.cols, 4.5f, 0.12f
                ) ?: run {
                    Log.d(TAG, "对比[$name]: 解矩阵失败")
                    return
                }
                Log.d(
                    TAG,
                    "对比[%s]: ΔE %.1f → %.2f  矩阵行和 %.2f/%.2f/%.2f".format(
                        name, r.meanBefore, r.meanAfter,
                        r.matrix[0] + r.matrix[1] + r.matrix[2],
                        r.matrix[3] + r.matrix[4] + r.matrix[5],
                        r.matrix[6] + r.matrix[7] + r.matrix[8]
                    )
                )
            }

            evalVariant("①全开(现状)", true, true)
            evalVariant("②不做暗角", true, false)
            evalVariant("③不做坏点", false, true)
            evalVariant("④都不做", false, false)

            // ⑤ 不减黑电平：把 black 当 0，验证这一步到底有多关键
            run {
                pristine.copyInto(scratch)
                OpticsCorrector.fixDefects(scratch, w, h, black, white)
                OpticsCorrector.applyShading(scratch, w, h, black, shading)
                val black0 = intArrayOf(0, 0, 0, 0)
                val sm = samplePatches(
                    scratch, w, h, black0, 1f / white, grid, colors, white, ::toRaw, cellPitch, 0f
                )
                if (sm == null) {
                    Log.d(TAG, "对比[⑤不减黑电平]: 采样失败")
                    return@run
                }
                val wb = Array(ColorChecker.COUNT) { i ->
                    floatArrayOf(
                        sm.first[i][0] * gains[0],
                        sm.first[i][1] * gains[1],
                        sm.first[i][2] * gains[2]
                    )
                }
                val r = ColorChecker.solveBestOrientationLinear(
                    wb, grid.rows, grid.cols, 4.5f, 0.12f
                )
                if (r != null) {
                    Log.d(
                        TAG,
                        "对比[⑤不减黑电平]: ΔE %.1f → %.2f  矩阵行和 %.2f/%.2f/%.2f".format(
                            r.meanBefore, r.meanAfter,
                            r.matrix[0] + r.matrix[1] + r.matrix[2],
                            r.matrix[3] + r.matrix[4] + r.matrix[5],
                            r.matrix[6] + r.matrix[7] + r.matrix[8]
                        )
                    )
                } else {
                    Log.d(TAG, "对比[⑤不减黑电平]: 解矩阵失败")
                }
            }
        }

        return best
    }

    /**
     * 逐格采样。色块是均匀面，不需要去马赛克：
     * 每个格子内按 CFA 相位分成 4 组，各取中位数，再按排列合并成 R/G/B。
     *
     * @return (24 组线性值, 削顶像素数)
     */
    private fun samplePatches(
        raw: ShortArray,
        w: Int,
        h: Int,
        black: IntArray,
        scale: Float,
        grid: ChartAutoDetector.Grid,
        cfaColors: IntArray,
        white: Float,
        toRaw: (Float, Float) -> FloatArray,
        cellPitch: Float,
        pedestal: Float
    ): Pair<Array<FloatArray>, Int>? {
        val half = (cellPitch * 0.26f).toInt().coerceAtLeast(3)

        val out = Array(ColorChecker.COUNT) { FloatArray(3) }
        var clipped = 0

        for (k in 0 until ColorChecker.COUNT) {
            val p = toRaw(grid.centers[k * 2], grid.centers[k * 2 + 1])
            val cx = p[0].toInt()
            val cy = p[1].toInt()
            val x0 = (cx - half).coerceIn(0, w - 1)
            val x1 = (cx + half).coerceIn(0, w - 1)
            val y0 = (cy - half).coerceIn(0, h - 1)
            val y1 = (cy + half).coerceIn(0, h - 1)
            // 贴着画面边缘的格子会被夹取，只要还剩下足够大的窗口就继续；
            // 小于一半说明这一格基本在画面外，那这次网格就是错的
            if (x1 - x0 < half || y1 - y0 < half || x1 - x0 < 4 || y1 - y0 < 4) {
                Log.w(TAG, "第 $k 格窗口越界: (%.2f,%.2f) 半宽 $half".format(
                    grid.centers[k * 2], grid.centers[k * 2 + 1]
                ))
                return null
            }

            // 4 个相位各一份直方图（DN 值 0..whiteLevel）
            val bins = (white.toInt() + 1).coerceAtMost(65536)
            val hist = Array(4) { IntArray(bins) }
            val counts = IntArray(4)
            for (y in y0..y1) {
                val row = y * w
                val parity = (y and 1) shl 1
                for (x in x0..x1) {
                    val phase = parity or (x and 1)
                    val v = raw[row + x].toInt() and 0xFFFF
                    if (v >= white - 1) clipped++
                    val bin = v.coerceIn(0, bins - 1)
                    hist[phase][bin]++
                    counts[phase]++
                }
            }

            // 每个相位取中位数
            val med = FloatArray(4)
            for (phase in 0 until 4) {
                if (counts[phase] == 0) return null
                med[phase] = medianOf(hist[phase], counts[phase])
            }

            // 相位 -> 颜色
            val r = FloatArray(2)
            val g = FloatArray(4)
            val b = FloatArray(2)
            var nc = 0
            var ng = 0
            var nr = 0
            var nb = 0
            for (phase in 0 until 4) {
                when (cfaColors[phase]) {
                    RcdDemosaic.R -> r[nr++] = med[phase]
                    RcdDemosaic.B -> b[nb++] = med[phase]
                    else -> g[ng++] = med[phase]
                }
                nc++
            }
            if (nr == 0 || nb == 0 || ng == 0) return null
            val gAvg = if (ng >= 2) (g[0] + g[1]) * 0.5f else g[0]

            out[k][0] = ((r[0] - black[indexOf(cfaColors, RcdDemosaic.R)] - pedestal) * scale)
                .coerceIn(0f, 1f)
            out[k][1] = ((gAvg - black[indexOf(cfaColors, -1)] - pedestal) * scale).coerceIn(0f, 1f)
            out[k][2] = ((b[0] - black[indexOf(cfaColors, RcdDemosaic.B)] - pedestal) * scale)
                .coerceIn(0f, 1f)
        }
        return out to clipped
    }

    private fun indexOf(colors: IntArray, color: Int): Int {
        for (i in colors.indices) {
            if (color == -1 && (colors[i] != RcdDemosaic.R && colors[i] != RcdDemosaic.B)) return i
            if (colors[i] == color) return i
        }
        return 0
    }

    private fun medianOf(hist: IntArray, total: Int): Float {
        val half = total / 2
        var acc = 0
        for (v in hist.indices) {
            acc += hist[v]
            if (acc >= half) return v.toFloat()
        }
        return 0f
    }

    private fun wbGains(result: TotalCaptureResult?): FloatArray {
        val neutral = result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        val n = FloatArray(3)
        for (i in 0..2) {
            n[i] = neutral?.getOrNull(i)?.let { it.numerator.toFloat() / it.denominator.toFloat() } ?: 0f
        }
        if (n[0] > 0.01f && n[1] > 0.01f && n[2] > 0.01f) {
            return floatArrayOf(1f / n[0], 1f / n[1], 1f / n[2])
        }
        val g = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        return floatArrayOf(
            g?.red ?: 1f,
            ((g?.greenEven ?: 1f) + (g?.greenOdd ?: 1f)) * 0.5f,
            g?.blue ?: 1f
        )
    }

    private fun cfaNameOf(characteristics: CameraCharacteristics): String =
        when (characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            android.hardware.camera2.CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
            android.hardware.camera2.CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
            android.hardware.camera2.CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
            else -> "RGGB"
        }

    private fun readRaw16(image: Image): ShortArray? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.order(ByteOrder.nativeOrder())
            val w = image.width
            val h = image.height
            val rowStride = plane.rowStride
            val out = ShortArray(w * h)

            if (rowStride == w * 2 && plane.pixelStride == 2) {
                buffer.asShortBuffer().get(out, 0, w * h)
            } else {
                val rowShorts = ShortArray(rowStride / 2)
                for (y in 0 until h) {
                    buffer.position(y * rowStride)
                    val slice = buffer.slice()
                    slice.order(ByteOrder.nativeOrder())
                    slice.asShortBuffer().get(rowShorts, 0, rowStride / 2)
                    System.arraycopy(rowShorts, 0, out, y * w, w)
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "读取 RAW16 失败", e)
            null
        }
    }
}
