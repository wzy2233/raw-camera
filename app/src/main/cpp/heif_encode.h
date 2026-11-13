#ifndef CAMHDR_HEIF_ENCODE_H
#define CAMHDR_HEIF_ENCODE_H

#include <cstdint>

namespace camhdr {

/**
 * 能力探针：把 HEVC Main10 编码器、P010 输入格式、HEIF muxer、
 * 以及 configure+start 之后真实的 stride / slice-height 全部打到 logcat。
 *
 * 为什么必须先探：P010 的行对齐（stride）各家不一样（常见 32/64/128 对齐，
 * 还有 slice-height 补齐），猜错就是整幅斜切/花屏。这些值只有 configure 成功
 * 之后从输入的 MediaFormat 里读才准。
 *
 * @return 0 = 全部可用；负数 = 对应环节不可用（见返回值定义）
 */
int heifProbe(const char *muxerPath);

/**
 * 把 10bit RGB 编成 HEIF（HEVC Main10 / P010 输入 / 4:2:0）。
 * @param px       finish 输出的 10bit 打包像素（R<<20|G<<10|B）
 * @param quality  0..100，映射成码率
 * @return 0 成功
 */
int encodeHeif10(const uint32_t *px, int sw, int sh, int rot, int quality,
                 int bitDepth, const char *path, int *outBytes, int *outMs);

/** 自检：用合成图跑一遍完整编码链路，验证 P010 布局与 muxer。 */
int heifSelfTest(int w, int h, int quality, int bitDepth, const char *path);

}  // namespace camhdr

#endif  // CAMHDR_HEIF_ENCODE_H
