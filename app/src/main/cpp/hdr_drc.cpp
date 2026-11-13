// 动态范围压缩：论文 §6 的 exposure fusion（Mertens 2007 的变体）。
//
// 论文原话拆开就是四步：
//   1. 从合并后的线性图**派生两个合成曝光**（一个短、一个长），方法是增益 + gamma；
//   2. 只做灰度、只做两档——省内存也省时间；
//   3. 用金字塔把两档"各自曝光正确的地方"融合起来；
//   4. 融合完把 gamma 反掉，再按**逐像素的色度比例**把颜色放回去。
//
// 第 4 步意味着：这一步只负责"亮度怎么映射"，颜色一点不碰。
// 所以实现成**增益图**是等价的，而且主循环里乘一次就行（通道比例天然不变）。
//
// 第 3 步的"金字塔"是关键，也是我第一版丢掉的东西。没有它，融合退化成一条全局曲线，
// 亮部和暗部只能二选一——这正是"看不出 HDR 优势"的根源。
#include "hdr_drc.h"

#include "hdr_common.h"

#include <android/log.h>

#include <algorithm>

#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "HdrDrc", __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "HdrDrc", __VA_ARGS__)

namespace camhdr {

namespace {

// 融合权重：论文只说"一个偏好中等亮度的固定亮度函数"，
// 参考实现把它写成正态分布 exp(-12.5·(t-0.5)²)，σ≈0.2。照抄。
inline float fusionWeight(float t) {
    const float d = t - 0.5f;
    return std::exp(-12.5f * d * d);
}

// 可分离 7 抽头高斯 [1,6,15,20,15,6,1]/64，与参考实现的 gauss_7x7 同量级
void blur7(float *a, float *tmp, int w, int h) {
    static const float k[7] = {1.f / 64, 6.f / 64, 15.f / 64, 20.f / 64, 15.f / 64, 6.f / 64, 1.f / 64};
    // 横向
    parallelRows(h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            const int row = y * w;
            for (int x = 0; x < w; ++x) {
                float s = 0;
                for (int i = 0; i < 7; ++i) s += k[i] * a[row + clampInt(x + i - 3, 0, w - 1)];
                tmp[row + x] = s;
            }
        }
    });
    // 纵向
    parallelRows(h, [&](int ys, int ye) {
        std::vector<float> out((size_t) w);
        for (int y = ys; y < ye; ++y) {
            const int row = y * w;
            for (int x = 0; x < w; ++x) {
                float s = 0;
                for (int i = 0; i < 7; ++i) {
                    s += k[i] * tmp[(size_t) clampInt(y + i - 3, 0, h - 1) * w + x];
                }
                out[x] = s;
            }
            std::copy(out.begin(), out.end(), a + row);
        }
    });
}

// 4×4 采样点在格内的偏移。
//
// **必须让相邻采样落在不同的 Bayer 相位**：采样步长和格宽都是偶数时（12MP 上
// step=8、sub=2），朴素的 i*sub 全部落在偶坐标上，16 个点全在同一个相位——
// 只有一个通道有样本，另外两个走 1e-4 的兜底值，线性亮度算出来是 0（甚至负数被夹掉），
// 融合增益于是恒等于 0，整幅乘零变成纯黑。
// 这里步长取偶数时给奇数号采样加 1 个像素，强行错开相位，四个相位各拿到 4 个样本。
inline int sampleOffset(int k, int sub, int ph) { return k * sub + ((k & 1) ? ph : 0); }

// 低分辨率代理：每块 step×step 里取 4×4 个点，按 CFA 相位分通道平均，
// 再做白平衡 + 3×3，得到线性亮度。够准，且只要 1/16 的采样量。
//
// `lrow` 是**亮度行**（0.299/0.587/0.114 加权后的矩阵行），不是矩阵的第二行。
// 用纯绿出行当亮度会出事：蓝多的像素（夜空、屏幕、蓝衣服）G 行是两个负系数，
// 算出来是负数、被 max(0) 夹成 0，融合就认为"这里本来就该是 0"，增益给到底，
// 暗部被越压越黑。亮度行不会因为色偏翻负。
//
// 暗角必须在这里也乘一次：主循环归一化时乘了，代理不乘的话四角会被当成"本来就暗"，
// exposure fusion 于是把四角提亮——实测白纸照的边角就比中心亮。两边必须用同一张表。
float proxyLuma(const uint16_t *raw, int w, int h, int px, int py, int step,
                const int *black4, float whiteScale,
                const float *wb, const float *lrow, const int *cfa,
                const float *shading, int mw, int mh, const int *channelOf) {
    float sum[3] = {0, 0, 0};
    int cnt[3] = {0, 0, 0};
    const int x0 = px * step, y0 = py * step;
    const int sub = std::max(1, step / 4);
    const int ph = (sub & 1) ? 0 : 1;      // 步长是偶数才需要错相位
    const bool useShade = shading && mw > 1 && mh > 1 && channelOf;
    for (int j = 0; j < 4; ++j) {
        const int sy = std::min(h - 1, y0 + sampleOffset(j, sub, ph));
        for (int i = 0; i < 4; ++i) {
            const int sx = std::min(w - 1, x0 + sampleOffset(i, sub, ph));
            const int pos = ((sy & 1) << 1) | (sx & 1);
            const int c = cfa[pos];
            float gain = 1.f;
            if (useShade) {
                const float gx = w > 1 ? (float) sx / (w - 1) * (mw - 1) : 0.f;
                const float gy = h > 1 ? (float) sy / (h - 1) * (mh - 1) : 0.f;
                const int ix0 = clampInt((int) gx, 0, mw - 1), ix1 = std::min(ix0 + 1, mw - 1);
                const int iy0 = clampInt((int) gy, 0, mh - 1), iy1 = std::min(iy0 + 1, mh - 1);
                const float wx = gx - ix0, wy = gy - iy0;
                const int ch = channelOf[pos];
                const float a = shading[((size_t) iy0 * mw + ix0) * 4 + ch];
                const float b = shading[((size_t) iy0 * mw + ix1) * 4 + ch];
                const float cc = shading[((size_t) iy1 * mw + ix0) * 4 + ch];
                const float d = shading[((size_t) iy1 * mw + ix1) * 4 + ch];
                const float top = a + (b - a) * wx;
                const float bot = cc + (d - cc) * wx;
                gain = top + (bot - top) * wy;
            }
            const float v = ((float) raw[(size_t) sy * w + sx] - (float) black4[pos]) * whiteScale * gain;
            sum[c] += v;
            cnt[c]++;
        }
    }
    // 某个通道一个样本都没采到（格比 2×2 还小）时，用另外两个通道的均值兜底。
    // 兜底值不能写成 1e-4：矩阵 G 行对 R/B 是负系数，给个假的极小值会把亮度算成负数。
    const float have = (cnt[0] ? sum[0] / cnt[0] : 0.f)
                     + (cnt[1] ? sum[1] / cnt[1] : 0.f)
                     + (cnt[2] ? sum[2] / cnt[2] : 0.f);
    const int nHave = (cnt[0] ? 1 : 0) + (cnt[1] ? 1 : 0) + (cnt[2] ? 1 : 0);
    const float fallback = nHave ? have / nHave : 0.f;
    const float g = cnt[1] ? sum[1] / cnt[1] : fallback;
    const float r = cnt[0] ? sum[0] / cnt[0] : fallback;
    const float b = cnt[2] ? sum[2] / cnt[2] : fallback;
    const float rw = r * wb[0], gw = g * wb[1], bw = b * wb[2];
    return std::max(0.f, rw * lrow[0] + gw * lrow[1] + bw * lrow[2]);
}

}  // namespace

void buildDrcGain(const uint16_t *raw, int w, int h,
                  const int *black4, float whiteScale,
                  const float *wbGains, const float *matrix, const int *cfaColors,
                  const float *shading, int mw, int mh, const int *channelOf,
                  float drcK, int gw, int gh, float *gain, float *outLin) {
    if (gw < 8 || gh < 8) return;
    const size_t n = (size_t) gw * gh;
    const int step = std::max(1, std::min(w / gw, h / gh));

    // ---- 1. 低分辨率线性亮度 ----
    // 亮度行 = 0.299·row0 + 0.587·row1 + 0.114·row2（BT.601 权重作用在矩阵行上），
    // 这样代理亮度和主循环输出的 Y 是同一个定义。
    const float lrow[3] = {
        0.299f * matrix[0] + 0.587f * matrix[3] + 0.114f * matrix[6],
        0.299f * matrix[1] + 0.587f * matrix[4] + 0.114f * matrix[7],
        0.299f * matrix[2] + 0.587f * matrix[5] + 0.114f * matrix[8],
    };
    std::vector<float> lin(n);
    parallelRows(gh, [&](int ys, int ye) {
        for (int py = ys; py < ye; ++py) {
            for (int px = 0; px < gw; ++px) {
                lin[(size_t) py * gw + px] =
                    proxyLuma(raw, w, h, px, py, step, black4, whiteScale,
                              wbGains, lrow, cfaColors, shading, mw, mh, channelOf);
            }
        }
    });

    if (outLin) std::copy(lin.begin(), lin.end(), outLin);

    if (drcK <= 1.01f) {
        std::fill(gain, gain + n, 1.f);
        return;
    }

    // ---- 2. 两个合成曝光（短 = 采集那一档，长 = 数字增益 K 倍）----
    std::vector<float> s(n), l(n);
    parallelRows(gh, [&](int ys, int ye) {
        for (int py = ys; py < ye; ++py) {
            for (int px = 0; px < gw; ++px) {
                const size_t i = (size_t) py * gw + px;
                s[i] = oetf(clampf(lin[i], 0.f, 1.f));
                l[i] = oetf(clampf(lin[i] * drcK, 0.f, 1.f));
            }
        }
    });

    // ---- 3. 权重 + 拉普拉斯金字塔融合（2 层，与参考实现一致）----
    std::vector<float> mS(n), tmp(n);
    parallelRows(gh, [&](int ys, int ye) {
        for (int py = ys; py < ye; ++py) {
            for (int px = 0; px < gw; ++px) {
                const size_t i = (size_t) py * gw + px;
                const float ws = fusionWeight(s[i]);
                const float wl = fusionWeight(l[i]);
                mS[i] = ws / std::max(1e-6f, ws + wl);
            }
        }
    });
    std::vector<float> mL(n);
    for (size_t i = 0; i < n; ++i) mL[i] = 1.f - mS[i];

    std::vector<float> gS(s), gL(l);
    blur7(gS.data(), tmp.data(), gw, gh);
    blur7(gL.data(), tmp.data(), gw, gh);

    std::vector<float> acc(n);
    parallelRows(gh, [&](int ys, int ye) {
        for (int py = ys; py < ye; ++py) {
            for (int px = 0; px < gw; ++px) {
                const size_t i = (size_t) py * gw + px;
                // 细节层用原始权重（参考实现如此：第 0 层的 mask 不模糊）
                acc[i] = (s[i] - gS[i]) * mS[i] + (l[i] - gL[i]) * mL[i];
            }
        }
    });

    // 低频层：图像与权重都再模糊一次
    std::vector<float> gS2(gS), gL2(gL), mS2(mS), mL2(mL);
    blur7(gS2.data(), tmp.data(), gw, gh);
    blur7(gL2.data(), tmp.data(), gw, gh);
    blur7(mS2.data(), tmp.data(), gw, gh);
    blur7(mL2.data(), tmp.data(), gw, gh);
    parallelRows(gh, [&](int ys, int ye) {
        for (int py = ys; py < ye; ++py) {
            for (int px = 0; px < gw; ++px) {
                const size_t i = (size_t) py * gw + px;
                acc[i] += gS2[i] * mS2[i] + gL2[i] * mL2[i];
            }
        }
    });

    // ---- 4. 反 gamma -> 线性，转成增益 ----
    //
    // 增益是**整体乘到 RGB 三通道**上的，一旦变成 0（代理亮度算成 0 时分母被钳住、
    // 分子也是 0），整幅就乘成纯黑——这个 bug 真出现过一次。
    //
    // 下限分两段，不是随手取的一个常数：
    //   · 线性亮度 <= 1 的像素，增益下限是 **1.0**。欠曝 K 倍的采集在暗部永远需要
    //     提亮，增益掉到 1 以下只可能是代理亮度算错（负数被夹成 0 / 采样缺通道），
    //     那种地方压暗等于把暗部细节抹掉。
    //   · 线性亮度 > 1 的像素（高光余量那部分），增益本来就该小于 1 把它们压回 1.0，
    //     所以不给下限，只防溢出。
    double linSum = 0;
    double gainSum = 0, gFloor = 0;
    float gainMin = 1e9f, gainMax = -1e9f;
    parallelRows(gh, [&](int ys, int ye) {
        for (int py = ys; py < ye; ++py) {
            for (int px = 0; px < gw; ++px) {
                const size_t i = (size_t) py * gw + px;
                const float outLin = oetfInv(clampf(acc[i], 0.f, 1.f));
                const float g = outLin / std::max(lin[i], 1.5e-3f);
                const float lo = lin[i] <= 1.f ? 1.f : 0.f;
                gain[i] = clampf(g, lo, 8.f);
            }
        }
    });

    // 代理亮度整幅为 0 说明采样出了问题（相位没覆盖到 / 通道全缺），
    // 此时任何逐像素增益都不可信，退回一个平坦增益 sqrt(K)：欠曝 K 倍的一半补回来，
    // 至少画面可见，而且不会因为分母趋零把图像压死。
    for (size_t i = 0; i < n; ++i) linSum += lin[i];
    const double linMean = linSum / (double) n;
    if (linMean < 1e-6) {
        const float flat = std::sqrt(std::max(1.f, drcK));
        std::fill(gain, gain + n, flat);
        ALOGE("DRC 代理亮度全为 0（均值 %.2e），退回平坦增益 %.2f", linMean, (double) flat);
        return;
    }

    // 平滑：增益本身是低频的，但它的**输入**不是——
    // 代理亮度是每 8×8 格取 4×4 个点算出来的，天然带一层 8 像素网格纹。
    // 只模糊一次（7 抽头，半径 3）压不掉：剩下的那点纹路乘进 RGB，就在
    // 平滑渐变（墙面、天空、木纹）上画出一圈圈等高线，也就是"同心环"。
    // 这里连做三次，等效半径约 5 个格（≈40 图像像素），远小于影调变化的尺度，
    // 但足以把网格纹抹平。顺手量一下残余起伏，>0.5% 就说明还有问题。
    blur7(gain, tmp.data(), gw, gh);
    blur7(gain, tmp.data(), gw, gh);
    blur7(gain, tmp.data(), gw, gh);

    // 残余高频起伏：拿一份再模糊两次的副本做参考，差值的均值就是"还会不会画圈"的指标
    double ripple = 0;
    {
        std::vector<float> refG(gain, gain + n);
        blur7(refG.data(), tmp.data(), gw, gh);
        blur7(refG.data(), tmp.data(), gw, gh);
        for (size_t i = 0; i < n; ++i) ripple += std::fabs(gain[i] - refG[i]);
        ripple /= (double) n;
    }

    for (size_t i = 0; i < n; ++i) {
        gainSum += gain[i];
        if (gain[i] <= 1.0001f) gFloor++;
        gainMin = std::min(gainMin, gain[i]);
        gainMax = std::max(gainMax, gain[i]);
    }
    ALOGD("DRC K=%.2f 代理亮度 均值 %.4f  增益 均值 %.2f 范围 [%.2f, %.2f] 贴地板 %.1f%% 残余起伏 %.3f%%",
          (double) drcK, linMean, gainSum / (double) n, (double) gainMin, (double) gainMax,
          gFloor * 100.0 / (double) n, ripple * 100.0 / std::max(1e-6, (double) (gainSum / n)));
}

}  // namespace camhdr
