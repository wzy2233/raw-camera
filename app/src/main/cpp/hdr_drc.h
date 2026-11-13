// 动态范围压缩（论文 §6 的 exposure fusion）。
//
// 为什么单独一个文件：这一步不是逐像素的查表，而是要在**低分辨率的亮度图上
// 做一次多尺度（拉普拉斯金字塔）融合**，再当成增益图用。写成独立单元，
// 才能和渲染主循环分开调、分开测。
#ifndef CAMHDR_DRC_H
#define CAMHDR_DRC_H

#include <cstdint>

namespace camhdr {

/**
 * 生成 DRC 增益图。
 *
 * 输入是归一化之前的 RAW（含黑电平），输出是 [gw,gh] 的增益图；
 * 主循环按双线性上采样到全分辨率后乘到线性 RGB 上。
 *
 * 为什么在低分辨率上做：
 *   局部影调的增益本来就是低频的（这也是 GCam 里叫 hdr_gain_map 的原因），
 *   在 1/8 分辨率上算，成本只有全分辨率的 1/64，效果几乎一样。
 *   全分辨率算一张 12MP 的浮点金字塔要 50MB×若干层，老机器直接 OOM。
 *
 * [drcK] 是采集时的欠曝倍数，等于"短曝光 / 长曝光"的比值；
 * 它就是论文里 AE 输出的那个 dynamic range compression。
 */
void buildDrcGain(const uint16_t *raw, int w, int h,
                  const int *black4, float whiteScale,
                  const float *wbGains, const float *matrix, const int *cfaColors,
                  /* 暗角增益表（可为 null）。必须和主循环归一化用同一张，
                     否则代理亮度带暗角、四角会被当成"本来就暗"而被提亮。 */
                  const float *shading, int mw, int mh, const int *channelOf,
                  float drcK, int gw, int gh, float *gain,
                  /* 可为 null：输出低分辨率线性亮度，供上层做去雾黑点统计 */
                  float *outLin);

}  // namespace camhdr

#endif  // CAMHDR_DRC_H
