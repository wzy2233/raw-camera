package com.camera.app

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 24 色卡的自动定位。返回**多个候选网格**，由调用方按 ΔE 挑选。
 *
 * 为什么返回多个：几何上会解出几个都"说得通"的网格（比如把对角方向当格距），
 * 光靠几何分不出来。但 24 个颜色是已知的——采样后解一次矩阵看 ΔE，错的那个会差到 30+，
 * 对的那个在 10 以内，一判就分开了。这是最强的验证信号，比调任何阈值都可靠。
 *
 * 走过的弯路（都写在代码里，免得再犯）：
 *  v1 靠亮块分割：暗色块和黑缝在亮度上分不开，阈值高低都不对
 *  v2 靠 24 条黑缝：任一条被摩尔纹/反光破坏就全线失败
 *  v3 靠黑色卡片轮廓：键盘+显示器边框+桌面连成一圈，"卡片"变成整个画面
 *  v4 靠最短的两个主方向：摩尔纹把色块切成两半后出现半格距，选错就废
 *  v5（当前）
 *    a) 面积收紧：24 个色块面积几乎一样（实测 1800~2600），键盘按键、窗口图标差一个数量级
 *    b) 所有够支持度的位移向量两两组合全试，只保留能解出完整 6x4 的
 *    c) 粗分 → 拟合透视 → 回代重分，吸收"斜拍导致格距不均"
 *    d) 交给调用方用参考色板验色
 */
object ChartAutoDetector {

    private const val MAX_SIDE = 480
    private const val MIN_VISIBLE = 8
    private const val MAX_CANDIDATES = 6

    class Grid(
        /** 归一化中心点 [x0,y0,...]，行优先，rows=4 cols=6 */
        val centers: FloatArray,
        val rows: Int,
        val cols: Int,
        /** 一个格子的归一化边长（相对图像宽/高） */
        val cellW: Float,
        val cellH: Float,
        /** 落在格点上的色块数（其余由网格推算） */
        val detected: Int
    ) {
        val cell: Float get() = if (cellW > cellH) cellW else cellH
    }

    var lastDiagnostic: String = ""
        private set

    /** 只要最好的一个（老接口） */
    fun detect(bitmap: Bitmap): Grid? = detectCandidates(bitmap).firstOrNull()

    /**
     * 返回若干候选网格，按"检出的色块数"从多到少。
     * 调用方应逐个采样解矩阵，取 ΔE 最小的那个——几何说不清的地方让颜色来判。
     */
    fun detectCandidates(bitmap: Bitmap): List<Grid> {
        val scale = maxOf(1, maxOf(bitmap.width, bitmap.height) / MAX_SIDE)
        val w = bitmap.width / scale
        val h = bitmap.height / scale
        if (w < 40 || h < 40) return emptyList()

        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        val luma = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            luma[i] = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        return detectFromLuma(luma, w, h)
    }

    /** 纯计算部分，脱离 Android 可测 */
    internal fun detectFromLuma(luma: IntArray, w: Int, h: Int): List<Grid> {
        if (w < 40 || h < 40 || luma.size < w * h) return emptyList()

        val smooth = boxBlur3(luma, w, h)
        val hist = IntArray(256)
        for (l in smooth) hist[l.coerceIn(0, 255)]++
        val t = otsu(hist, w * h).coerceIn(16, 220)

        val bright = BooleanArray(w * h) { smooth[it] >= t }
        val raw = findBlobs(bright, w, h).filter { b ->
            val bw = b.maxX - b.minX + 1
            val bh = b.maxY - b.minY + 1
            val aspect = bw.toFloat() / bh
            b.area >= 16 && aspect in 0.45f..2.2f && b.fill > 0.55f
        }
        if (raw.size < MIN_VISIBLE) {
            lastDiagnostic = "可见色块只有 ${raw.size} 个（阈值 $t）"
            return emptyList()
        }

        // 面积收紧：24 个色块面积几乎相同，键盘按键/图标/文字块差一个数量级
        val sortedAreas = raw.map { it.area }.sortedDescending()
        val topN = sortedAreas.take(minOf(12, sortedAreas.size))
        val med = topN[topN.size / 2].toFloat()
        val blobs = raw.filter { it.area >= 0.42f * med && it.area <= 2.5f * med }
        if (blobs.size < MIN_VISIBLE) {
            lastDiagnostic = "面积过滤后只剩 ${blobs.size} 个色块"
            return emptyList()
        }

        val pts = FloatArray(blobs.size * 2)
        for (i in blobs.indices) {
            pts[i * 2] = blobs[i].cx
            pts[i * 2 + 1] = blobs[i].cy
        }

        val vectors = candidateVectors(pts, maxOf(w, h) * 0.5f)
        if (vectors.size < 2) {
            lastDiagnostic = "候选格距方向不足（色块 ${blobs.size} 个）"
            return emptyList()
        }

        val found = ArrayList<Grid>()
        val seen = ArrayList<FloatArray>()
        for (a in vectors.indices) {
            for (b in a + 1 until vectors.size) {
                val v1 = vectors[a].vec
                val v2 = vectors[b].vec
                val dAng = minOf(abs(vectors[a].ang - vectors[b].ang),
                    180f - abs(vectors[a].ang - vectors[b].ang))
                if (dAng < 25f) continue

                val asg = assign(pts, v1, v2, 0.24f) ?: continue
                val ref = refine(pts, asg) ?: continue
                if (ref.keep.size < MIN_VISIBLE) continue

                val pitch = 0.5f * (hypot(v1[0], v1[1]) + hypot(v2[0], v2[1]))
                if (ref.err > maxOf(6f, pitch * 0.18f)) continue

                val grid = toGrid(ref, w, h, pitch) ?: continue
                // 去重：中心点几乎一样的视为同一个解
                val dup = seen.any { prev ->
                    var same = 0
                    for (k in 0 until 24) {
                        val dx = prev[k * 2] - grid.centers[k * 2]
                        val dy = prev[k * 2 + 1] - grid.centers[k * 2 + 1]
                        if (hypot(dx, dy) * w < pitch * 0.3f) same++
                    }
                    same >= 20
                }
                if (!dup) {
                    seen.add(grid.centers)
                    found.add(grid)
                }
            }
        }

        if (found.isEmpty()) {
            lastDiagnostic = "格点反解失败（色块 ${blobs.size} 个，方向 ${vectors.size} 个）"
            return emptyList()
        }
        found.sortByDescending { it.detected }
        return found.take(MAX_CANDIDATES)
    }

    private fun toGrid(ref: Refined, w: Int, h: Int, pitch: Float): Grid? {
        val centers = FloatArray(24 * 2)
        val iIsColumn = ref.ni == 6
        for (i in 0 until ref.ni) {
            for (j in 0 until ref.nj) {
                val r = if (iIsColumn) j else i
                val c = if (iIsColumn) i else j
                if (r >= 4 || c >= 6) return null
                val p = applyH(ref.h, i.toFloat(), j.toFloat())
                val idx = (r * 6 + c) * 2
                centers[idx] = p[0] / w
                centers[idx + 1] = p[1] / h
            }
        }
        // 24 个中心必须都稳稳落在画面内。原来允许 -0.1~1.1 的溢出，
        // 结果有些候选的格子中心跑到图像外，采样窗口直接越界，整次标定白跑（实测）
        for (v in centers) if (v < 0.015f || v > 0.985f) return null
        return Grid(centers, 4, 6, pitch / w, pitch / h, ref.keep.size)
    }

    // ---------------- 候选格距向量 ----------------

    private class Cand(val vec: FloatArray, val ang: Float, val support: Int)

    private fun candidateVectors(pts: FloatArray, maxScale: Float): List<Cand> {
        val n = pts.size / 2
        val all = ArrayList<FloatArray>(n * n)   // dx, dy, len, ang
        for (i in 0 until n) {
            for (j in 0 until n) {
                if (i == j) continue
                val dx = pts[j * 2] - pts[i * 2]
                val dy = pts[j * 2 + 1] - pts[i * 2 + 1]
                val len = hypot(dx, dy)
                if (len < 8f || len > maxScale) continue
                var ang = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                ang = ((ang % 180f) + 180f) % 180f
                all.add(floatArrayOf(dx, dy, len, ang))
            }
        }
        if (all.isEmpty()) return emptyList()
        all.sortBy { it[2] }

        val clusters = ArrayList<MutableList<FloatArray>>()
        for (v in all) {
            var placed = false
            for (cl in clusters) {
                val lc = medianOf(cl, 2)
                val ac = medianOf(cl, 3)
                val dAng = minOf(abs(v[3] - ac), 180f - abs(v[3] - ac))
                if (abs(v[2] - lc) < 0.15f * lc && dAng < 12f) {
                    cl.add(v)
                    placed = true
                    break
                }
            }
            if (!placed) clusters.add(mutableListOf(v))
        }

        val out = ArrayList<Cand>()
        for (cl in clusters) {
            if (cl.size < 4) continue
            val ref = cl[0]
            var sx = 0f
            var sy = 0f
            for (v in cl) {
                val s = if (v[0] * ref[0] + v[1] * ref[1] < 0) -1f else 1f
                sx += v[0] * s
                sy += v[1] * s
            }
            val vx = sx / cl.size
            val vy = sy / cl.size
            var ang = Math.toDegrees(atan2(vy.toDouble(), vx.toDouble())).toFloat()
            ang = ((ang % 180f) + 180f) % 180f
            out.add(Cand(floatArrayOf(vx, vy), ang, cl.size))
        }
        out.sortByDescending { it.support }
        return out
    }

    private fun medianOf(cl: List<FloatArray>, idx: Int): Float {
        val s = cl.map { it[idx] }.sorted()
        return s[s.size / 2]
    }

    // ---------------- 格点分配 ----------------

    private class Assign(val keep: IntArray, val ii: IntArray, val jj: IntArray, val ni: Int, val nj: Int)

    private fun assign(pts: FloatArray, v1: FloatArray, v2: FloatArray, tol: Float): Assign? {
        val det = v1[0] * v2[1] - v2[0] * v1[1]
        if (abs(det) < 1e-4f) return null
        val n = pts.size / 2
        val ti = FloatArray(n)
        val tj = FloatArray(n)
        for (k in 0 until n) {
            val px = pts[k * 2]
            val py = pts[k * 2 + 1]
            ti[k] = (v2[1] * px - v2[0] * py) / det
            tj[k] = (-v1[1] * px + v1[0] * py) / det
        }
        val off = FloatArray(2)
        for (axis in 0..1) {
            val src = if (axis == 0) ti else tj
            var s = 0.0
            var c = 0.0
            for (v in src) {
                val f = v - Math.round(v)
                val a = f * 2.0 * Math.PI
                s += sin(a)
                c += cos(a)
            }
            off[axis] = (atan2(s, c) / (2.0 * Math.PI)).toFloat()
        }
        val ii = IntArray(n)
        val jj = IntArray(n)
        val res = FloatArray(n)
        for (k in 0 until n) {
            val a = ti[k] - off[0]
            val b = tj[k] - off[1]
            ii[k] = Math.round(a)
            jj[k] = Math.round(b)
            res[k] = maxOf(abs(a - ii[k]), abs(b - jj[k]))
        }
        val keepList = ArrayList<Int>(n)
        for (k in 0 until n) if (res[k] < tol) keepList.add(k)
        if (keepList.size < MIN_VISIBLE) return null

        var minI = Int.MAX_VALUE
        var minJ = Int.MAX_VALUE
        for (k in keepList) {
            if (ii[k] < minI) minI = ii[k]
            if (jj[k] < minJ) minJ = jj[k]
        }
        var ni = 0
        var nj = 0
        val keep = IntArray(keepList.size)
        val ki = IntArray(keepList.size)
        val kj = IntArray(keepList.size)
        for ((idx, k) in keepList.withIndex()) {
            keep[idx] = k
            ki[idx] = ii[k] - minI
            kj[idx] = jj[k] - minJ
            if (ki[idx] + 1 > ni) ni = ki[idx] + 1
            if (kj[idx] + 1 > nj) nj = kj[idx] + 1
        }
        if (!((ni == 6 && nj == 4) || (ni == 4 && nj == 6))) return null
        return Assign(keep, ki, kj, ni, nj)
    }

    private class Refined(
        val keep: IntArray, val ii: IntArray, val jj: IntArray,
        val ni: Int, val nj: Int, val h: FloatArray, val err: Float
    )

    /**
     * 粗分 → 拟合透视 → 用逆变换把点映回格点坐标 → 重新 round，逐轮收紧。
     * 斜拍时近处格子宽、远处窄，一次仿射分配会漏掉几个，两三轮后就收敛。
     */
    private fun refine(pts: FloatArray, start: Assign, rounds: Int = 4): Refined? {
        var keep = start.keep
        var ii = start.ii
        var jj = start.jj
        var ni = start.ni
        var nj = start.nj

        for (round in 0 until rounds) {
            if (keep.size < MIN_VISIBLE) return null
            val src = FloatArray(keep.size * 2)
            val dst = FloatArray(keep.size * 2)
            for ((idx, k) in keep.withIndex()) {
                src[idx * 2] = ii[idx].toFloat()
                src[idx * 2 + 1] = jj[idx].toFloat()
                dst[idx * 2] = pts[k * 2]
                dst[idx * 2 + 1] = pts[k * 2 + 1]
            }
            val hf = fitHomography(src, dst) ?: return null
            val hb = fitHomography(dst, src) ?: return null

            val n = pts.size / 2
            val cand = ArrayList<Int>(n)
            for (k in 0 until n) {
                val uv = applyH(hb, pts[k * 2], pts[k * 2 + 1])
                val ri = Math.round(uv[0])
                val rj = Math.round(uv[1])
                if (maxOf(abs(uv[0] - ri), abs(uv[1] - rj)) < 0.16f) cand.add(k)
            }
            if (cand.size < MIN_VISIBLE) return null
            if (cand.size < keep.size - 1) return null

            var mi = Int.MAX_VALUE
            var mj = Int.MAX_VALUE
            for (k in cand) {
                val uv = applyH(hb, pts[k * 2], pts[k * 2 + 1])
                mi = minOf(mi, Math.round(uv[0]))
                mj = minOf(mj, Math.round(uv[1]))
            }
            val newI = IntArray(cand.size)
            val newJ = IntArray(cand.size)
            var maxI = 0
            var maxJ = 0
            for ((idx, k) in cand.withIndex()) {
                val uv = applyH(hb, pts[k * 2], pts[k * 2 + 1])
                newI[idx] = Math.round(uv[0]) - mi
                newJ[idx] = Math.round(uv[1]) - mj
                if (newI[idx] > maxI) maxI = newI[idx]
                if (newJ[idx] > maxJ) maxJ = newJ[idx]
            }
            ni = maxI + 1
            nj = maxJ + 1
            if (!((ni == 6 && nj == 4) || (ni == 4 && nj == 6))) return null

            keep = cand.toIntArray()
            ii = newI
            jj = newJ
        }

        val src = FloatArray(keep.size * 2)
        val dst = FloatArray(keep.size * 2)
        for ((idx, k) in keep.withIndex()) {
            src[idx * 2] = ii[idx].toFloat()
            src[idx * 2 + 1] = jj[idx].toFloat()
            dst[idx * 2] = pts[k * 2]
            dst[idx * 2 + 1] = pts[k * 2 + 1]
        }
        val hf = fitHomography(src, dst) ?: return null
        var err = 0f
        for (k in keep.indices) {
            val p = applyH(hf, src[k * 2], src[k * 2 + 1])
            err = maxOf(err, abs(p[0] - dst[k * 2]), abs(p[1] - dst[k * 2 + 1]))
        }
        return Refined(keep, ii, jj, ni, nj, hf, err)
    }

    // ---------------- 透视拟合 ----------------

    internal fun fitHomography(src: FloatArray, dst: FloatArray): FloatArray? {
        val n = src.size / 2
        if (n < 4 || dst.size / 2 != n) return null
        val ata = Array(8) { DoubleArray(9) }
        for (k in 0 until n) {
            val i = src[k * 2].toDouble()
            val j = src[k * 2 + 1].toDouble()
            val x = dst[k * 2].toDouble()
            val y = dst[k * 2 + 1].toDouble()
            accumulate(ata, doubleArrayOf(i, j, 1.0, 0.0, 0.0, 0.0, -x * i, -x * j), x)
            accumulate(ata, doubleArrayOf(0.0, 0.0, 0.0, i, j, 1.0, -y * i, -y * j), y)
        }
        for (d in 0 until 8) ata[d][d] += 1e-6
        return solve8(ata)
    }

    private fun accumulate(ata: Array<DoubleArray>, row: DoubleArray, rhs: Double) {
        for (r in 0 until 8) {
            val rv = row[r]
            if (rv == 0.0) continue
            val line = ata[r]
            for (c in 0 until 8) line[c] += rv * row[c]
            line[8] += rv * rhs
        }
    }

    private fun solve8(a: Array<DoubleArray>): FloatArray? {
        for (col in 0 until 8) {
            var pivot = col
            for (r in col + 1 until 8) {
                if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            }
            if (abs(a[pivot][col]) < 1e-12) return null
            if (pivot != col) {
                val tmp = a[pivot]; a[pivot] = a[col]; a[col] = tmp
            }
            val pv = a[col][col]
            for (c in col..8) a[col][c] /= pv
            for (r in 0 until 8) {
                if (r == col) continue
                val f = a[r][col]
                if (f == 0.0) continue
                for (c in col..8) a[r][c] -= f * a[col][c]
            }
        }
        val out = FloatArray(9)
        for (i in 0 until 8) {
            val v = a[i][8].toFloat()
            if (!v.isFinite()) return null
            out[i] = v
        }
        out[8] = 1f
        return out
    }

    internal fun applyH(h: FloatArray, u: Float, v: Float): FloatArray {
        val ww = h[6] * u + h[7] * v + h[8]
        val den = if (abs(ww) < 1e-6f) 1e-6f else ww
        return floatArrayOf(
            (h[0] * u + h[1] * v + h[2]) / den,
            (h[3] * u + h[4] * v + h[5]) / den
        )
    }

    // ---------------- 连通域 ----------------

    private class Blob(
        var minX: Int, var maxX: Int, var minY: Int, var maxY: Int,
        var area: Int, var sumX: Long, var sumY: Long
    ) {
        val cx: Float get() = sumX.toFloat() / area
        val cy: Float get() = sumY.toFloat() / area
        val fill: Float
            get() = area.toFloat() / ((maxX - minX + 1).toFloat() * (maxY - minY + 1))
    }

    private fun findBlobs(mask: BooleanArray, w: Int, h: Int): List<Blob> {
        val visited = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<Blob>()

        for (start in 0 until w * h) {
            if (!mask[start] || visited[start]) continue
            var top = 0
            stack[top++] = start
            visited[start] = true
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            var area = 0; var sx = 0L; var sy = 0L

            while (top > 0) {
                val idx = stack[--top]
                val x = idx % w
                val y = idx / w
                area++
                sx += x; sy += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                if (x > 0) { val n = idx - 1; if (mask[n] && !visited[n]) { visited[n] = true; stack[top++] = n } }
                if (x < w - 1) { val n = idx + 1; if (mask[n] && !visited[n]) { visited[n] = true; stack[top++] = n } }
                if (y > 0) { val n = idx - w; if (mask[n] && !visited[n]) { visited[n] = true; stack[top++] = n } }
                if (y < h - 1) { val n = idx + w; if (mask[n] && !visited[n]) { visited[n] = true; stack[top++] = n } }
            }

            if (area >= 16) out.add(Blob(minX, maxX, minY, maxY, area, sx, sy))
        }
        return out
    }

    private fun boxBlur3(src: IntArray, w: Int, h: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val l = if (x > 0) src[row + x - 1] else src[row]
                val c = src[row + x]
                val r = if (x < w - 1) src[row + x + 1] else src[row + w - 1]
                tmp[row + x] = (l + c + r) / 3
            }
        }
        for (y in 0 until h) {
            val row = y * w
            val up = if (y > 0) (y - 1) * w else 0
            val down = if (y < h - 1) (y + 1) * w else row
            for (x in 0 until w) {
                out[row + x] = (tmp[up + x] + tmp[row + x] + tmp[down + x]) / 3
            }
        }
        return out
    }

    internal fun otsu(hist: IntArray, total: Int): Int {
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = 127
        var maxVar = -1.0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > maxVar) {
                maxVar = between
                best = t
            }
        }
        return best
    }
}
