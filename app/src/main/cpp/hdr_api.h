// 三个阶段的对外接口（C++ 名字空间，不做 extern "C"：JNI 层直接调用）
#ifndef CAMHDR_API_H
#define CAMHDR_API_H

#include <cstdint>

namespace camhdr {

/**
 * 渲染：暗角 + 归一化 + RCD 补绿 + 白平衡/矩阵 + 高光处理 + 色调曲线 -> ARGB。
 *
 * [drcK] 是动态范围压缩比（HDR 采集时的欠曝档数，1.0 = 不压缩）。
 * 它同时决定两件事：高光余量（合并后的 1.0 对应场景里比"正常曝光白"亮 K 倍的位置）
 * 和 §6 exposure fusion 里两个合成曝光之间的倍数。
 *
 * [huePreserve] 是色相保持修正强度 0..1。显示曲线逐通道套上去会让色相随亮度漂移，
 * 1 = 用"按亮度查表再等比缩放"得到的色相方向修正过来（色度幅值不变）。
 *
 * [rgb3] 非空时走**超分分支**：三平面线性 RGB（R 平面在前，每平面 w*h，Q14 定点），
 * 跳过减黑电平/暗角/逐相位降噪/补绿/去马赛克，其余（白平衡、矩阵、高光、DRC、
 * 色调曲线、色相保持）与普通分支**完全共用同一段代码**——这样 SR 只替换"重建"这一层，
 * 色彩链路一点不动，A/B 才有意义。
 */
void render(const uint16_t *raw, int w, int h,
            const int *black4, float white,
            const float *shading, int mw, int mh, const int *channelOf,
            const int *cfaColors,
            const float *wbGains, const float *matrix,
            int useRcd, int hlRecon, float drcK, float huePreserve,
            int wbRefine,
            const uint16_t *rgb3,
            uint32_t *out);

/**
 * 超分合并（SuperRes 2019）：分层对齐 -> 亚像素细化 -> 逐通道核回归。
 * 输出三平面线性 RGB（Q14），交给 render 的 [rgb3] 参数。
 *
 * 返回 0 = 成功；非 0 = 失败（调用方应退回普通 HDR 合并路径）。
 */
int hdrplusSuperRes(const uint16_t *const *frames, int n, int w, int h,
                    int black, int white, const int *cfaColors, float sigmaR,
                    const float *shading, int mw, int mh, const int *channelOf,
                    int lanes, uint16_t *outR, uint16_t *outG, uint16_t *outB);

/**
 * 收尾：局部影调 + 色度降噪 + 亮度锐化 + 色差校正 + 色相微调 + 抖动。
 *
 * [caStrength]   色差校正强度（0 = 关）。
 * [hueStrength]  色相微调强度（蓝天更透、绿植更润；0 = 关）。
 */
/** 10bit 打包像素 → 8bit ARGB（TPDF 抖动）。高位深输出用它做预览。 */
void quantizeToArgb(uint32_t *px, int w, int h);

/**
 * 坏点修复（就地改 raw，单位是 DN，**未减黑电平**）。
 *
 * 只和同色邻居（x±2、y±2，同 CFA 相位）比，判据是"是极值 且 离中值 > 阈值"，
 * 阈值 = max(满量程×8%, 40 DN)。返回修掉的像素数。
 *
 * 从 Kotlin 搬过来的：12.6M 像素的纯规则运算，Kotlin 版 340ms，
 * native 版约 40ms，而它位于整条链路最前面，省下的时间是用户直接感知的。
 */
int fixDefects(uint16_t *raw, int w, int h, int black, float white);

/**
 * JPEG 4:4:4 编码（直接落盘）。[argb] 是 8bit ARGB，行优先，尺寸 sw×sh，
 * [rot] 是 90/180/270 时在采样阶段旋转，输出尺寸相应交换宽高。
 *
 * 与 Kotlin 版数学完全一致，但 DCT 能吃 NEON，实测 1083ms → 约 200ms。
 * 返回 0 成功；<0 失败。-1 参数非法，-2 打不开文件。
 */
int encodeJpeg444(const uint32_t *argb, int sw, int sh, int rot, int quality,
                  const char *path, int *outBytes);

void finish(uint32_t *px, int w, int h,
            float tmoStrength, float chromaStrength, float sharpenAmount,
            float caStrength, float hueStrength,
            int enableTmo, int enableNr, int quantize);

/** HDR+ 融合（Hasinoff 2016 §4 分层对齐 + §5 频域 Wiener）。要求所有帧曝光相同。 */
void hdrplusMerge(const uint16_t *const *frames, int n, int w, int h,
                  int black, int white, uint16_t *out);

}  // namespace camhdr

#endif  // CAMHDR_API_H
