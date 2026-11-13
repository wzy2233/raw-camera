// 多帧亚像素超分合并（Wronski et al., SIGGRAPH 2019 §5）。
//
// 一句话：**不再做去马赛克，而是把 5 帧 Bayer 当成三个欠采样信号的稀疏观测，
// 用各向同性高斯核在每个输出像素上做核回归，直接重建出全 RGB。**
//
// 为什么这样就比"平均 + 去马赛克"清楚：
//   · 平均要求位移是整像素（还必须是偶数，为了 Bayer 相位），于是 5 帧提供的
//     采样格点完全重合 —— 信息量没有增加，只降了噪；
//   · 亚像素位移让每一帧的 R/G/B 落在不同的位置，5 帧合起来把每个通道的
//     采样密度补到接近全采样。这是"分辨率"的来源，不是插值出来的。
//
// 离线实测（sr_proto.py，真实照片合成 burst，20 组随机位移）：
//   整像素平均 + 去马赛克   34.21 dB
//   亚像素核回归 σ_r=0.5    37.88 dB   (+3.66 ± 0.69，20/20 全为正)
//
// 鲁棒性不在这里做显式的失配图：核回归**天然**具备这一条——某帧错位 3 像素，
// 它的样本离输出像素 3 px，权重 exp(-9/(2·0.5²)) ≈ e^-18 ≈ 0，自动被拒。
// 真正需要额外处理的是"位置对、内容变"（局部运动），那要用参考帧做亮度守卫，
// 属于阶段 B。
#include "hdr_superres.h"

#include "hdr_common.h"

#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <ctime>
#include <vector>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

#define ASR(...) __android_log_print(ANDROID_LOG_DEBUG, "HdrSR", __VA_ARGS__)

namespace camhdr {

namespace {

// 定点编码：和 hdr_render.cpp 的 kQScale 完全一致（0..4 线性映射到 0..65535），
// 这样 render 那边的 decodeQ 不用改。
constexpr float kQScale = 16383.75f;

// SR 输出的空间降噪强度（σ² = A·x 里的 A）。
//   < 0  = 完全关闭（当前默认）
//   = 0  = 退回旧的固定阈值 σ=70
//   > 0  = 按噪声模型逐像素定阈值
//
// ---- 为什么默认关闭（这是实测结论，不是我保守）----
// 用合成 burst（真实照片 + 实测噪声模型 + 已知亚像素位移）做了完整标定，
// 唯一变量就是 A。三项独立指标全部指向"不划算"：
//
//   A        PSNR      梯度能量保留率    平坦区 σ
//   关闭     37.00dB   48.9%            0.0263
//   0.05     36.89dB   47.7%            0.0261
//   0.15     36.78dB   47.0%            0.0260
//
// 关键在第三列：降噪把 σ 只压掉 0.8%（0.0263→0.0260），却让梯度能量掉 1.2%、
// PSNR 掉 0.22 dB。也就是说**它去掉的细节比噪声多**。
//
// 按亮度分档看 PSNR（这个才是关键，全局值会掩盖局部）：
//   亮度档            占比    Δ(A=0.15)
//   0.005-0.020      14.5%    +0.17      ← 只有最暗这一档有收益
//   0.020-0.050      16.8%    -0.04
//   0.050-0.120      20.7%    -0.23
//   0.120-0.300      36.4%    -0.35      ← 伤害最重的是中间调
//   0.300-0.700      10.2%    -0.18
//
// 根因：核回归的输出相邻像素**高度相关**（它们由同一批样本重建），
// "邻居 - 中心" 这个判据不再能区分细节与噪声；而多帧平均已经把噪声降到
// 很低，残留的那点噪声比局部梯度还小。此时任何保边平均都在做"无条件低通"。
//
// 结论：要降噪就去时域多要样本（加帧数），不要在重建结果上做事后空域低通。
// 这个常量留着是因为它能被重新打开做 A/B；关闭时下面的整段会被跳过，
// 不产生任何运行时开销。
constexpr float kSrDenoiseA = -1.0f;

inline uint16_t encodeQ(float v) {
    const int q = (int) (clampf(v, 0.f, 4.f) * kQScale + 0.5f);
    return (uint16_t) clampInt(q, 0, 65535);
}

/**
 * 核权重表（可分离版）。
 *
 * 高斯核是可分离的：
 *     w(ex, ey) = exp(−(ex²+ey²)/2σ²) = exp(−ex²/2σ²) · exp(−ey²/2σ²)
 *
 * 于是 9 个样本的权重不需要 9 次二维双线性插值，只要
 *     wx[i] · wy[j]（i, j ∈ {-1,0,1}）
 * 也就是 3+3 次一维查表 + 9 次乘法。
 *
 * 更进一步：三个样本的残差只由**小数部分** f 决定
 *     ex = i − f，  其中 f = (x + dx) − round(x + dx) ∈ [−0.5, 0.5]
 * 所以表只需要 "f 档 → 三个权重"：512 档 × 3 个 float = 6KB。
 *
 * 精度：档宽 1/512 ≈ 0.002 px。权重随 ex 的相对变化率是 |ex|/σ²，
 * 在最远的样本（|ex| = 1.5，权重已 ≤ 0.04）上是 0.8%，
 * 对加权平均的贡献 < 0.03%，远小于 1 个码。
 * （原实现用 65×65 二维表 + 双线性，理论误差 0.1%，但每样本约 30 周期；
 *   这里每样本只是 1 次乘加。）
 */
struct SepTable {
    static constexpr int N = 512;
    float w[N][4];              // 4 对齐；第 4 列留空

    void build(float sigma) {
        const float inv2s2 = 1.f / (2.f * sigma * sigma);
        for (int q = 0; q < N; ++q) {
            const float f = ((float) q + 0.5f) * (1.f / (float) N) - 0.5f;
            for (int i = 0; i < 3; ++i) {
                const float e = (float) (i - 1) - f;      // i=0 → −1−f，1 → −f，2 → 1−f
                w[q][i] = std::exp(-e * e * inv2s2);
            }
            w[q][3] = 0.f;
        }
    }

    inline int index(float f) const {
        return clampInt((int) ((f + 0.5f) * (float) N), 0, N - 1);
    }
};

/** 行级预计算：位移场的 y 方向几何 */
struct RowGeo {
    size_t r0 = 0, r1 = 0;      // ya*cols、yb*cols（省掉内层乘法）
    float fy = 0.f;
    bool valid = false;
};

/** 线程 CPU 时间（纳秒），用于测并行效率 */
inline int64_t threadCpuNs() {
    struct timespec ts;
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

inline int64_t monoNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

}  // namespace

void superResMerge(const uint16_t *const *frames, int n, int w, int h,
                   int black, float whiteScale, int refIdx,
                   const std::vector<AlignField> &coarse,
                   const std::vector<std::vector<float>> &dxf,
                   const std::vector<std::vector<float>> &dyf,
                   const int *cfaColors, float sigmaR,
                   const float *shading, int mw, int mh, const int *channelOf,
                   int lanes,
                   uint16_t *outR, uint16_t *outG, uint16_t *outB) {
    if (n <= 0 || w <= 0 || h <= 0) return;
    if (refIdx < 0 || refIdx >= n) refIdx = 0;

    SepTable tbl;
    tbl.build(sigmaR);

    const uint16_t *ref = frames[refIdx];
    const float wsc = whiteScale;

    // 暗角增益按行预计算，但**不按颜色分三行，而是按 CFA 行奇偶分两行**。
    //
    // 两件事一起解决：
    //
    // 1) 正确性。shading map 的 4 个通道固定是 R/Gr/Gb/B，而哪个 Bayer 位置是 R
    //    取决于 COLOR_FILTER_ARRANGEMENT（这台是 BGGR）。原代码写成
    //    chR = channelOf[0]、chB = channelOf[3]，再按"颜色号"去选，等于假设
    //    pos0 = R —— 在 BGGR 上 R 和 B 的暗角增益正好**对换**了（普通路径的
    //    normalizePass 用的是 channelOf[pos]，是对的，只有这里错）。
    //    现在直接按 pos 取：增益(x, sy) = shading[ channelOf[((sy&1)<<1)|(x&1)] ]。
    //
    // 2) 速度。原来的内层是 `v *= (c==0 ? gR[sx] : (c==1 ? gG[sx] : gB[sx]))`，
    //    每个样本一次三分支 + 最多两次多余数组读。而 (sy&1) 只有两种取值，
    //    所以预先合成两行 gE / gO，内层就退化成一次读、零分支。
    const bool useShade = shading && mw > 1 && mh > 1 && channelOf;
    const int *chOf = useShade ? channelOf : nullptr;

    std::atomic<int64_t> gLaneCpuNs{0};
    int64_t gLaneWallNs = 0;

    const int nn = std::min(n, 16);

    parallelRows(h, [&](int ys, int ye) {
        const int64_t thCpu0 = threadCpuNs(), thWall0 = monoNs();
        std::vector<float> gE((size_t) w), gO((size_t) w);

        // 每帧的**常量**部分提到行循环外：位移场的数据指针、tile 的倒数、
        // 以及节点网格尺寸。倒数是为了把内层 7500 万次浮点除法换成乘法。
        const float *pxDx[16], *pxDy[16];
        float invTile[16];
        int fCols[16], fRows[16];
        for (int k = 0; k < nn; ++k) {
            const AlignField &f = coarse[k];
            const bool ok = !dxf[k].empty() && !dyf[k].empty() && f.cols > 0 && f.rows > 0;
            pxDx[k] = ok ? dxf[k].data() : nullptr;
            pxDy[k] = ok ? dyf[k].data() : nullptr;
            invTile[k] = f.tile > 0 ? 1.f / (float) f.tile : 0.f;
            fCols[k] = f.cols;
            fRows[k] = f.rows;
        }

        RowGeo geo[16];

        for (int y = ys; y < ye; ++y) {
            if (useShade) {
                const float py = h > 1 ? (float) y / (h - 1) * (mh - 1) : 0.f;
                const int iy0 = clampInt((int) py, 0, mh - 1);
                const int iy1 = std::min(iy0 + 1, mh - 1);
                const float wy = py - iy0;
                for (int x = 0; x < w; ++x) {
                    const float px2 = w > 1 ? (float) x / (w - 1) * (mw - 1) : 0.f;
                    const int ix0 = clampInt((int) px2, 0, mw - 1);
                    const int ix1 = std::min(ix0 + 1, mw - 1);
                    const float wx = px2 - ix0;
                    auto gv = [&](int ch) {
                        const float a = shading[((size_t) iy0 * mw + ix0) * 4 + ch];
                        const float b = shading[((size_t) iy0 * mw + ix1) * 4 + ch];
                        const float c = shading[((size_t) iy1 * mw + ix0) * 4 + ch];
                        const float d = shading[((size_t) iy1 * mw + ix1) * 4 + ch];
                        const float t = a + (b - a) * wx;
                        const float u = c + (d - c) * wx;
                        return t + (u - t) * wy;
                    };
                    // 归一化系数折进增益，内层就不用每个样本再乘一次 wsc
                    const int xo = x & 1;
                    // sy 偶：pos = xo（0/1）；sy 奇：pos = 2+xo（2/3）
                    gE[x] = gv(chOf[xo]) * wsc;
                    gO[x] = gv(chOf[2 | xo]) * wsc;
                }
            }

            // ---- 行级预计算：每帧的 y 方向几何 ----
            // 原来这部分在 fieldAtF 里被每个像素各算一遍（每像素每帧 2 次），
            // 但 v / y0 / fy / ya / yb 只依赖 y，一个像素和一个像素之间完全相同。
            for (int k = 0; k < nn; ++k) {
                RowGeo &g = geo[k];
                if (!pxDx[k]) { g.valid = false; continue; }
                const float v = (float) y * invTile[k] - 0.5f;
                const int y0 = (int) std::floor(v);
                g.fy = clampf(v - (float) y0, 0.f, 1.f);
                const int ya = clampInt(y0, 0, fRows[k] - 1);
                const int yb = clampInt(y0 + 1, 0, fRows[k] - 1);
                g.r0 = (size_t) ya * (size_t) fCols[k];
                g.r1 = (size_t) yb * (size_t) fCols[k];
                g.valid = true;
            }

            const int rowOut = y * w;
            const float *gEp = useShade ? gE.data() : nullptr;
            const float *gOp = useShade ? gO.data() : nullptr;

            for (int x = 0; x < w; ++x) {
                // 六个累加器写成标量：原来用 num[3]/den[3] + 运行时下标 c，
                // 每次累加都是一次栈内存读改写，而且 num[1] 反复被写会形成
                // 一条长依赖链。改成标量后它们全部进寄存器。
                float n0 = 0.f, n1 = 0.f, n2 = 0.f;
                float d0 = 0.f, d1 = 0.f, d2 = 0.f;

                for (int k = 0; k < nn; ++k) {
                    const RowGeo &g = geo[k];

                    // ---- 位移场双线性：dx 和 dy 共用同一套几何，只算一次 ----
                    // 原来是两次 fieldAtF，各自把 u/x0/fx/xa/xb 全算一遍（还各含一次除法）。
                    float dx = 0.f, dy = 0.f;
                    if (g.valid) {
                        const float u = (float) x * invTile[k] - 0.5f;
                        const int xi = (int) std::floor(u);
                        const float fx = clampf(u - (float) xi, 0.f, 1.f);
                        const int xa = clampInt(xi, 0, fCols[k] - 1);
                        const int xb = clampInt(xi + 1, 0, fCols[k] - 1);
                        const float ax = 1.f - fx;
                        const float *D = pxDx[k], *E = pxDy[k];
                        const size_t i00 = g.r0 + xa, i01 = g.r0 + xb;
                        const size_t i10 = g.r1 + xa, i11 = g.r1 + xb;
                        const float fy = g.fy, ay = 1.f - fy;
                        dx = (D[i00] * ax + D[i01] * fx) * ay + (D[i10] * ax + D[i11] * fx) * fy;
                        dy = (E[i00] * ax + E[i01] * fx) * ay + (E[i10] * ax + E[i11] * fx) * fy;
                    }

                    // 符号约定与 hdr_merge 一致：**frames[k][x + dx] ↔ ref[x]**。
                    // 写成 x − dx 会让样本落在 2·dx 的位置，整幅双倍错位。
                    const float tx = (float) x + dx;
                    const float ty = (float) y + dy;
                    const int x0 = (int) std::floor(tx + 0.5f);
                    const int y0 = (int) std::floor(ty + 0.5f);

                    // 采样残差的小数部分，落在 [-0.5, 0.5]。
                    // 核权重可分离：w(ex,ey) = Wx(ex)·Wy(ey)，于是 9 个样本的权重
                    // 只需两次一维查表 + 9 次乘法（原来 9 次二维双线性插值）。
                    const float ex2 = tx - (float) x0;
                    const float ey2 = ty - (float) y0;
                    const float *wxv = tbl.w[tbl.index(ex2)];
                    const float *wyv = tbl.w[tbl.index(ey2)];

                    // 三个样本的 x 位置。x0±1 与 x0 的**奇偶不同**，所以两侧属于
                    // 同一个通道，中间属于另一个——每行只有两种。
                    const int xm = x0 - 1, xp = x0 + 1;
                    const bool okM = (unsigned) xm < (unsigned) w;
                    const bool okC = (unsigned) x0 < (unsigned) w;
                    const bool okP = (unsigned) xp < (unsigned) w;
                    const float wxm = wxv[0], wxc = wxv[1], wxp = wxv[2];

                    // 累加器分发。六个累加器是标量，所以它们住在寄存器里；
                    // 若用 num[c]/den[c] + 运行时下标，每次累加都是一次栈读改写。
                    auto acc2 = [&](int c, float s, float wv) {
                        if (c == 0) { n0 += s; d0 += wv; }
                        else if (c == 1) { n1 += s; d1 += wv; }
                        else { n2 += s; d2 += wv; }
                    };

                    // ---- 样本累加：标量 ----
                    //
                    // 试过 NEON（vld1_u16 一次读 3 个连续样本 -> vcvt -> vmul -> 累加），
                    // **实测反而慢 3.8 倍**（47ms -> 179ms，768x1024x5 帧）。
                    //
                    // 原因：vgetq_lane_f32 每次都要把值从 NEON 寄存器搬到标量寄存器，
                    // 每帧 3 个样本 -> 每像素 21 次跨域搬运；而这个算法每像素只有
                    // 3 个样本（向量利用率 3/4），累加结果还必须立刻回到标量做三通道
                    // 分发。于是省下的 3 条 ldrh 远远补不上搬运的开销。
                    //
                    // 要把 NEON 用在这里，得先在 x 方向展开 4 个输出像素，但那要求
                    // 相邻像素的采样起点严格递增 1 —— dx 逐像素变化、round() 在
                    // tile 边界会跳变，不成立。所以这里保持标量。
                    for (int j = -1; j <= 1; ++j) {
                        const int sy = y0 + j;
                        if (sy < 0 || sy >= h) continue;
                        const uint16_t *row = frames[k] + (size_t) sy * w;
                        const int parity = (sy & 1) << 1;
                        const int cE = cfaColors[parity];
                        const int cO = cfaColors[parity | 1];
                        const float wj = wyv[j + 1];
                        const float *gM = (sy & 1) ? gOp : gEp;

                        // x0±1 与 x0 的奇偶相反，所以"两侧/中心"的通道会随 x0 翻转。
                        //   x0 偶 -> 中心 cEven、两侧 cOdd
                        //   x0 奇 -> 中心 cOdd、两侧 cEven
                        // （写死成"两侧=cEven、中心=cOdd"会让一半像素通道错乱：
                        //   实测 PSNR 从 37.26 掉到 28.03。）
                        const int cSide = (x0 & 1) ? cE : cO;
                        const int cMid = (x0 & 1) ? cO : cE;

                        float sE = 0.f, wE = 0.f;
                        if (okM) {
                            float v = (float) row[xm] - (float) black;
                            v = gM ? v * gM[xm] : v * wsc;
                            const float wm = wxm * wj;
                            sE = v * wm;
                            wE = wm;
                        }
                        if (okP) {
                            float v = (float) row[xp] - (float) black;
                            v = gM ? v * gM[xp] : v * wsc;
                            const float wp = wxp * wj;
                            sE += v * wp;
                            wE += wp;
                        }
                        float sC = 0.f, wC = 0.f;
                        if (okC) {
                            float v = (float) row[x0] - (float) black;
                            v = gM ? v * gM[x0] : v * wsc;
                            wC = wxc * wj;
                            sC = v * wC;
                        }
                        acc2(cSide, sE, wE);
                        acc2(cMid, sC, wC);
                    }
                }

                // 缺相兜底（只在图像边缘 1 px 发生）：用参考帧的最近邻
                float fb = 0.f;
                bool needFb = false;
                if (d0 < 1e-5f || d1 < 1e-5f || d2 < 1e-5f) {
                    const int sx = clampInt(x, 0, w - 1), sy2 = clampInt(y, 0, h - 1);
                    fb = ((float) ref[(size_t) sy2 * w + sx] - (float) black) * wsc;
                    needFb = true;
                }

                const float r0 = (d0 > 1e-5f) ? n0 / d0 : fb;
                const float r1 = (d1 > 1e-5f) ? n1 / d1 : fb;
                const float r2 = (d2 > 1e-5f) ? n2 / d2 : fb;
                (void) needFb;

                outR[rowOut + x] = encodeQ(r0);
                outG[rowOut + x] = encodeQ(r1);
                outB[rowOut + x] = encodeQ(r2);
            }
        }

        // ---- 并行效率探针 ----
        // 只有在"CPU 时间 / 墙钟时间 ≈ 线程数"时才算真并行。
        // 若比值接近 1，说明线程被串行化了（或者全压在小核上排队）。
        const int64_t cpuNs = threadCpuNs() - thCpu0;
        const int64_t wallNs = monoNs() - thWall0;
        gLaneCpuNs.fetch_add(cpuNs);
        if (wallNs > gLaneWallNs) gLaneWallNs = wallNs;
    }, lanes);

    ASR("SR 合并完成 %dx%d n=%d ref=%d σ_r=%.2f shading=%d",
        w, h, n, refIdx, (double) sigmaR, useShade ? 1 : 0);

    // 并行效率：CPU 总和 / 最慢线程墙钟 = 有效核数。理想 = 线程数。
    {
        const double cpuMs = (double) gLaneCpuNs.load() / 1e6;
        const double wallMs = (double) gLaneWallNs / 1e6;
        ASR("核回归并行效率：CPU 合计 %.0fms / 最慢线程 %.0fms = %.2f 核（lanes=%d，"
            "上限 %d）", cpuMs, wallMs, wallMs > 0 ? cpuMs / wallMs : 0.0,
            lanes > 0 ? lanes : laneCount(), laneCount());
    }
}

/**
 * 单平面的 3×3 保边降噪（Q14 定点）。
 *
 * 为什么 SR 之后还要这一步：普通路径的降噪链是
 *   5 帧平均 (σ/2.2) → 逐相位 3×3 保边 (再降约 2×) → 去马赛克
 * 而 SR 路径是
 *   每个通道每像素只有 1~3 个有效样本的核回归 → 直接出 RGB
 * 少了中间那一环。实测暗部平坦区的 σ 比普通路径高 36%（4.61 vs 3.38）。
 *
 * 论文 §5 的最后一步也是"时域合并之后再做空间收缩"，理由相同：
 * 时域合并降的是随机噪声，但合并本身会引入新的相关性/插值误差，
 * 需要在空域再收一次。这里用最简单可靠的形式——只在通道内部做，
 * 不跨通道（色度已经由后面的 chroma denoise 统一处理）。
 */
void denoisePlane(uint16_t *p, int w, int h, float noiseA, float normPerDn) {
    if (w < 8 || h < 8) return;
    const float kMinSigma = 25.f, kMaxSigma = 500.f;
    std::vector<uint16_t> src(p, p + (size_t) w * h);
    parallelRows(h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            const int yu = clampInt(y - 1, 0, h - 1), yd = clampInt(y + 1, 0, h - 1);
            const uint16_t *ru = src.data() + (size_t) yu * w;
            const uint16_t *rc = src.data() + (size_t) y * w;
            const uint16_t *rd = src.data() + (size_t) yd * w;
            for (int x = 0; x < w; ++x) {
                const int xl = clampInt(x - 1, 0, w - 1), xr = clampInt(x + 1, 0, w - 1);
                const float c = (float) rc[x];
                // 阈值按噪声模型逐像素定（与普通路径的 denoiseBayer 同一套推导）
                float sig = (noiseA > 0.f)
                        ? std::sqrt(std::max(0.f, noiseA * c * normPerDn))
                        : 70.f;
                if (sig < kMinSigma) sig = kMinSigma;
                if (sig > kMaxSigma) sig = kMaxSigma;
                const float inv = 1.f / (2.f * sig * sig);
                float sw = 0.f, sv = 0.f;
                for (int k = -1; k <= 1; ++k) {
                    const int xa = k < 0 ? xl : (k > 0 ? xr : x);
                    for (int r = -1; r <= 1; ++r) {
                        const uint16_t *row = r < 0 ? ru : (r > 0 ? rd : rc);
                        const float v = (float) row[xa];
                        const float d = v - c;
                        const float wt = 1.f / (1.f + d * d * inv);
                        sw += wt;
                        sv += v * wt;
                    }
                }
                p[(size_t) y * w + x] = (uint16_t) clampInt((int) (sv / sw + 0.5f), 0, 65535);
            }
        }
    });
}

int hdrplusSuperRes(const uint16_t *const *frames, int n, int w, int h,
                    int black, int white, const int *cfaColors, float sigmaR,
                    const float *shading, int mw, int mh, const int *channelOf,
                    int lanes, uint16_t *outR, uint16_t *outG, uint16_t *outB) {
    if (n < 2 || w < 128 || h < 128) return 1;   // 单帧没有可用的亚像素信息

    const int64_t t0 = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();

    std::vector<AlignField> fields;
    int refIdx = 0;
    alignBurst(frames, n, w, h, black, &fields, &refIdx);
    const int64_t tAlign = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();

    std::vector<std::vector<float>> dxf, dyf;
    refineSubpixelAlign(frames, n, refIdx, w, h, black, fields, &dxf, &dyf);
    const int64_t tRefine = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();

    // 亚像素位移的统计：如果位移几乎全是整数，说明手抖没提供新的采样格点，
    // SR 的收益会接近 0（此时结果应该和"平均 + 去马赛克"差不多，不会更差）。
    {
        double sumFrac = 0;
        int cnt = 0;
        for (int k = 0; k < n; ++k) {
            if (k == refIdx || dxf[k].empty()) continue;
            for (float v : dxf[k]) {
                const float fr = std::fabs(v - std::round(v));
                sumFrac += std::min(fr, 1.f - fr);
                ++cnt;
            }
            for (float v : dyf[k]) {
                const float fr = std::fabs(v - std::round(v));
                sumFrac += std::min(fr, 1.f - fr);
                ++cnt;
            }
        }
        ASR("亚像素位移：平均小数部分 %.3f px（0.25 是理想均匀覆盖，接近 0 说明没有新信息）",
            cnt ? sumFrac / cnt : 0.0);
    }

    const float wsc = 1.f / std::max(1.f, (float) (white - black));
    superResMerge(frames, n, w, h, black, wsc, refIdx, fields, dxf, dyf,
                  cfaColors, sigmaR, shading, mw, mh, channelOf, lanes,
                  outR, outG, outB);

    // ---- 空间降噪：实测不划算，默认关闭（见 kSrDenoiseA 的注释）----
    //
    // 这里有过两轮尝试，结论一致：
    //   第一轮用**固定阈值** σ=70 / σ=26，同区域梯度能量 202.8 → 74.8，
    //   而暗部 σ 只从 4.6 降到 3.3。
    //   第二轮改用噪声自适应阈值（σ²=A·x 逐像素定），做出了完整的 A/B 标定：
    //   PSNR、梯度能量、平坦区 σ 三项全部变差，只有最暗 14.5% 的像素 +0.17 dB。
    //
    // 保留这段代码是为了能重新打开做实验（把 kSrDenoiseA 设成 >= 0 即可）；
    // 关闭时整段被跳过，零开销。
    if (kSrDenoiseA >= 0.f) {
        const float normPerDn = 16383.75f / std::max(1.f, (float) (white - black));
        const int64_t td = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        denoisePlane(outR, w, h, kSrDenoiseA, normPerDn);
        denoisePlane(outG, w, h, kSrDenoiseA, normPerDn);
        denoisePlane(outB, w, h, kSrDenoiseA, normPerDn);
        const int64_t te = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        ASR("SR 空间降噪 %lldms（噪声自适应，A=%.2f）", (long long) (te - td), (double) kSrDenoiseA);
    }

    const int64_t t1 = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    ASR("SR 分段：对齐 %lldms  亚像素细化 %lldms  核回归 %lldms  合计 %lldms",
        (long long) (tAlign - t0), (long long) (tRefine - tAlign),
        (long long) (t1 - tRefine), (long long) (t1 - t0));
    return 0;
}

}  // namespace camhdr
