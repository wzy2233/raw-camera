#ifndef CAMHDR_SUPERRES_H
#define CAMHDR_SUPERRES_H

#include <cstdint>
#include <vector>

#include "hdr_align.h"

namespace camhdr {

/**
 * 多帧亚像素超分合并：Bayer 连拍 -> 全分辨率 RGB 三平面（**不做去马赛克**）。
 *
 * @param black        黑电平（4 个 CFA 相位共用，与合并阶段一致）
 * @param whiteScale   1/(白电平-黑电平)
 * @param refIdx       参考帧下标（与 alignBurst 选出的一致）
 * @param coarse       alignBurst 的整像素位移场（提供 cols/rows/tile 元信息）
 * @param dxf/dyf      refineSubpixelAlign 的浮点位移场（全分辨率像素）
 * @param cfaColors    4 个相位的颜色号（0=R 1=G 2=B），顺序 (y&1)*2+(x&1)
 * @param sigmaR       核宽（全分辨率像素）。离线实测 0.5 最锐、0.9 最稳；
 *                     实际位移有估计误差，默认取 0.6。
 * @param shading      暗角增益表（可空）。普通路径在 normalizePass 里乘过，
 *                     SR 路径必须在这里乘，否则四角会暗——两边必须用同一张表。
 * @param outR/G/B     输出三平面，Q14 定点（0..4 线性映射到 0..65535），
 *                     编码与 hdr_render.cpp 的 decodeQ 一致
 */
void superResMerge(const uint16_t *const *frames, int n, int w, int h,
                   int black, float whiteScale, int refIdx,
                   const std::vector<AlignField> &coarse,
                   const std::vector<std::vector<float>> &dxf,
                   const std::vector<std::vector<float>> &dyf,
                   const int *cfaColors, float sigmaR,
                   const float *shading, int mw, int mh, const int *channelOf,
                   int lanes,
                   uint16_t *outR, uint16_t *outG, uint16_t *outB);

}  // namespace camhdr

#endif  // CAMHDR_SUPERRES_H
