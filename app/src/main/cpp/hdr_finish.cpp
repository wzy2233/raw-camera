// HDR 收尾：局部影调 + 色度降噪 + 色差校正 + 亮度锐化 + 色相微调 + 抖动。
//
// 顺序照论文 §6 的清单：色差校正在锐化之前、色相微调在锐化之后、抖动最后。
// 色差必须在锐化前做，因为锐化会把彩色镶边一起锐化，之后就分不清是细节还是色差。
#include "hdr_common.h"

#include <android/log.h>

#include <chrono>

#define AFIN(...) __android_log_print(ANDROID_LOG_DEBUG, "HdrFinish", __VA_ARGS__)

namespace camhdr {

namespace {
/** 单调毫秒，只用来量分段耗时 */
inline int64_t monoMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
}  // namespace


namespace {

// ---- 10 位像素的读写 ----
//
// 对外一律用"8bit 码单位"的浮点（0..255，步长 1/4 码）：
// 这样各阶段里所有既有阈值（4/14 码的门、236 的软膝、0.78 的高光截止…）
// 都保持原来的含义，不需要重新标定一遍；精度却比原来高 4 倍。
inline float qR(uint32_t p) { return pixR(p) * kPixTo255; }
inline float qG(uint32_t p) { return pixG(p) * kPixTo255; }
inline float qB(uint32_t p) { return pixB(p) * kPixTo255; }

inline uint32_t pack255(float r, float g, float b) {
    return pixPack((int) (r * kPixFrom255 + 0.5f),
                   (int) (g * kPixFrom255 + 0.5f),
                   (int) (b * kPixFrom255 + 0.5f));
}

inline float luminanceOf(uint32_t p) {
    return (77.f * pixR(p) + 150.f * pixG(p) + 29.f * pixB(p)) / 256.f * kPixTo255;
}

// 可分离 5 抽头高斯，核 [1,4,6,4,1]/16，边界夹取
void blur5(float *a, float *tmp, int w, int h) {
    parallelRows(h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            const int row = y * w;
            for (int x = 0; x < w; ++x) {
                const int xm2 = row + std::max(0, x - 2);
                const int xm1 = row + std::max(0, x - 1);
                const int xp1 = row + std::min(w - 1, x + 1);
                const int xp2 = row + std::min(w - 1, x + 2);
                tmp[row + x] = (a[xm2] + 4.f * a[xm1] + 6.f * a[row + x] +
                                4.f * a[xp1] + a[xp2]) * 0.0625f;
            }
        }
    });
    parallelRows(h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            const int ym2 = std::max(0, y - 2) * w;
            const int ym1 = std::max(0, y - 1) * w;
            const int yp1 = std::min(h - 1, y + 1) * w;
            const int yp2 = std::min(h - 1, y + 2) * w;
            const int row = y * w;
            for (int x = 0; x < w; ++x) {
                a[row + x] = (tmp[ym2 + x] + 4.f * tmp[ym1 + x] + 6.f * tmp[row + x] +
                              4.f * tmp[yp1 + x] + tmp[yp2 + x]) * 0.0625f;
            }
        }
    });
}

/**
 * 3×3 双边（保边平滑），作用在双分辨率色度平面上。
 *
 * 为什么需要它：后面的宽高斯（σ≈6 全分辨率像素）会跨越颜色边界平均，
 * 把邻居的颜色拖进来。Google 的 chroma_denoise 第一步就是 7×7 双边
 * （range 权重 exp(-d²/2σ²)），先把"同色区域内部"平滑掉，同时不跨越色差边界，
 * 然后才做宽高斯。我们之前直接宽高斯，缺的就是这一半。
 *
 * 权重用 1/(1+d²/2σ²) 而不是 exp：exp 在这个热循环里 12MP 要跑 5 千万次，
 * 实测会多花几百毫秒；这个有理式形状足够接近（都是 1 在 d=0、单调衰减），
 * 在 8bit 色度上肉眼分不出差别。
 */
void bilateral3(std::vector<float> &a, std::vector<float> &tmp, int w, int h, float sigma) {
    if (w < 3 || h < 3) return;
    const float inv = 1.f / std::max(1e-3f, 2.f * sigma * sigma);
    parallelRows(h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            const int row = y * w;
            const int up = (y > 0 ? y - 1 : y) * w;
            const int dn = (y < h - 1 ? y + 1 : y) * w;
            for (int x = 0; x < w; ++x) {
                const int xl = x > 0 ? x - 1 : x;
                const int xr = x < w - 1 ? x + 1 : x;
                const float c = a[row + x];
                float sw = 0.f, sv = 0.f;
                for (int r = -1; r <= 1; ++r) {
                    const int base = (r < 0) ? up : (r > 0 ? dn : row);
                    for (int k = -1; k <= 1; ++k) {
                        const int idx = base + ((k < 0) ? xl : (k > 0 ? xr : x));
                        const float v = a[idx];
                        const float d = v - c;
                        const float wgt = 1.f / (1.f + d * d * inv);
                        sw += wgt;
                        sv += v * wgt;
                    }
                }
                tmp[row + x] = sw > 1e-6f ? sv / sw : c;
            }
        }
    });
    a.swap(tmp);
}

inline float bilinear(const float *a, int w, int h, float u, float v) {
    float x = clampf(u, 0.f, (float) (w - 1));
    float y = clampf(v, 0.f, (float) (h - 1));
    int x0 = (int) x, y0 = (int) y;
    int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
    float fx = x - x0, fy = y - y0;
    float top = a[y0 * w + x0] * (1.f - fx) + a[y0 * w + x1] * fx;
    float bot = a[y1 * w + x0] * (1.f - fx) + a[y1 * w + x1] * fx;
    return top * (1.f - fy) + bot * fy;
}

// 一个像素的 YCbCr（BT.601 系数，与后面所有环节一致）
inline void yccOf(uint32_t p, float *y, float *cb, float *cr) {
    const float r = qR(p);
    const float g = qG(p);
    const float b = qB(p);
    const float yy = 0.299f * r + 0.587f * g + 0.114f * b;
    *y = yy;
    *cb = (b - yy) * 0.564f;
    *cr = (r - yy) * 0.713f;
}

/**
 * 色差校正（论文 §6 第 10 步）。
 *
 * 不建镜头模型——横向色差的表现就是"亮度边缘处冒出一条纯色镶边"，
 * 所以判据很简单：**亮度梯度大、而这条边上的色度又和周边不一致**。
 * 这类像素的色度不可信，用距离 2 像素外的四个邻居的色度中位数替换。
 *
 * 为什么不干脆全局模糊色度：那会把真实的颜色边界也抹掉。
 * 只在亮度边缘动手，平坦区（占绝大多数像素）一个都不碰。
 */
void caCorrect(uint32_t *px, int w, int h, float strength) {
    if (strength <= 0.01f || w < 8 || h < 8) return;
    parallelRows(h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            const int row = y * w;
            const int yUp = std::max(0, y - 1) * w;
            const int yDn = std::min(h - 1, y + 1) * w;
            const int yU2 = std::max(0, y - 2) * w;
            const int yD2 = std::min(h - 1, y + 2) * w;
            for (int x = 0; x < w; ++x) {
                const int xl = std::max(0, x - 1);
                const int xr = std::min(w - 1, x + 1);
                const float gx = std::fabs(luminanceOf(px[row + xr]) - luminanceOf(px[row + xl]));
                const float gy = std::fabs(luminanceOf(px[yDn + x]) - luminanceOf(px[yUp + x]));
                const float grad = gx + gy;
                if (grad < 40.f) continue;                    // 平坦区：不可能有色差镶边
                // 门槛从 26 抬到 40，而且上限压到 0.7：
                // 桌面反光、阴影交界这类**真实的**强颜色边界，梯度也很高，
                // 原来的门槛会把这些地方的真颜色也按邻域中位数替换掉，
                // 结果就是"亮暗交界的颜色被抹平"、两块区域看起来像拼上去的。
                const float t = smoothstep(40.f, 170.f, grad) * strength * 0.7f;
                if (t <= 0.02f) continue;

                const int xL2 = std::max(0, x - 2), xR2 = std::min(w - 1, x + 2);
                float cb[4], cr[4], yy;
                yccOf(px[row + xL2], &yy, &cb[0], &cr[0]);
                yccOf(px[row + xR2], &yy, &cb[1], &cr[1]);
                yccOf(px[yU2 + x], &yy, &cb[2], &cr[2]);
                yccOf(px[yD2 + x], &yy, &cb[3], &cr[3]);

                // 四点中位数：比均值抗单个邻居是另一条边的情况
                const float mb = median4(cb[0], cb[1], cb[2], cb[3]);
                const float mr = median4(cr[0], cr[1], cr[2], cr[3]);

                float y0, cb0, cr0;
                yccOf(px[row + x], &y0, &cb0, &cr0);
                const float ncb = cb0 + (mb - cb0) * t;
                const float ncr = cr0 + (mr - cr0) * t;

                float nr = y0 + ncr / 0.713f;
                float nb = y0 + ncb / 0.564f;
                float ng = (y0 - 0.299f * nr - 0.114f * nb) / 0.587f;
                px[row + x] = pack255(nr, ng, nb);
            }
        }
    });
}

/**
 * 色相微调（论文 §6 第 12 步）：把偏青偏紫的蓝推向"透亮的蓝"，并给蓝、绿一点饱和度。
 *
 * 蓝天的观感问题不是"不够蓝"，而是**蓝里混了品红**（大气散射 + 白平衡把 R 也抬了）。
 *
 * ---- 为什么重写 ----
 * 原来用 `smoothstep(nb) × smoothstep(nr)` 这种"单边门相乘"来划扇区。这在色相平面上
 * 不是一条平滑函数：两个单边门相乘会形成脊和平台，扇区边界处权重并不是从 0 平滑过渡，
 * 于是**色相一动、力度就跳**。再叠加当时的力度（紫色区把 Cr 砍 60%、蓝/绿加 16%/22%
 * 饱和度），同一片阴影里色度方向缓慢扫过扇区边界，就一半被砍红、一半没被砍——
 * 红块绿块就是这么来的。
 *
 * ---- 现在的做法 ----
 * 权重改成**严格只依赖色相角**的解析函数：三个钟形（品红↔蓝、蓝、绿）在色相环上做
 * 4 阶傅里叶拟合（误差 <0.6% 峰值）。运行时令 nr=cos h、nb=sin h，用倍角递推求
 * cos(kh)/sin(kh)，**不需要 atan2**，也**不存在任何扇区边界**——色相连续变化，
 * 力度就连续变化。
 *
 * 力度同时收小：
 *   · 偏品红的蓝：Cr × (1 − 0.22·F)，只做"少一点品红"，不再砍 60%；
 *   · 蓝 +7%、绿 +9% 饱和度（原 16%/22%）。
 *
 * 门槛按色度幅值平滑渐入：暗部色度只有十几码、噪声好几码，此时 (Cb,Cr) 的方向基本是
 * 噪声，按方向去推色等于在阴影里随机刷色。25~50 码以下不参与。
 */
namespace {

// 4 阶傅里叶系数：F(h) = c0 + Σ_{k=1..4} [a_k·cos(kh) + b_k·sin(kh)]
struct HueBasis {
    float c0;
    float a[5];   // a[1..4]，a[0] 未用
    float b[5];
};

/** 高光软膝的起点。从这里开始按指数收敛到 255，用于 hueTune 加饱和后的越界收回。 */
constexpr float kKneeStart = 236.f;

// 色相轴的物理位置（在本工程的 (Cr, Cb) 坐标里，h = atan2(Cb, Cr)）：
//
//   红 −9°   橙 −46°   品红 +38°   蓝 +99°   青 +161°   绿 −141°
//
// 这几个角度不是猜的，是代入纯色算出来的（例如纯蓝 R=G=0/B=1 → Cr=−0.081、Cb=0.500
// → h=99°）。实测这张机器的**天空落 +117°~+145°**，也就是蓝和青之间，**不在品红区**。
// 所以品红修正的钟形必须收紧在 38° 附近，绝不能伸到 120° 去——伸过去等于给天空
// 砍 Cr，把蓝推向青，和"更蓝"正好相反。

// 品红钟形：中心 0.66 rad(38°)、宽 0.45。到蓝(99°)只剩 0.06，到天空(123°)是 0.004。
constexpr HueBasis kPurple = {0.179530f,
                              {0.f, 0.256321f, 0.059443f, 0.132428f, 0.034165f},
                              {0.f, 0.198941f, 0.231985f, -0.057443f, -0.062291f}};

// 饱和增益：0.07×蓝(1.90rad=109°) + 0.09×绿(−2.46rad=−141°)，已按系数合并
constexpr HueBasis kSat = {0.044681f,
                           {0.f, -0.040447f, -0.007717f, 0.006128f, -0.000718f},
                           {0.f, 0.004170f, 0.009482f, -0.007305f, 0.001153f}};

// 在 (nr, nb) 单位圆上求值：倍角递推，无需三角函数
inline float hueEval(const HueBasis &k, float nr, float nb,
                     float *cs, float *sn) {
    cs[1] = nr;
    sn[1] = nb;
    for (int i = 2; i <= 4; ++i) {
        const float pc = cs[i - 1], ps = sn[i - 1];
        cs[i] = pc * nr - ps * nb;
        sn[i] = ps * nr + pc * nb;
    }
    float f = k.c0;
    for (int i = 1; i <= 4; ++i) f += k.a[i] * cs[i] + k.b[i] * sn[i];
    return f;
}

}  // namespace


/**
 * 锐化后的 halo 抑制（Polyblur, Delbracio et al. 2021, §III-C 的闭式解）。
 *
 * ---- 为什么需要 ----
 * 我们的锐化是"加高频"，本质上是个**局部**算子。局部算子无法区分
 * "真实的细节" 和 "被增强出来的过冲"，于是强边缘两侧一定会出现梯度反转
 * （白边/黑边），也就是俗称的 halos。
 *
 * 论文的做法很干净：在**梯度方向发生反转**的像素上按比例退回原图，
 * 其余像素完全采用锐化结果。公式（论文式 15-17）：
 *
 *     M(x) = −∇v(x)·∇v̄(x)                    // 梯度反转量，>0 表示反转
 *     z(x) = max( M/(|∇v|² + M), 0 )          // 退回原图的权重
 *     out  = z·v + (1−z)·v̄
 *
 * z 的来由：要求输出梯度与原图梯度同向（∇v·∇out > 0），解出 z 的上界正好是
 * 上面那个式子。它逐像素、无搜索、无迭代，代价可以忽略。
 *
 * ---- 我们这里的近似 ----
 * 论文是逐通道混合。我们只保留了**亮度**的快照（省 48MB 的 RGB 拷贝），
 * 混合出目标亮度后按比例缩三通道——和去雾那步同一个思路，保色相不变。
 *
 * [lumaBefore] 是锐化前的 10bit 亮度快照。
 */
void haloGuard(uint32_t *px, const uint16_t *lumaBefore, int w, int h) {
    if (w < 3 || h < 3) return;
    parallelRows(h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            const int ym = y > 0 ? y - 1 : y;
            const int yp = y < h - 1 ? y + 1 : y;
            const int row = y * w;
            for (int x = 0; x < w; ++x) {
                const int xm = x > 0 ? x - 1 : x;
                const int xp = x < w - 1 ? x + 1 : x;

                const uint32_t p = px[row + x];
                const int yNow = (77 * pixR(p) + 150 * pixG(p) + 29 * pixB(p)) >> 8;
                const int yOld = lumaBefore[row + x];

                // 中心差分（边界退化成单侧差分，对判据够用）
                const int gxOld = (int) lumaBefore[row + xp] - lumaBefore[row + xm];
                const int gyOld = (int) lumaBefore[yp * w + x] - lumaBefore[ym * w + x];
                const int gxNew = ((77 * pixR(px[row + xp]) + 150 * pixG(px[row + xp]) + 29 * pixB(px[row + xp])) >> 8)
                                - ((77 * pixR(px[row + xm]) + 150 * pixG(px[row + xm]) + 29 * pixB(px[row + xm])) >> 8);
                const int gyNew = ((77 * pixR(px[yp * w + x]) + 150 * pixG(px[yp * w + x]) + 29 * pixB(px[yp * w + x])) >> 8)
                                - ((77 * pixR(px[ym * w + x]) + 150 * pixG(px[ym * w + x]) + 29 * pixB(px[ym * w + x])) >> 8);

                // M = −∇v·∇v̄；只有反转（M>0）才需要处理
                const float M = -(float) (gxOld * gxNew + gyOld * gyNew);
                if (M <= 0.f) continue;

                const float g2 = (float) (gxOld * gxOld + gyOld * gyOld);
                if (g2 < 1e-3f) continue;
                float z = M / (g2 + M);
                if (z > 0.95f) z = 0.95f;      // 留一点锐化结果，避免整条边完全退回
                if (z <= 0.01f) continue;

                const float target = z * (float) yOld + (1.f - z) * (float) yNow;
                if (yNow <= 0) continue;
                const float scl = target / (float) yNow;
                const int r2 = clampInt((int) (pixR(p) * scl + 0.5f), 0, 1023);
                const int g2v = clampInt((int) (pixG(p) * scl + 0.5f), 0, 1023);
                const int b2 = clampInt((int) (pixB(p) * scl + 0.5f), 0, 1023);
                px[row + x] = ((uint32_t) r2 << 20) | ((uint32_t) g2v << 10) | (uint32_t) b2;
            }
        }
    });
}

void hueTune(uint32_t *px, int w, int h, float strength) {
    if (strength <= 0.01f) return;
    const float s = strength;
    parallelRows(h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            const int row = y * w;
            for (int x = 0; x < w; ++x) {
                float yy, cb, cr;
                yccOf(px[row + x], &yy, &cb, &cr);
                const float m = std::sqrt(cb * cb + cr * cr);
                if (m < 1e-4f) continue;
                // 门槛按**亮度**，不按色度幅值。
                //
                // 原来判据是色度幅值 25~50，本意是"别在暗部把噪声当颜色放大"。
                // 但它把**浅色的天空**也一起挡掉了：实测这张机器天空的平均色度只有 21，
                // 低于 25，于是整条 hueTune 对天空完全不生效（量出来色度 21.46→21.49）。
                // 亮度才是噪声的正确代理——暗部才会被噪声淹没，中高调再淡的颜色也是可信的。
                const float gate = smoothstep(18.f, 45.f, yy);
                if (gate <= 0.002f) continue;

                const float nr = cr / m, nb = cb / m;
                float cs[5], sn[5];
                const float fPurple = hueEval(kPurple, nr, nb, cs, sn);
                const float fSat = hueEval(kSat, nr, nb, cs, sn);

                // 品红的蓝 -> 压一点 Cr（色相朝蓝轴转，幅度有界）
                float cr2 = cr * (1.f - 0.22f * fPurple * gate * s);
                float cb2 = cb;
                // 蓝、绿加饱和（中心 +7% / +9%，随色相平滑分布）
                const float gain = 1.f + fSat * gate * s;
                cb2 *= gain;
                cr2 *= gain;

                float r2 = yy + cr2 / 0.713f;
                float b2 = yy + cb2 / 0.564f;
                float g2 = (yy - 0.299f * r2 - 0.114f * b2) / 0.587f;

                // 加饱和后可能顶出范围：按比例收色度（保持色相），用 **C1 软膝**而不是硬截断。
                //
                // 原来写的是 `if (mx > 252) k = (252-yy)/(mx-yy)`。值在 mx=252 处虽然连续
                // （k=1），但**斜率从 1 突跳到 0**；而且所有超出的高光都被压到同一个 252，
                // 形成一片死平的高光（亮饱和色块）。这两件事都会在渐变里留下边界。
                // 现在从 236 起按指数膝收敛到 255：236 处值与一阶导都连续，
                // 超出的部分平滑压缩，既不会出现平台，也不浪费 253~255 这三级。
                const float mx = std::max(r2, std::max(g2, b2));
                if (mx > kKneeStart) {
                    const float span = 255.f - kKneeStart;
                    const float mxS = kKneeStart + span * (1.f - std::exp(-(mx - kKneeStart) / span));
                    const float k = (mxS - yy) / std::max(1e-3f, mx - yy);
                    r2 = yy + (r2 - yy) * k;
                    g2 = yy + (g2 - yy) * k;
                    b2 = yy + (b2 - yy) * k;
                }
                px[row + x] = pack255(r2, g2, b2);
            }
        }
    });
}

}  // namespace

/**
 * 10bit 打包像素 → 8bit ARGB（含 TPDF 抖动）。
 *
 * 抽成独立函数的原因：高位深输出（HEIF / PNG16）需要在**不量化**的情况下
 * 拿走 10bit 数据，之后再调用这里生成预览用的 8bit 图。
 */
void quantizeToArgb(uint32_t *px, int w, int h) {
    //
    // 10 位像素在这里才落到 8 位，**整条链只量化这一次**。
    // 以前每个阶段结束都回到 8bit（tmo/nr/ca/hue/锐化各一次，共 6 次），
    // 而每个 ×1.5~2 的乘法在 8bit 上都会丢级：实测一条 6..255 的渐变，
    // 本该用 250 级，实际只剩 145 级，最大跳变 3 码。丢掉的级只能靠抖动补回来，
    // 代价就是平白多出来的噪声。
    //
    // 抖动改成 **TPDF（三角分布）**，逐通道独立：
    //   · 原来的 ±1 均匀抖动方差 0.5（σ≈0.71 码），TPDF 只有 1/6（σ≈0.41），
    //     同样能打散台阶，噪声小 40%；
    //   · 原来色度走的是"R 加 1、B 减 1"的反向抖，σ=1.0 码，是三通道里最大的一项。
    //     现在 10 位存储本身就有 4 个亚级，逐通道 TPDF 就够了，不需要那么大的幅度。
    parallelRows(h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            const int row = y * w;
            for (int x = 0; x < w; ++x) {
                const uint32_t p = px[row + x];
                float d[3];
                // ---- 蓝噪声抖动（HDR+ §6 第 13 步）----
                //
                // 原来用的是 TPDF 白噪声（两次 LCG 相减）。白噪声的问题是：
                // 它把量化误差均匀撒在空间上，暗部平场里会留下一层"散粒感"，
                // 观感上像加了噪点。蓝噪声把误差推到高频，人眼在正常观看距离
                // 几乎看不见，同样能打散色阶。
                //
                // 这里用 Interleaved Gradient Noise（Jimenez 2014）代替论文里
                // 的预计算表：解析式、无表、无内存，空间分布已经足够蓝。
                // 三个通道错开相位，避免三通道同向抖动造成亮度闪烁。
                for (int c = 0; c < 3; ++c) {
                    const float fx = (float) x + (float) c * 17.0f;
                    const float fy = (float) y + (float) c * 31.0f;
                    const float a = clampf(0.06711056f * fx + 0.00583715f * fy, 0.f, 1.f);
                    const float b = a * 52.9829189f;
                    d[c] = b - std::floor(b);      // [0,1) 的蓝噪声阈值
                }
                // floor(v + d)，d∈[0,1)：期望值等于 v，无偏；且误差被推到高频
                const int r = clampInt((int) std::floor(qR(p) + d[0]), 0, 255);
                const int g = clampInt((int) std::floor(qG(p) + d[1]), 0, 255);
                const int b = clampInt((int) std::floor(qB(p) + d[2]), 0, 255);
                px[row + x] = 0xFF000000u | ((uint32_t) r << 16) | ((uint32_t) g << 8) | (uint32_t) b;
            }
        }
    });}

// 双线性上采样的横向索引表：x 坐标与权重与行号无关，预计算一次。
struct UpXTab {
    std::vector<int> x0, x1;
    std::vector<float> fx;
    void build(int dw, int sw) {
        x0.resize(dw); x1.resize(dw); fx.resize(dw);
        for (int x = 0; x < dw; ++x) {
            const float sx = clampf((x + 0.5f) * sw / dw - 0.5f, 0.f, (float) (sw - 1));
            int i = (int) sx;
            if (i >= sw - 1) i = sw - 2;
            if (i < 0) i = 0;
            x0[x] = i;
            x1[x] = std::min(i + 1, sw - 1);
            fx[x] = sx - i;
        }
    }
};

// 一行的双线性放大（y 固定），结果写 out[0..dw)
inline void upRow(const float *s, int sw, const UpXTab &t, int y0, int y1, float fy,
                  int dw, float *out) {
    const float *r0 = s + (size_t) y0 * sw;
    const float *r1 = s + (size_t) y1 * sw;
    for (int x = 0; x < dw; ++x) {
        const float a = r0[t.x0[x]] + (r0[t.x1[x]] - r0[t.x0[x]]) * t.fx[x];
        const float b = r1[t.x0[x]] + (r1[t.x1[x]] - r1[t.x0[x]]) * t.fx[x];
        out[x] = a + (b - a) * fy;
    }
}

/**
 * 多尺度锐化（论文 §6 第 11 步，Farbman et al. 2011 的 edge-aware 细节增强）。
 *
 * ---- 为什么换掉 3×3 USM ----
 * USM 的模糊核半径只有 1 像素，它增强的是"相邻两个像素之间的对比"。
 * 而人眼判断"这张图清不清楚"，看的是**几个到几十个像素尺度的结构对比**——
 * 树叶的层次、织物的经纬、砖缝的立体感。3×3 对这些一点作用都没有，
 * 所以它只能让画面"变脆"（边缘出现一圈白边），不能让画面"变清楚"。
 *
 * 而且原来的 `if (|d| <= 3.5 码) 不动，否则全额放大` 是一个**硬阈值**：
 * 渐变里 3.4 码和 3.6 码的两个像素被区别对待，锐化本身在画面上留下了边界。
 *
 * ---- 现在的做法 ----
 * 亮度做 4 层高斯金字塔（σ ≈ 1.5 / 3 / 6 / 12 全分辨率像素），
 * 相邻层相减得到三个**频段**，每个频段按自己的幅度加权后加回去：
 *
 *   D_i = L_i − up(L_{i+1})        第 i 个频段
 *   w_i = 1 − exp(−(D_i/τ_i)²)     幅度越小越可能是噪声，权重越小
 *   out = L0 + Σ gain_i · w_i · D_i
 *
 * 三个好处：
 *   · τ 随层数增大（粗频段本来就是低频，允许更大的幅度），
 *     所以暗部细颗粒被压住、真实结构照常增强；
 *   · 权重是**连续**的，不存在"某个码值以上才锐化"的台阶；
 *   · 每层都在自己的分辨率上算（3 Mpix / 0.75 Mpix / 0.19 Mpix），
 *     粗频段的卷积成本降到 1/16、1/64，比"全分辨率做三次大核高斯"快一个量级。
 *
 * 重建用逐级 collapse（B2→B1→L0），不需要保存整张上采样图：
 * 每级只多一行缓冲，峰值内存 ≈ 64 MB（4 层 float 金字塔）。
 */
void sharpenMultiScale(uint32_t *px, int w, int h, float amount) {
    if (amount <= 0.01f || w < 16 || h < 16) return;
    const auto tBegin = std::chrono::steady_clock::now();

    constexpr int LV = 4;
    // 三个频段的增益与噪声门限（单位：0..255 码）。
    // τ 的取值依据：合并后残余噪声在 10bit 上是 1 码左右 = 0.25 个"255 码单位"，
    // 取 τ1=1.0 意味着"幅度 1 码以下基本不放大"，而真实细纹理通常在 2~3 码以上。
    // τ2/τ3 更大是因为粗频段的幅度天然更大（一栋楼的轮廓差几十码很正常）。
    constexpr float kGain[3] = {0.60f, 0.50f, 0.35f};
    constexpr float kTau[3] = {1.00f, 1.60f, 2.40f};

    // ---- 0. 亮度金字塔 ----
    struct Lev {
        int w = 0, h = 0;
        std::vector<float> a;
    };
    Lev pyr[LV];

    pyr[0].w = w;
    pyr[0].h = h;
    pyr[0].a.resize((size_t) w * h);
    parallelRows(h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            const int row = y * w;
            for (int x = 0; x < w; ++x) pyr[0].a[row + x] = luminanceOf(px[row + x]);
        }
    });

    for (int i = 1; i < LV; ++i) {
        const Lev &s = pyr[i - 1];
        Lev d;
        d.w = std::max(2, (s.w + 1) / 2);
        d.h = std::max(2, (s.h + 1) / 2);
        d.a.resize((size_t) d.w * d.h);
        const int sw = s.w, sh = s.h, dw = d.w, dh = d.h;
        const float *sa = s.a.data();
        float *da = d.a.data();
        parallelRows(dh, [&](int ys, int ye) {
            for (int y = ys; y < ye; ++y) {
                const int sy0 = std::min(sh - 1, 2 * y);
                const int sy1 = std::min(sh - 1, 2 * y + 1);
                const float *r0 = sa + (size_t) sy0 * sw;
                const float *r1 = sa + (size_t) sy1 * sw;
                float *o = da + (size_t) y * dw;
                for (int x = 0; x < dw; ++x) {
                    const int sx0 = std::min(sw - 1, 2 * x);
                    const int sx1 = std::min(sw - 1, 2 * x + 1);
                    o[x] = 0.25f * (r0[sx0] + r0[sx1] + r1[sx0] + r1[sx1]);
                }
            }
        });
        // 降采样之后再平滑一次：2×2 盒式的频响在 Nyquist 处不为零，
        // 不补这一下，粗层会把细纹理"折叠"成假的低频结构（摩尔纹）。
        std::vector<float> tmp(d.a.size());
        blur5(d.a.data(), tmp.data(), d.w, d.h);
        pyr[i] = std::move(d);
    }

    // ---- 1. 逐级重建，顺便加细节 ----
    //
    // pyr[i].a 里现在是该层的 L_i；下面每一轮把它改写成"增强后的 B_i"。
    // 最粗层（LV-1）不动，它就是 B_{LV-1}。
    for (int i = LV - 2; i >= 1; --i) {
        const Lev &src = pyr[i + 1];       // 更粗的一层（已经是 B_）
        Lev &dst = pyr[i];                 // L_i，将要被改写成 B_i
        const int sw = src.w, sh = src.h;
        const int dw = dst.w, dh = dst.h;
        const float *sa = src.a.data();
        float *da = dst.a.data();

        // 频段序号：i=2 对应第 3 个频段（最粗），i=1 对应第 2 个
        const int band = i;                // 0..2
        const float g = kGain[band] * amount;
        const float invTau2 = 1.f / (kTau[band] * kTau[band]);

        UpXTab tab;
        tab.build(dw, sw);

        parallelRows(dh, [&](int ys, int ye) {
            // **每个线程一份**行缓冲。放在 lambda 外面共用会互相覆盖：
            // 线程 A 写完 row 还没读完，线程 B 已经把它改写成了自己那一行，
            // 结果每列的亮度取自不同的源行——满幅竖条纹，而且随线程调度变化。
            std::vector<float> row((size_t) dw);
            for (int y = ys; y < ye; ++y) {
                const float sy = clampf((y + 0.5f) * sh / dh - 0.5f, 0.f, (float) (sh - 1));
                int y0 = (int) sy;
                if (y0 >= sh - 1) y0 = sh - 2;
                if (y0 < 0) y0 = 0;
                const int y1 = std::min(y0 + 1, sh - 1);
                upRow(sa, sw, tab, y0, y1, sy - y0, dw, row.data());
                float *o = da + (size_t) y * dw;
                for (int x = 0; x < dw; ++x) {
                    const float u = row[x];
                    const float li = o[x];       // 本层原始值 L_i（**基底**，不是 u）
                    const float d = li - u;      // 该频段的高频
                    const float wgt = 1.f - std::exp(-d * d * invTau2);
                    // 基底必须是 L_i。写成 u 的话 amount=0 时得到的是"1/2 分辨率
                    // 上采样回来的模糊图"，整幅会糊掉（血泪教训：梯度能量掉了 88%）。
                    o[x] = li + g * wgt * d;
                }
            }
        });
    }

    // ---- 2. 最高分辨率一级：直接写回 px（不再多保存一份 L0）----
    {
        const Lev &src = pyr[1];           // B_1
        const int sw = src.w, sh = src.h;
        const float *sa = src.a.data();
        const float *l0 = pyr[0].a.data();
        const float g = kGain[0] * amount;
        const float invTau2 = 1.f / (kTau[0] * kTau[0]);

        UpXTab tab;
        tab.build(w, sw);

        parallelRows(h, [&](int ys, int ye) {
            // 同样每线程一份（见上一处的说明）
            std::vector<float> row((size_t) w);
            for (int y = ys; y < ye; ++y) {
                const float sy = clampf((y + 0.5f) * sh / h - 0.5f, 0.f, (float) (sh - 1));
                int y0 = (int) sy;
                if (y0 >= sh - 1) y0 = sh - 2;
                if (y0 < 0) y0 = 0;
                const int y1 = std::min(y0 + 1, sh - 1);
                upRow(sa, sw, tab, y0, y1, sy - y0, w, row.data());

                const int rowIdx = y * w;
                for (int x = 0; x < w; ++x) {
                    const uint32_t p = px[rowIdx + x];
                    const float luma = l0[rowIdx + x];
                    const float u = row[x];
                    const float d = luma - u;
                    const float wgt = 1.f - std::exp(-d * d * invTau2);
                    // 同上一级：基底是原始亮度 luma，不是上采样值 u
                    const float target = luma + g * wgt * d;

                    // 暗部的缩放要平滑收敛（和局部影调同一套逻辑）：
                    // 亮度 4 码以下本来就没有可增强的结构，直接按 1 处理，
                    // 4..14 码之间线性过渡，避免在阴影的平滑过渡上留一道硬边。
                    const float want = clampf(target / std::max(luma, 1e-3f), 0.6f, 1.7f);
                    const float gate = smoothstep(4.f, 14.f, luma);
                    const float scale = 1.f + (want - 1.f) * gate;

                    px[rowIdx + x] = pack255(qR(p) * scale, qG(p) * scale, qB(p) * scale);
                }
            }
        });
    }

    AFIN("多尺度锐化 %lldms（4 层，%dx%d）",
         (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::steady_clock::now() - tBegin).count(), w, h);
}

void finish(uint32_t *px, int w, int h,
            float tmoStrength, float chromaStrength, float sharpenAmount,
            float caStrength, float hueStrength,
            int enableTmo, int enableNr, int quantize) {

    const int64_t tS0 = monoMs();
    // ---------------- 1. 局部影调 ----------------
    if (enableTmo && tmoStrength > 0.01f) {
        const int DS = 16;
        int lw = std::max(4, w / DS), lh = std::max(4, h / DS);
        std::vector<float> low((size_t) lw * lh, 0.f);

        const float sx = (float) w / lw, sy = (float) h / lh;
        for (int ly = 0; ly < lh; ++ly) {
            int y0 = (int) (ly * sy), y1 = std::min(h, (int) ((ly + 1) * sy));
            if (y1 <= y0) y1 = std::min(h, y0 + 1);
            for (int lx = 0; lx < lw; ++lx) {
                int x0 = (int) (lx * sx), x1 = std::min(w, (int) ((lx + 1) * sx));
                if (x1 <= x0) x1 = std::min(w, x0 + 1);
                long sum = 0; int cnt = 0;
                for (int y = y0; y < y1; y += 2) {
                    const int row = y * w;
                    for (int x = x0; x < x1; x += 2) {
                        const uint32_t p = px[row + x];
                        sum += 77L * pixR(p) + 150L * pixG(p) + 29L * pixB(p);
                        ++cnt;
                    }
                }
                low[ly * lw + lx] = cnt ? (float) sum / cnt / 256.f * kPixTo255 : 0.f;
            }
        }
        std::vector<float> tmp(low.size());
        blur5(low.data(), tmp.data(), lw, lh);
        blur5(low.data(), tmp.data(), lw, lh);

        // 基底抬升：对数域钟形窗（峰值在 base≈12%）。
        // 不用 gamma：t^0.72 在 0 点斜率无穷，会把纯黑也抬起来，整幅发灰。
        const float PEAK = 0.12f, SIGMA = 0.85f, AMOUNT = 0.52f;
        float liftLut[1025];
        for (int i = 0; i <= 1024; ++i) {
            float b = i / 4.f;
            float t = clampf(b / 255.f, 1e-4f, 1.f);
            float lg = std::log(t / PEAK);
            float wBell = std::exp(-(lg * lg) / (2.f * SIGMA * SIGMA));
            liftLut[i] = b * (1.f + AMOUNT * wBell * tmoStrength);
        }
        const float detailGain = 1.f + 0.25f * tmoStrength;

        parallelRows(h, [&](int ys, int ye) {
            for (int y = ys; y < ye; ++y) {
                const float v = (y + 0.5f) * lh / h - 0.5f;
                const int row = y * w;
                for (int x = 0; x < w; ++x) {
                    const uint32_t p = px[row + x];
                    float r = qR(p), g = qG(p), b = qB(p);
                    float luma = (77.f * r + 150.f * g + 29.f * b) / 256.f;

                    float u = (x + 0.5f) * lw / w - 0.5f;
                    float base = bilinear(low.data(), lw, lh, u, v);
                    float fi = clampf(base * 4.f, 0.f, 1024.f);
                    int i0 = std::min((int) fi, 1023);
                    float f = fi - i0;
                    float targetBase = liftLut[i0] + (liftLut[i0 + 1] - liftLut[i0]) * f;

                    float detail = luma - base;
                    float lumaOut = targetBase + detail * detailGain;
                    // 暗部不能"开关式"处理。
                    //
                    // 原来写的是 luma > 6 ? clamp(lumaOut/luma, 0.35, 2.6) : 1，
                    // 于是亮度 5.9 和 6.1 的两个像素，一个完全不缩放、一个缩放 1.3 倍——
                    // 在阴影的平滑过渡上就是一道**硬边**，看起来像"两块阴影色块接在一起"。
                    // 改成 4..14 码之间线性过渡：更暗的地方本来也没什么可缩放的，
                    // 跨过 14 码才是完整的局部影调。
                    const float want = clampf(lumaOut / std::max(luma, 1e-3f), 0.35f, 2.6f);
                    const float gate = smoothstep(4.f, 14.f, luma);
                    const float scale = 1.f + (want - 1.f) * gate;
                    px[row + x] = pack255(r * scale, g * scale, b * scale);
                }
            }
        });
    }

    const int64_t tS1 = monoMs();
    // ---------------- 2. 色度降噪 ----------------
    if (enableNr && chromaStrength > 0.01f) {
        const int cw = (w + 1) / 2, ch = (h + 1) / 2;
        std::vector<float> cb((size_t) cw * ch), cr((size_t) cw * ch);
        std::vector<float> lum((size_t) cw * ch);

        parallelRows(ch, [&](int ys, int ye) {
            for (int cy = ys; cy < ye; ++cy) {
                const int y0 = cy * 2, y1 = std::min(h, y0 + 2);
                for (int cx = 0; cx < cw; ++cx) {
                    const int x0 = cx * 2, x1 = std::min(w, x0 + 2);
                    float sr = 0, sg = 0, sb = 0; int cnt = 0;
                    for (int y = y0; y < y1; ++y) {
                        const int row = y * w;
                        for (int x = x0; x < x1; ++x) {
                            const uint32_t p = px[row + x];
                            sr += pixR(p); sg += pixG(p); sb += pixB(p);
                            ++cnt;
                        }
                    }
                    float r = sr / cnt * kPixTo255, g = sg / cnt * kPixTo255, b = sb / cnt * kPixTo255;
                    float yy = 0.299f * r + 0.587f * g + 0.114f * b;
                    cb[cy * cw + cx] = (b - yy) * 0.564f;
                    cr[cy * cw + cx] = (r - yy) * 0.713f;
                    lum[cy * cw + cx] = yy;
                }
            }
        });

        std::vector<float> tmp(cb.size());

        // 边缘强度图。色度平滑必须是**看邻居是什么才决定信多少**，
        // 否则半径一放大就把强色物体的颜色抹到旁边的暗背景上
        // （红色键盘边缘一圈粉、暗处一片紫，就是这么来的）。
        std::vector<float> edge(lum);
        std::vector<float> lumBlur(lum);
        blur5(lumBlur.data(), tmp.data(), cw, ch);
        blur5(lumBlur.data(), tmp.data(), cw, ch);
        parallelRows(ch, [&](int ys, int ye) {
            for (int cy = ys; cy < ye; ++cy) {
                const int r0 = cy * cw;
                for (int cx = 0; cx < cw; ++cx) {
                    edge[r0 + cx] = std::fabs(lum[r0 + cx] - lumBlur[r0 + cx]);
                }
            }
        });
        blur5(edge.data(), tmp.data(), cw, ch);

        // 先保边平滑一次（Google chroma_denoise 的第一遍），再做宽模糊。
        // σ=6 码：8bit 色度里 6 码以内视作"同色"，超过就是真的颜色边界。
        bilateral3(cb, tmp, cw, ch, 6.f);
        bilateral3(cr, tmp, cw, ch, 6.f);

        // 色度模糊半径要比"看起来够用"更大一些。
        //
        // 残余的色度噪声是**低频**的：5 帧平均只把 σ 降到 1/2.2，在 ISO 1600+ 的暗部
        // 仍有 1 DN 量级，而且都在 10~40 像素的尺度上——正好是肉眼最容易看成"色块"的尺度。
        // 两次 5 抽头（σ≈2.2 半分辨率像素 ≈ 4.4 全分辨率）压不住，改成四次（σ≈3.1，≈6.2）。
        // 人眼对色度的空间分辨率本来就低，多模糊一点换掉色块是划算的；
        // 真实颜色边界由下面的 edge 权重挡着，不会被抹平。
        for (int it = 0; it < 4; ++it) {
            blur5(cb.data(), tmp.data(), cw, ch);
            blur5(cr.data(), tmp.data(), cw, ch);
        }

        parallelRows(h, [&](int ys, int ye) {
            for (int y = ys; y < ye; ++y) {
                const float v = (y + 0.5f) / 2.f - 0.5f;
                const int row = y * w;
                for (int x = 0; x < w; ++x) {
                    const uint32_t p = px[row + x];
                    float r = qR(p), g = qG(p), b = qB(p);
                    float luma = 0.299f * r + 0.587f * g + 0.114f * b;
                    float u = (x + 0.5f) / 2.f - 0.5f;
                    float cb2 = bilinear(cb.data(), cw, ch, u, v);
                    float cr2 = bilinear(cr.data(), cw, ch, u, v);

                    float wHi = 1.f - 0.5f * smoothstep(0.78f, 0.97f, luma / 255.f);
                    // 边缘越强越不该动色度：那里是真实颜色边界，抹平就是串色
                    float edgeAmt = bilinear(edge.data(), cw, ch, u, v);
                    // 暗部要**更强**地平滑色度，但不能一刀切。
                    // 暗部的色度噪声本身就比亮部大得多（白平衡 R×1.9、B×1.9 把噪声一起放大），
                    // 平滑不够就留下一块块低频色斑，而且色斑边界跟着阴影形状走——
                    // 也就是"两个阴影色块之间一道断层"。这里按亮度给一点额外权重，
                    // 亮部不受影响（那里细节和颜色都要留）。
                    const float darkBoost = 1.f + 0.35f * (1.f - smoothstep(8.f, 70.f, luma));

                    // ---------------- 幅值守卫（照 Google HDR+ 的 desaturate_noise）----------------
                    //
                    // 参考实现（hdr-plus/src/finish.cpp）原话是：
                    //   "using the result only if it falls within constraints on by what factor
                    //    and absolute threshold the chroma magnitudes fall"
                    //   output = select(|blur|/|input| < 1.4 && |input| < T && |blur| < T,
                    //                   .7*blur + .3*input,  input);
                    //
                    // 为什么这条比"边缘权重"重要得多：我们原来是**无条件**用平滑后的色度替换，
                    // 而宽半径平滑会把邻近的颜色拖进本来几乎没有颜色的地方——阴影里那些
                    // "被涂上去"的整块颜色就是这么来的（平滑窗的边缘就是色块的边界）。
                    // 幅值守卫禁止这种**注入**：平滑结果比原值大 40% 以上就说明它是从别处
                    // 拖过来的颜色，一律不采用，保留原值。
                    const float cb0 = (b - luma) * 0.564f;
                    const float cr0 = (r - luma) * 0.713f;
                    const float m0 = std::sqrt(cb0 * cb0 + cr0 * cr0);
                    const float mB = std::sqrt(cb2 * cb2 + cr2 * cr2);
                    // 70 码≈中低饱和的分界，超过它就属于"真有颜色"，不该被当噪声平滑掉
                    const bool accept = (mB < 1.4f * m0 + 1.5f) && (m0 < 70.f) && (mB < 70.f);

                    // 混合系数：采用时按 0.7 平滑 + 0.3 原值（参考实现的系数），
                    // 再乘上强度、高光衰减、暗部加权和边缘衰减。
                    float k = accept
                              ? clampf(chromaStrength * 0.7f * wHi *
                                       (1.f - 0.5f * smoothstep(3.f, 14.f, edgeAmt)) * darkBoost,
                                       0.f, 1.f)
                              : 0.f;
                    if (k <= 0.01f) continue;

                    // **不再按亮度衰减色度。**
                    //
                    // 这条曲线（0.35+0.65·smoothstep(3,50,luma)）看起来只是"暗部柔和一点"，
                    // 实际效果是：亮度 3~50 的那一带色度被压掉 10~65%，而这一带**正好沿着
                    // 阴影的过渡带铺开**。周围是暖色（R>G>B），色度一压就"没那么暖"= 偏绿，
                    // 压得少的地方还是暖红 → 一条跟着阴影形状走的红/绿色带。
                    // 这就是阴影里红块绿块的来源，而且它在最后无条件执行，前面怎么调色度降噪都动不了它。
                    //
                    // Google 的 HDR+ 里没有这一步（finish.cpp 的 chroma_denoise 只有
                    // 双边 + 带幅值守卫的宽模糊）。暗部噪声它靠两件事：幅值守卫，
                    // 和 contrast() 里把最暗 3% 压成纯黑。
                    const float damp = 1.f;
                    float nr0 = luma + cr2 * damp / 0.713f;
                    float nb0 = luma + cb2 * damp / 0.564f;
                    float ng0 = (luma - 0.299f * nr0 - 0.114f * nb0) / 0.587f;

                    px[row + x] = pack255(r + (nr0 - r) * k,
                                          g + (ng0 - g) * k,
                                          b + (nb0 - b) * k);
                }
            }
        });
    }

    const int64_t tS2 = monoMs();
    // ---------------- 3. 色差校正 ----------------
    // 必须在锐化之前：锐化会把彩色镶边一起锐化，之后就分不清是细节还是色差
    caCorrect(px, w, h, caStrength);

    const int64_t tS3 = monoMs();
    // ---------------- 4. 亮度锐化（多尺度）----------------
    //
    // 换成拉普拉斯金字塔：三个频段（≈1.5 / 3 / 6 px 尺度）各自按幅度加权后加回，
    // 覆盖的尺度比原来的 3×3 USM 宽一个多数量级。细节见 sharpenMultiScale 的注释。
    // 锐化前留一份亮度快照，供 haloGuard 判断梯度反转。
    // 12MP 的 uint16 = 25MB，比拷贝 RGB（48MB）便宜一半；亮度就够用来做判据。
    std::vector<uint16_t> lumaBefore;
    if (sharpenAmount > 0.01f) {
        lumaBefore.resize((size_t) w * h);
        parallelRows(h, [&](int ys, int ye) {
            for (int y = ys; y < ye; ++y) {
                const int row = y * w;
                for (int x = 0; x < w; ++x) {
                    const uint32_t p = px[row + x];
                    lumaBefore[row + x] = (uint16_t) ((77 * pixR(p) + 150 * pixG(p) + 29 * pixB(p)) >> 8);
                }
            }
        });
    }

    sharpenMultiScale(px, w, h, sharpenAmount);

    // 锐化会在强边缘两侧造成梯度反转（白边/黑边）。按 Polyblur 的闭式解
    // 在反转处把像素按比例退回锐化前——只动过冲，不动真实细节。
    if (!lumaBefore.empty()) {
        const int64_t tHalo = monoMs();
        haloGuard(px, lumaBefore.data(), w, h);
        AFIN("halo 抑制 %.0fms", (double) (monoMs() - tHalo));
    }

    const int64_t tS4 = monoMs();
    // ---------------- 5. 色相微调 ----------------
    // 放在锐化之后：只改色度不动亮度，锐化出的细节不受影响
    hueTune(px, w, h, hueStrength);

    // ---- 精度自检 ----
    // 如果 10 位数据里的低 2 位全是 0，说明上游其实还是 8 位精度（只是被左移了两位），
    // 那这次改造就是白做。打印低 2 位的非零占比，一眼能看出来。
    {
        long n = 0, lowNonZero = 0;
        for (size_t i = 0; i < (size_t) w * h; i += 97) {
            const uint32_t p = px[i];
            const int y10 = (77 * pixR(p) + 150 * pixG(p) + 29 * pixB(p)) >> 8;
            if (y10 < 8 || y10 > 1000) continue;
            ++n;
            if (((pixR(p) | pixG(p) | pixB(p)) & 3) != 0) ++lowNonZero;
        }
        AFIN("精度自检：10 位数据低 2 位非零占比 %.1f%%（接近 100%% 才算真的 10 位）",
              n ? lowNonZero * 100.0 / (double) n : 0.0);
    }

    const int64_t tS5 = monoMs();
    // ---------------- 6. 量化到 8bit ----------------
    if (quantize) quantizeToArgb(px, w, h);
    AFIN("收尾分段：影调 %lld / 色度降噪 %lld / 色差 %lld / 锐化 %lld / 色相 %lld / 量化 %lld ms",
          (long long) (tS1 - tS0), (long long) (tS2 - tS1), (long long) (tS3 - tS2),
          (long long) (tS4 - tS3), (long long) (tS5 - tS4), (long long) (monoMs() - tS5));

}

}  // namespace camhdr
