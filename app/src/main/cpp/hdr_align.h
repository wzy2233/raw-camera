// HDR+ §4 分层对齐（Hasinoff et al., SIGGRAPH Asia 2016）。
//
// 与之前的"全局平移"相比，这里给每个 tile 一份独立位移，
// 于是手转、局部运动、近处物体视差都能被各自吸收——这是论文里最关键的一步。
#ifndef CAMHDR_ALIGN_H
#define CAMHDR_ALIGN_H

#include <cstdint>
#include <vector>

namespace camhdr {

/**
 * 一层金字塔上的位移场。
 *
 * tile 是**全分辨率**下的边长（64px），dx/dy 也是全分辨率像素且恒为偶数。
 * 恒为偶数是论文的点睛之笔：偶数位移后 Bayer 四相位仍然对齐，
 * 于是可以在未去马赛克的 RAW 上做对齐，颜色关系不会被拆散。
 */
struct AlignField {
    int cols = 0;
    int rows = 0;
    int tile = 64;                  // 全分辨率 px
    std::vector<int16_t> dx, dy;
};

/**
 * 对整段连拍做对齐。
 *
 * **参考帧不是 frames[0]，而是这簇里最锐的那一帧**（论文 §4 的 "lucky imaging"：
 * 参考帧从前 3 帧里选梯度最强的一帧）。
 *
 * 为什么这件事值 30 行代码：融合结果的模糊程度趋近参考帧——参考帧自己糊，
 * 后面再怎么平均都补不回来。而快门按下的那几十毫秒恰好是手抖最容易出现峰值的时刻，
 * 第 0 帧正好撞上的概率不低。选锐帧等于把"最坏情况"从"整段连拍里最糊的一帧"
 * 变成"最锐的一帧"。
 *
 * 评分用 3 Mpix 灰度图（对齐本来就要算的那张）上的 Sobel 能量，
 * 除以平均亮度平方做归一——这样"帧间亮度有微小抖动"不会被误当成锐度差异。
 *
 * @param outRefIdx 输出实际选中的参考帧下标（可为 nullptr）。它的位移场恒为单位场。
 */
void alignBurst(const uint16_t *const *frames, int n, int w, int h, int black,
                std::vector<AlignField> *out, int *outRefIdx);

/**
 * 把整像素位移场细化到**亚像素**（Lucas-Kanade，3 次迭代）。
 *
 * 为什么 SR 必须要这一步：整像素平移（而且是强制偶数）不会改变 Bayer 相位，
 * 5 帧提供的采样格点完全重合——多帧只能降噪，一点分辨率都不涨。
 * 只有亚像素位移才能让 R/G/B 三个通道各自获得"新"的采样位置，
 * 从而把欠采样补回来。这也解释了为什么"整像素平均 + 去马赛克"只有 34 dB，
 * 而亚像素核回归能到 38 dB（离线实测，20 组位移平均 +3.66 dB）。
 *
 * 精度目标 0.05~0.1 px。论文的做法是"块匹配之后跑 3 次 LK"，并明确说
 * 二次曲线拟合（HDR+ 用的那套）精度不够 SR 用——所以我们照它走。
 *
 * @param coarse  alignBurst 的输出（整像素）
 * @param outDx/outDy 输出：每帧的浮点位移场，**全分辨率像素**，尺寸 cols*rows
 */
void refineSubpixelAlign(const uint16_t *const *frames, int n, int refIdx,
                         int w, int h, int black,
                         const std::vector<AlignField> &coarse,
                         std::vector<std::vector<float>> *outDx,
                         std::vector<std::vector<float>> *outDy);

}  // namespace camhdr

#endif  // CAMHDR_ALIGN_H
