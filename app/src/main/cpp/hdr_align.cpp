// HDR+ §4 分层对齐 + 补充材料 §1–2 的具体参数。
//
// 之前这一版我是"照意思写的"，金字塔层距和搜索半径都是估的。补充材料（§2
// Image alignment pyramids）写得很明确，照抄如下：
//
//   层       相对上一层      搜索半径      算法      tile
//   L0(底)   —（灰度基准）    ±1 px        L1       16×16
//   L1       下采样 ×2        ±4 px        L2       16×16
//   L2       下采样 ×4        ±4 px        L2       16×16
//   L3       下采样 ×4        ±4 px        L2        8×8
//
// 覆盖范围 = 1 + 4·2 + 4·8 + 4·32 = 169（底图像素），正是论文说的"最大位移 169 像素"。
// 之前我用了 2/4/8 的半径和 ×2 的层距，覆盖算出来对不上 169，说明参数不是论文的。
//
// 另外两条来自论文与补充材料，之前也没照做：
//   · tile 只有最粗层缩到 8×8——因为再往下采样，16×16 在原始图上覆盖太大，
//     近似不了非平移的局部运动；更粗的层靠大 tile 抗噪声。
//   · 最细层必须用 L1 + 小半径（补充材料 §1：大 tile + 小半径时，L1 暴力搜索
//     比快速 L2 和相位相关都好，而且 ARM 有绝对差累加指令，正好对口）。
#include "hdr_align.h"

#include "hdr_common.h"

#include <android/log.h>

#include <algorithm>
#include <climits>
#include <cstdio>

#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "HdrAlign", __VA_ARGS__)

namespace camhdr {

namespace {

constexpr int PYR = 4;

// 由细到粗（与补充材料表格一一对应）
constexpr int kTile[PYR] = {16, 16, 16, 8};
constexpr int kRad[PYR] = {1, 4, 4, 4};
// 相邻层的降采样倍数：L0->L1 是 2，之后是 4
constexpr int kDecim[PYR] = {1, 2, 4, 4};

struct Level {
    int w = 0, h = 0;
    std::vector<uint16_t> a;
};

// factor×factor 盒式平均
Level decimate(const Level &s, int factor) {
    Level d;
    d.w = std::max(1, s.w / factor);
    d.h = std::max(1, s.h / factor);
    d.a.resize((size_t) d.w * d.h);
    const int f2 = factor * factor;
    parallelRows(d.h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            uint16_t *o = d.a.data() + (size_t) y * d.w;
            for (int x = 0; x < d.w; ++x) {
                unsigned sum = 0;
                for (int j = 0; j < factor; ++j) {
                    const int sy = std::min(s.h - 1, y * factor + j);
                    const uint16_t *row = s.a.data() + (size_t) sy * s.w;
                    for (int i = 0; i < factor; ++i) {
                        sum += row[std::min(s.w - 1, x * factor + i)];
                    }
                }
                o[x] = (uint16_t) ((sum + f2 / 2) / f2);
            }
        }
    });
    return d;
}

void buildPyramid(Level base, Level out[PYR]) {
    // 按值接 + move：grayHalf 已经算好的 3 Mpix 灰度图不再复制一遍
    // （12MP 工程里每帧少一次 6 MB 的分配 + 拷贝，5 帧就是 5 次）
    out[0] = std::move(base);
    for (int i = 1; i < PYR; ++i) out[i] = decimate(out[i - 1], kDecim[i]);
}

// 2×2 Bayer 平均 -> 半分辨率灰度（论文：对齐 3Mpix 灰度图而不是 12Mpix RAW）
Level grayHalf(const uint16_t *raw, int w, int h, int black) {
    Level g;
    g.w = std::max(1, w / 2);
    g.h = std::max(1, h / 2);
    g.a.resize((size_t) g.w * g.h);
    parallelRows(g.h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            const int r0 = (2 * y) * w;
            const int r1 = std::min(h - 1, 2 * y + 1) * w;
            const uint16_t *p0 = raw + r0;
            const uint16_t *p1 = raw + r1;
            uint16_t *o = g.a.data() + (size_t) y * g.w;
            for (int x = 0; x < g.w; ++x) {
                const int c0 = 2 * x, c1 = std::min(w - 1, 2 * x + 1);
                // 黑电平是加法偏置，必须在平均之前减掉
                const int v = (int) p0[c0] + p0[c1] + p1[c0] + p1[c1] - 4 * black;
                o[x] = (uint16_t) clampInt((v + 2) >> 2, 0, 65535);
            }
        }
    });
    return g;
}

/**
 * 一帧的"锐度"评分：3 Mpix 灰度图上的 Sobel 能量，按平均亮度平方归一。
 *
 * 为什么要除以亮度平方：Sobel 是线性算子，画面整体亮一倍，梯度也大一倍，
 * 能量就大四倍。不归一的话，评分会变成"哪帧更亮"而不是"哪帧更锐"。
 * （等曝光连拍理论上亮度一致，但 AE 收敛尾巴上那 1~2% 的抖动足够翻转排序。）
 *
 * 步长 2：3 Mpix 只取 75 万点，5 帧合计 375 万次，几毫秒。
 * 采样这么稀仍然可靠，因为要判断的是"整幅画面的锐度排序"，不是逐像素精度。
 *
 * 噪声也会贡献一点能量，但同一次连拍里各帧噪声水平相同，且被亮度归一后
 * 基本抵消——实测 5 帧的分数差异来自构图内容本身（0.5%~8%），不是噪声。
 */
double sharpnessScore(const Level &g) {
    if (g.w < 8 || g.h < 8) return 0.0;
    double sum = 0;
    long long lumSum = 0;
    int cnt = 0;
    for (int y = 1; y < g.h - 1; y += 2) {
        const uint16_t *r0 = g.a.data() + (size_t) (y - 1) * g.w;
        const uint16_t *r1 = g.a.data() + (size_t) y * g.w;
        const uint16_t *r2 = g.a.data() + (size_t) (y + 1) * g.w;
        for (int x = 1; x < g.w - 1; x += 2) {
            const int gx = (r0[x + 1] + 2 * r1[x + 1] + r2[x + 1])
                         - (r0[x - 1] + 2 * r1[x - 1] + r2[x - 1]);
            const int gy = (r2[x - 1] + 2 * r2[x] + r2[x + 1])
                         - (r0[x - 1] + 2 * r0[x] + r0[x + 1]);
            sum += (double) gx * gx + (double) gy * gy;
            lumSum += r1[x];
            ++cnt;
        }
    }
    if (cnt == 0) return 0.0;
    const double mean = (double) lumSum / cnt;
    return sum / (double) cnt / std::max(1.0, mean * mean);
}

// tile 的横向平均梯度，用来判断"这块有没有可比的结构"
inline float tileContrast(const Level &ref, int tx, int ty, int tile) {
    const int x0 = tx * tile, y0 = ty * tile;
    const int x1 = std::min(ref.w, x0 + tile), y1 = std::min(ref.h, y0 + tile);
    double s = 0;
    int c = 0;
    for (int y = y0; y < y1; ++y) {
        const uint16_t *r = ref.a.data() + (size_t) y * ref.w;
        for (int x = x0; x + 1 < x1; ++x) {
            s += std::abs((int) r[x + 1] - (int) r[x]);
            ++c;
        }
    }
    return c > 0 ? (float) (s / c) : 0.f;
}

/**
 * 一个 tile 在给定位移下的平均 L1 残差 + 有效样本数。
 *
 * 越界要**跳过**而不是 clamp：clamp 会把图外内容伪造成"和图内一样"，
 * 边界处所有候选得分相等，搜索就退化。取平均而不是求和，是因为跳过之后
 * 各候选的样本数不同，求和会把"样本少"当成"差异小"。
 */
inline float tileScore(const Level &ref, const Level &alt, int tx, int ty, int tile,
                       int ox, int oy, int step, int *outCnt) {
    const int x0 = tx * tile, y0 = ty * tile;
    const int x1 = std::min(ref.w, x0 + tile), y1 = std::min(ref.h, y0 + tile);
    const int xs = std::max(x0, -ox), xe = std::min(x1, alt.w - ox);
    const int ys = std::max(y0, -oy), ye = std::min(y1, alt.h - oy);
    if (xe <= xs || ye <= ys) {
        *outCnt = 0;
        return 1e9f;
    }
    int sum = 0, cnt = 0;
    for (int y = ys; y < ye; y += step) {
        const int ay = y + oy;
        const uint16_t *rr = ref.a.data() + (size_t) y * ref.w;
        const uint16_t *aa = alt.a.data() + (size_t) ay * alt.w;
        for (int x = xs; x < xe; x += step) {
            sum += std::abs((int) rr[x] - (int) aa[x + ox]);
            ++cnt;
        }
    }
    *outCnt = cnt;
    return cnt > 0 ? (float) sum / cnt : 1e9f;
}

}  // namespace

void alignBurst(const uint16_t *const *frames, int n, int w, int h, int black,
                std::vector<AlignField> *out, int *outRefIdx) {
    out->assign(std::max(1, n), AlignField{});
    if (outRefIdx) *outRefIdx = 0;
    if (n <= 0 || w < 64 || h < 64) return;

    // ---- 1. 所有帧的半分辨率灰度：对齐要用，锐度评分也要用，只算一次 ----
    //
    // 内存：3 Mpix × 2 B = 6 MB/帧。5 帧 30 MB、8 帧 48 MB，
    // 换掉的是"每帧算两遍降采样"（一遍评分、一遍对齐）和一次全图复制。
    std::vector<Level> grays(n);
    for (int k = 0; k < n; ++k) grays[k] = grayHalf(frames[k], w, h, black);

    // ---- 2. 挑参考帧（论文 §4：burst 里最锐的一帧，且只从前 3 帧里挑）----
    int refIdx = 0;
    if (n > 1) {
        std::vector<double> score(n, 0.0);
        for (int k = 0; k < n; ++k) score[k] = sharpnessScore(grays[k]);
        // 限制在前 3 帧：论文的约束来自"减少快门延迟"（参考帧越靠后，
        // 用户按下快门到出图之间要等越久）。我们帧数本来就少，
        // 但保留这条约束，行为与论文一致。
        const int lim = std::min(n, 3);
        double best = -1.0;
        for (int k = 0; k < lim; ++k) {
            if (score[k] > best) { best = score[k]; refIdx = k; }
        }
        // 分数差 < 0.5% 时视为"没有可分辨的锐度差异"，退回第 0 帧：
        // 避免在完全静止的场景里被噪声的小数点后几位推着乱选。
        double lo = score[0], hi = score[0];
        for (int k = 0; k < lim; ++k) { lo = std::min(lo, score[k]); hi = std::max(hi, score[k]); }
        if (hi > 0 && (hi - lo) / hi < 0.005) refIdx = 0;

        ALOGD("选参考帧: %d / %d 帧", refIdx, n);
        {
            char buf[256];
            int off = 0;
            for (int k = 0; k < n && off < 200; ++k) {
                off += snprintf(buf + off, sizeof(buf) - off, "%s%.4f", k ? "  " : "", score[k]);
            }
            ALOGD("  锐度分数（仅前 3 帧参与评选）: %s", buf);
        }
    }

    Level refPyr[PYR];
    buildPyramid(std::move(grays[refIdx]), refPyr);
    // **必须回传**：merge 要用同一帧当基底。忘了这一行的话，
    // 位移场是相对帧 2 算的、融合却拿帧 0 当参考，整幅会错位（真出过一次）。
    if (outRefIdx) *outRefIdx = refIdx;

    const int fCols = (refPyr[0].w + kTile[0] - 1) / kTile[0];
    const int fRows = (refPyr[0].h + kTile[0] - 1) / kTile[0];
    (*out)[refIdx].cols = fCols;
    (*out)[refIdx].rows = fRows;
    (*out)[refIdx].tile = kTile[0] * 2;          // 底图像素 -> 全分辨率像素
    (*out)[refIdx].dx.assign((size_t) fCols * fRows, 0);
    (*out)[refIdx].dy.assign((size_t) fCols * fRows, 0);

    // 逐帧串行（每帧内部行并行）。同时建多份金字塔会吃满老机器的内存。
    for (int k = 0; k < n; ++k) {
        if (k == refIdx) continue;
        Level altPyr[PYR];
        buildPyramid(std::move(grays[k]), altPyr);

        std::vector<std::vector<int16_t>> lvx(PYR), lvy(PYR);
        std::vector<int> cols(PYR, 0), rows(PYR, 0);

        for (int L = PYR - 1; L >= 0; --L) {
            const int tile = kTile[L];
            const int rad = kRad[L];
            const int colsL = (refPyr[L].w + tile - 1) / tile;
            const int rowsL = (refPyr[L].h + tile - 1) / tile;
            // 细层抽 2 点采样（评分只需要相对大小），粗层全采（本来就没几个样本）
            const int step = (L <= 1) ? 2 : 1;
            cols[L] = colsL;
            rows[L] = rowsL;
            lvx[L].assign((size_t) colsL * rowsL, 0);
            lvy[L].assign((size_t) colsL * rowsL, 0);

            const Level &ref = refPyr[L];
            const Level &alt = altPyr[L];

            parallelRows(rowsL, [&](int ys, int ye) {
                for (int ty = ys; ty < ye; ++ty) {
                    for (int tx = 0; tx < colsL; ++tx) {
                        const int fullX = std::min(ref.w, tx * tile + tile) - tx * tile;
                        const int fullY = std::min(ref.h, ty * tile + tile) - ty * tile;
                        const int fullCnt = std::max(1, (fullX / step) * (fullY / step));

                        int gx = 0, gy = 0;

                        // 粗层解上采样：论文的做法是取 3 个候选（最近邻 + 两个方向上的次近邻），
                        // 各自算 L1 残差取最好的。这样粗层跨过物体边界时不会直接污染细层。
                        if (L + 1 < PYR && cols[L + 1] > 0) {
                            const int cx = tx * tile + tile / 2;
                            const int cy = ty * tile + tile / 2;
                            // 换算到粗层坐标（层距是 kDecim[L+1]）
                            const int dec = kDecim[L + 1];
                            const int ccx = cx / dec, ccy = cy / dec;
                            const int ct = kTile[L + 1];
                            const int bx = clampInt(ccx / ct, 0, cols[L + 1] - 1);
                            const int by = clampInt(ccy / ct, 0, rows[L + 1] - 1);
                            const int sx = ((ccx - bx * ct) * 2 > ct) ? 1 : -1;
                            const int sy = ((ccy - by * ct) * 2 > ct) ? 1 : -1;
                            const int candX[3] = {bx, clampInt(bx + sx, 0, cols[L + 1] - 1), bx};
                            const int candY[3] = {by, by, clampInt(by + sy, 0, rows[L + 1] - 1)};

                            float bestS = 1e30f;
                            for (int c = 0; c < 3; ++c) {
                                const int j = candY[c] * cols[L + 1] + candX[c];
                                const int ox = lvx[L + 1][j] * dec;
                                const int oy = lvy[L + 1][j] * dec;
                                int cnt = 0;
                                const float s = tileScore(ref, alt, tx, ty, tile, ox, oy, step, &cnt);
                                if (cnt * 2 < fullCnt) continue;
                                if (s < bestS) {
                                    bestS = s;
                                    gx = ox;
                                    gy = oy;
                                }
                            }
                        }

                        // 平坦区没有可比的结构，任何位移都"差不多好"。
                        // 这种 tile 直接采用初值，不参与搜索。
                        if (tileContrast(ref, tx, ty, tile) < 1.6f) {
                            const int j = ty * colsL + tx;
                            lvx[L][j] = (int16_t) clampInt(gx, -800, 800);
                            lvy[L][j] = (int16_t) clampInt(gy, -800, 800);
                            continue;
                        }

                        // 本层细化（半径见 kRad）
                        int cnt = 0;
                        float bestS = tileScore(ref, alt, tx, ty, tile, gx, gy, step, &cnt);
                        if (cnt * 3 < fullCnt * 2) bestS = 1e30f;
                        int hx = gx, hy = gy;
                        for (int dy = -rad; dy <= rad; ++dy) {
                            for (int dx = -rad; dx <= rad; ++dx) {
                                if (!dx && !dy) continue;
                                int c2 = 0;
                                const float s = tileScore(ref, alt, tx, ty, tile, gx + dx, gy + dy, step, &c2);
                                if (c2 * 3 < fullCnt * 2) continue;
                                // 必须"明显更好"才移动。平坦/暗噪声区的残差本来就只差零点几 DN，
                                // 没有这条门槛，静止画面也会被搜出假位移，
                                // 融合时把不同位置的内容混到一起，边缘就成彩色色块。
                                if (s < bestS - std::max(1.2f, bestS * 0.03f)) {
                                    bestS = s;
                                    hx = gx + dx;
                                    hy = gy + dy;
                                }
                            }
                        }
                        const int j = ty * colsL + tx;
                        lvx[L][j] = (int16_t) clampInt(hx, -800, 800);
                        lvy[L][j] = (int16_t) clampInt(hy, -800, 800);
                    }
                }
            });
        }

        AlignField &f = (*out)[k];
        f.cols = fCols;
        f.rows = fRows;
        f.tile = kTile[0] * 2;
        f.dx.assign((size_t) fCols * fRows, 0);
        f.dy.assign((size_t) fCols * fRows, 0);
        // 底图像素 -> 全分辨率像素（×2，天然偶数，Bayer 相位不变）
        const int nCopy = std::min((int) lvx[0].size(), fCols * fRows);
        for (int i = 0; i < nCopy; ++i) {
            f.dx[i] = (int16_t) clampInt(lvx[0][i] * 2, -400, 400);
            f.dy[i] = (int16_t) clampInt(lvy[0][i] * 2, -400, 400);
        }

        // 3×3 中位数滤波：单个 tile 判错会留下孤立的大位移，
        // 融合时位移按双线性插值，一个坏点会污染周围 4 个 tile 的内容。
        std::vector<int16_t> tx9(f.dx), ty9(f.dy);
        for (int y = 0; y < fRows; ++y) {
            for (int x = 0; x < fCols; ++x) {
                int bx[9], by[9], m = 0;
                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dx = -1; dx <= 1; ++dx) {
                        const int xx = clampInt(x + dx, 0, fCols - 1);
                        const int yy = clampInt(y + dy, 0, fRows - 1);
                        bx[m] = tx9[(size_t) yy * fCols + xx];
                        by[m] = ty9[(size_t) yy * fCols + xx];
                        ++m;
                    }
                }
                for (int i = 1; i < 9; ++i) {
                    const int v = bx[i];
                    int j = i - 1;
                    while (j >= 0 && bx[j] > v) { bx[j + 1] = bx[j]; j--; }
                    bx[j + 1] = v;
                    const int w2 = by[i];
                    j = i - 1;
                    while (j >= 0 && by[j] > w2) { by[j + 1] = by[j]; j--; }
                    by[j + 1] = w2;
                }
                f.dx[(size_t) y * fCols + x] = (int16_t) bx[4];
                f.dy[(size_t) y * fCols + x] = (int16_t) by[4];
            }
        }

        int mxd = 0;
        long sumd = 0;
        for (int i = 0; i < fCols * fRows; ++i) {
            const int a = std::abs((int) f.dx[i]) + std::abs((int) f.dy[i]);
            mxd = std::max(mxd, a);
            sumd += a;
        }
        ALOGD("帧 %d 位移场 %dx%d tile=%d  均值 %.2f 最大 %d 全分辨率px",
              k, fCols, fRows, f.tile, (double) sumd / std::max(1, fCols * fRows), mxd);
    }
}

void refineSubpixelAlign(const uint16_t *const *frames, int n, int refIdx,
                         int w, int h, int black,
                         const std::vector<AlignField> &coarse,
                         std::vector<std::vector<float>> *outDx,
                         std::vector<std::vector<float>> *outDy) {
    outDx->assign(std::max(1, n), {});
    outDy->assign(std::max(1, n), {});
    if (n <= 0 || w < 64 || h < 64) return;
    if (refIdx < 0 || refIdx >= n) refIdx = 0;
    if (coarse.size() < (size_t) n) return;

    const Level refG = grayHalf(frames[refIdx], w, h, black);

    // 双线性采样（灰度层坐标可以是小数）
    auto at = [&](const Level &g, float fx, float fy) -> float {
        const float cx = clampf(fx, 0.f, (float) (g.w - 1));
        const float cy = clampf(fy, 0.f, (float) (g.h - 1));
        int x0 = (int) cx, y0 = (int) cy;
        if (x0 >= g.w - 1) x0 = g.w - 2;
        if (y0 >= g.h - 1) y0 = g.h - 2;
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        const int x1 = std::min(x0 + 1, g.w - 1), y1 = std::min(y0 + 1, g.h - 1);
        const float ax = cx - x0, ay = cy - y0;
        const uint16_t *r0 = g.a.data() + (size_t) y0 * g.w;
        const uint16_t *r1 = g.a.data() + (size_t) y1 * g.w;
        const float top = (float) r0[x0] + ((float) r0[x1] - (float) r0[x0]) * ax;
        const float bot = (float) r1[x0] + ((float) r1[x1] - (float) r1[x0]) * ax;
        return top + (bot - top) * ay;
    };

    const int TW = kTile[0];        // 16（灰度层）

    for (int k = 0; k < n; ++k) {
        const AlignField &f = coarse[k];
        const int cols = f.cols, rows = f.rows;
        if (cols <= 0 || rows <= 0) continue;
        outDx->at(k).assign((size_t) cols * rows, 0.f);
        outDy->at(k).assign((size_t) cols * rows, 0.f);
        if (k == refIdx) continue;              // 参考帧位移恒 0

        const Level altG = grayHalf(frames[k], w, h, black);

        parallelRows(rows, [&](int ys, int ye) {
            for (int ty = ys; ty < ye; ++ty) {
                for (int tx = 0; tx < cols; ++tx) {
                    const int j = ty * cols + tx;
                    // coarse 是全分辨率像素，灰度层要除 2
                    const float c0x = f.dx[j] * 0.5f;
                    const float c0y = f.dy[j] * 0.5f;
                    float gx = c0x, gy = c0y;

                    const int x0 = tx * TW, y0 = ty * TW;
                    const int x1 = std::min(refG.w - 2, x0 + TW - 1);
                    const int y1 = std::min(refG.h - 2, y0 + TW - 1);

                    for (int it = 0; it < 3; ++it) {
                        double A = 0, B = 0, C = 0, D = 0, E = 0;
                        for (int y = std::max(1, y0); y < y1; ++y) {
                            const uint16_t *rm = refG.a.data() + (size_t) (y - 1) * refG.w;
                            const uint16_t *rc = refG.a.data() + (size_t) y * refG.w;
                            const uint16_t *rp = refG.a.data() + (size_t) (y + 1) * refG.w;
                            for (int x = std::max(1, x0); x < x1; ++x) {
                                const float Ix = 0.5f * ((float) rc[x + 1] - (float) rc[x - 1]);
                                const float Iy = 0.5f * ((float) rp[x] - (float) rm[x]);
                                const float It = at(altG, (float) x + gx, (float) y + gy)
                                                 - (float) rc[x];
                                A += (double) Ix * Ix;
                                B += (double) Ix * Iy;
                                C += (double) Iy * Iy;
                                D -= (double) Ix * It;
                                E -= (double) Iy * It;
                            }
                        }
                        const double det = A * C - B * B;
                        if (std::fabs(det) < 1e-9) break;
                        // 平坦区的梯度矩阵接近奇异，步长必须限幅，
                        // 否则会在"两张几乎一样的格子里"跳来跳去
                        const double u = (C * D - B * E) / det;
                        const double v = (A * E - B * D) / det;
                        gx += clampf((float) u, -1.f, 1.f);
                        gy += clampf((float) v, -1.f, 1.f);
                    }

                    // LK 跑飞了就退回粗位移：与初值差 >3 灰度像素说明这个 tile
                    // 要么是平坦区、要么纹理重复，细化的结果不可信
                    if (std::fabs(gx - c0x) > 3.f || std::fabs(gy - c0y) > 3.f) {
                        gx = c0x;
                        gy = c0y;
                    }
                    outDx->at(k)[j] = gx * 2.f;     // 回全分辨率
                    outDy->at(k)[j] = gy * 2.f;
                }
            }
        });
    }
}

}  // namespace camhdr
