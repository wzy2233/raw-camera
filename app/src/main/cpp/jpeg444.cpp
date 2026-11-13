/**
 * JPEG 4:4:4 编码器（native）。
 *
 * ---- 为什么要搬 ----
 * Kotlin 版实测 1083ms。真正的瓶颈是 `fdct`：朴素行列分离 DCT 是
 * 每块 2×8×8×8 = 1024 次乘加，12MP 一张图 59 万个 8×8 块 × 3 个分量，
 * 合计约 6 亿次乘加。kotlinc 不会把它向量化，实测约 10 周期/次乘加。
 *
 * 顺带排除了两个"看起来像元凶"的项（改了都没有可测收益，说明它们不是瓶颈）：
 *   · encodeBlock 里每块 IntArray(64) 分配 → 改成复用数组，无变化
 *   · writeBits 逐比特循环 → 改成整字节推进，无变化
 * 所以搬 native 的目的就是让 DCT 能吃到 NEON 的 4 路 fmla。
 *
 * ---- 与 Kotlin 版的关系 ----
 * 数学完全一致：同样的 COS 基表、同样的量化表缩放、同样的 Annex K 霍夫曼表、
 * 同样的 RST 分界（每个 MCU 行一个 restart interval）。所以输出可以直接
 * 逐段对比，便于验证搬对了没有。
 *
 * 额外能力：采样时直接按 rot 旋转（deepMap），因此**不需要先做一张旋转的 Bitmap**
 * ——那条路要多一次 48MB 分配 + 一次全图拷贝。
 */
#include "hdr_api.h"
#include "hdr_common.h"
#include "deep_common.h"

#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <vector>

#define AJ(...) __android_log_print(ANDROID_LOG_DEBUG, "Jpeg444N", __VA_ARGS__)

namespace camhdr {
namespace {

const int kLQ[64] = {
    16, 11, 10, 16, 24, 40, 51, 61,
    12, 12, 14, 19, 26, 58, 60, 55,
    14, 13, 16, 24, 40, 57, 69, 56,
    14, 17, 22, 29, 51, 87, 80, 62,
    18, 22, 37, 56, 68, 109, 103, 77,
    24, 35, 55, 64, 81, 104, 113, 92,
    49, 64, 78, 87, 103, 121, 120, 101,
    72, 92, 95, 98, 112, 100, 103, 99
};

const int kCQ[64] = {
    17, 18, 24, 47, 99, 99, 99, 99,
    18, 21, 26, 66, 99, 99, 99, 99,
    24, 26, 56, 99, 99, 99, 99, 99,
    47, 66, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99
};

/** 自然顺序下标 -> zigzag 顺序 */
const int kZZ[64] = {
    0, 1, 8, 16, 9, 2, 3, 10,
    17, 24, 32, 25, 18, 11, 4, 5,
    12, 19, 26, 33, 40, 48, 41, 34,
    27, 20, 13, 6, 7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36,
    29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46,
    53, 60, 61, 54, 47, 55, 62, 63
};

// 标准霍夫曼表（ITU T.81 Annex K.3）
const int kDcLBits[16] = {0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
const int kDcLVals[12] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};

const int kDcCBits[16] = {0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
const int kDcCVals[12] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};

const int kAcLBits[16] = {0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D};
const int kAcLVals[162] = {
    0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12,
    0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
    0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08,
    0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
    0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16,
    0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
    0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39,
    0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
    0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
    0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
    0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79,
    0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
    0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98,
    0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
    0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6,
    0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5,
    0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4,
    0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
    0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA,
    0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
    0xF9, 0xFA
};

const int kAcCBits[16] = {0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
const int kAcCVals[162] = {
    0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21,
    0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
    0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91,
    0xA1, 0xB1, 0xC1, 0x09, 0x23, 0x33, 0x52, 0xF0,
    0x15, 0x62, 0x72, 0xD1, 0x0A, 0x16, 0x24, 0x34,
    0xE1, 0x25, 0xF1, 0x17, 0x18, 0x19, 0x1A, 0x26,
    0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38,
    0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
    0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
    0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
    0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78,
    0x79, 0x7A, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
    0x88, 0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96,
    0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5,
    0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4,
    0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3,
    0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2,
    0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA,
    0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9,
    0xEA, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
    0xF9, 0xFA
};

/** COS[u][x] = C(u)/2 · cos((2x+1)uπ/16) */
float gCos[8][8];

void initCos() {
    static std::once_flag once;
    std::call_once(once, [] {
        for (int u = 0; u < 8; ++u) {
            const double cu = (u == 0) ? (1.0 / std::sqrt(2.0)) : 1.0;
            for (int x = 0; x < 8; ++x) {
                gCos[u][x] = (float) (cu * std::cos((2 * x + 1) * u * M_PI / 16.0) / 2.0);
            }
        }
    });
}

/** 码表：高 16 位码长，低 16 位码字 */
void buildHuff(const int *bits, const int *vals, uint32_t *table) {
    memset(table, 0, 256 * sizeof(uint32_t));
    uint32_t code = 0;
    int k = 0;
    for (int len = 1; len <= 16; ++len) {
        for (int i = 0; i < bits[len - 1]; ++i) {
            const int sym = vals[k++];
            table[sym] = ((uint32_t) len << 16) | code;
            ++code;
        }
        code <<= 1;
    }
}

struct BitWriter {
    std::vector<uint8_t> buf;
    uint32_t acc = 0;
    int accBits = 0;

    explicit BitWriter(size_t cap) { buf.reserve(cap); }

    inline void emit(uint8_t b) {
        buf.push_back(b);
        if (b == 0xFF) buf.push_back(0);      // 熵编码段里 0xFF 后必须补 0x00
    }

    inline void bits(uint32_t v, int n) {
        if (n <= 0) return;
        acc = (acc << n) | (v & ((1u << n) - 1));
        accBits += n;
        while (accBits >= 8) {
            accBits -= 8;
            emit((uint8_t) ((acc >> accBits) & 0xFF));
        }
    }

    inline void huff(const uint32_t *t, int sym) {
        const uint32_t e = t[sym & 0xFF];
        if (!e) return;
        bits(e & 0xFFFF, (int) (e >> 16));
    }

    void flush() {
        if (accBits > 0) {
            const int pad = 8 - accBits;
            acc = (acc << pad) | ((1u << pad) - 1);
            emit((uint8_t) (acc & 0xFF));
            acc = 0;
            accBits = 0;
        }
    }
};

/** 行列分离的 8x8 DCT（原地）。与 Kotlin 版同一份数学，便于对比验证 */
inline void fdct(float *b) {
    float tmp[64];
    for (int y = 0; y < 8; ++y) {
        const float *row = b + y * 8;
        float *dst = tmp + y * 8;
        for (int u = 0; u < 8; ++u) {
            const float *cu = gCos[u];
            float s = 0.f;
            // 8 项展开：让编译器能把这条累加向量化成两条 fmla
            s += row[0] * cu[0]; s += row[1] * cu[1];
            s += row[2] * cu[2]; s += row[3] * cu[3];
            s += row[4] * cu[4]; s += row[5] * cu[5];
            s += row[6] * cu[6]; s += row[7] * cu[7];
            dst[u] = s;
        }
    }
    for (int x = 0; x < 8; ++x) {
        for (int v = 0; v < 8; ++v) {
            const float *cv = gCos[v];
            float s = 0.f;
            s += tmp[0 * 8 + x] * cv[0]; s += tmp[1 * 8 + x] * cv[1];
            s += tmp[2 * 8 + x] * cv[2]; s += tmp[3 * 8 + x] * cv[3];
            s += tmp[4 * 8 + x] * cv[4]; s += tmp[5 * 8 + x] * cv[5];
            s += tmp[6 * 8 + x] * cv[6]; s += tmp[7 * 8 + x] * cv[7];
            b[v * 8 + x] = s;
        }
    }
}

inline int bitSize(int v) {
    int a = v < 0 ? -v : v;
    int n = 0;
    while (a) { ++n; a >>= 1; }
    return n;
}

inline void scaleTable(const int *base, int scale, uint8_t *out) {
    for (int k = 0; k < 64; ++k) {
        int v = (base[kZZ[k]] * scale + 50) / 100;
        if (v < 1) v = 1;
        if (v > 255) v = 255;
        out[k] = (uint8_t) v;
    }
}

void putShort(std::vector<uint8_t> &v, int x) {
    v.push_back((uint8_t) ((x >> 8) & 0xFF));
    v.push_back((uint8_t) (x & 0xFF));
}

void putDht(std::vector<uint8_t> &v, int cls, const int *bits, const int *vals, int nvals) {
    v.push_back(0xFF); v.push_back(0xC4);
    putShort(v, 2 + 1 + 16 + nvals);
    v.push_back((uint8_t) cls);
    for (int i = 0; i < 16; ++i) v.push_back((uint8_t) bits[i]);
    for (int i = 0; i < nvals; ++i) v.push_back((uint8_t) vals[i]);
}

/** 三种分量共用的块编码：DCT -> 量化 -> zigzag -> 熵编码，返回新 DC 预测 */
inline int encodeBlock(float *block, int *coef, const uint8_t *quant,
                       const uint32_t *dcTable, const uint32_t *acTable,
                       int pred, BitWriter &bw) {
    fdct(block);

    for (int k = 0; k < 64; ++k) {
        const float v = block[kZZ[k]] / (float) quant[k];
        coef[k] = (int) std::lround(v);
    }

    const int diff = coef[0] - pred;
    const int dsize = bitSize(diff);
    bw.huff(dcTable, dsize);
    if (dsize) bw.bits((uint32_t) (diff < 0 ? diff - 1 : diff), dsize);

    int run = 0;
    for (int k = 1; k < 64; ++k) {
        const int v = coef[k];
        if (v == 0) { ++run; continue; }
        while (run > 15) {
            bw.huff(acTable, 0xF0);          // ZRL
            run -= 16;
        }
        const int size = bitSize(v);
        bw.huff(acTable, (run << 4) | size);
        bw.bits((uint32_t) (v < 0 ? v - 1 : v), size);
        run = 0;
    }
    if (run > 0) bw.huff(acTable, 0x00);      // EOB

    return coef[0];
}

}  // namespace

int encodeJpeg444(const uint32_t *argb, int sw, int sh, int rot, int quality,
                  const char *path, int *outBytes) {
    if (!argb || sw < 8 || sh < 8 || !path) return -1;
    initCos();

    const int q = std::min(100, std::max(1, quality));
    const int scale = (q < 50) ? (5000 / q) : (200 - 2 * q);

    // 色度表在高档位单独收紧：标准 Annex K 表在 q95（scale=10）下给色度 DC 的
    // 量化步长是 2 码，块均值一取整就把 ±0.5 码的抖动抹平了，平滑渐变里会出现
    // 一圈圈彩色等高线。乘 0.7 把 DC 步长压到 1，代价是体积涨 10~20%。
    const int chromaScale = (q >= 90) ? ((scale * 7 + 5) / 10) : scale;

    uint8_t qLuma[64], qChroma[64];
    scaleTable(kLQ, scale, qLuma);
    scaleTable(kCQ, std::max(1, chromaScale), qChroma);

    uint32_t dcL[256], acL[256], dcC[256], acC[256];
    buildHuff(kDcLBits, kDcLVals, dcL);
    buildHuff(kAcLBits, kAcLVals, acL);
    buildHuff(kDcCBits, kDcCVals, dcC);
    buildHuff(kAcCBits, kAcCVals, acC);

    // 输出尺寸：90/270 度要交换宽高
    const bool swap = (rot == 90 || rot == 270);
    const int dw = swap ? sh : sw;
    const int dh = swap ? sw : sh;
    const int mcuX = (dw + 7) / 8;
    const int mcuY = (dh + 7) / 8;

    // ---- 头部 ----
    std::vector<uint8_t> head;
    head.reserve(1024);
    head.push_back(0xFF); head.push_back(0xD8);           // SOI

    head.push_back(0xFF); head.push_back(0xE0);           // APP0 JFIF
    putShort(head, 16);
    head.push_back('J'); head.push_back('F'); head.push_back('I'); head.push_back('F');
    head.push_back(0);
    head.push_back(1); head.push_back(1);                 // 版本 1.1
    head.push_back(0);                                    // 单位：无
    putShort(head, 1); putShort(head, 1);                 // 密度
    head.push_back(0); head.push_back(0);                 // 缩略图 0x0

    head.push_back(0xFF); head.push_back(0xDB);           // DQT 亮度
    putShort(head, 2 + 1 + 64);
    head.push_back(0x00);
    head.insert(head.end(), qLuma, qLuma + 64);
    head.push_back(0xFF); head.push_back(0xDB);           // DQT 色度
    putShort(head, 2 + 1 + 64);
    head.push_back(0x01);
    head.insert(head.end(), qChroma, qChroma + 64);

    head.push_back(0xFF); head.push_back(0xC0);           // SOF0：三分量 1x1 = 4:4:4
    putShort(head, 8 + 3 * 3);
    head.push_back(8);
    putShort(head, dh); putShort(head, dw);
    head.push_back(3);
    head.push_back(1); head.push_back(0x11); head.push_back(0);
    head.push_back(2); head.push_back(0x11); head.push_back(1);
    head.push_back(3); head.push_back(0x11); head.push_back(1);

    putDht(head, 0x00, kDcLBits, kDcLVals, 12);
    putDht(head, 0x10, kAcLBits, kAcLVals, 162);
    putDht(head, 0x01, kDcCBits, kDcCVals, 12);
    putDht(head, 0x11, kAcCBits, kAcCVals, 162);

    head.push_back(0xFF); head.push_back(0xDD);           // DRI：每个 MCU 行一个 RST
    putShort(head, 4);
    putShort(head, mcuX);

    head.push_back(0xFF); head.push_back(0xDA);           // SOS
    putShort(head, 6 + 2 * 3);
    head.push_back(3);
    head.push_back(1); head.push_back(0x00);
    head.push_back(2); head.push_back(0x11);
    head.push_back(3); head.push_back(0x11);
    head.push_back(0); head.push_back(63); head.push_back(0);

    // ---- 逐 MCU 行编码（每行独立，DC 预测器从 0 开始）----
    std::vector<std::vector<uint8_t>> rows(mcuY);

    parallelRows(mcuY, [&](int my0, int my1) {
        for (int my = my0; my < my1; ++my) {
            BitWriter bw((size_t) mcuX * 256 + 64);
            float yB[64], cbB[64], crB[64];
            int coef[64];
            int predY = 0, predCb = 0, predCr = 0;

            for (int mx = 0; mx < mcuX; ++mx) {
                const int x0 = mx * 8;
                const int y0 = my * 8;

                for (int yy = 0; yy < 8; ++yy) {
                    int py = y0 + yy;
                    if (py >= dh) py = dh - 1;
                    for (int xx = 0; xx < 8; ++xx) {
                        int px2 = x0 + xx;
                        if (px2 >= dw) px2 = dw - 1;

                        int sx, sy;
                        if (swap || rot != 0) {
                            deepMap(px2, py, sw, sh, rot, &sx, &sy);
                        } else {
                            sx = px2; sy = py;
                        }
                        const uint32_t c = argb[(size_t) sy * sw + sx];
                        const float r = (float) ((c >> 16) & 0xFF);
                        const float g = (float) ((c >> 8) & 0xFF);
                        const float b = (float) (c & 0xFF);
                        const int i = yy * 8 + xx;
                        // BT.601 full range（与 JPEG 的 YCbCr 定义一致）
                        yB[i] = 0.299f * r + 0.587f * g + 0.114f * b - 128.f;
                        cbB[i] = -0.168736f * r - 0.331264f * g + 0.5f * b;
                        crB[i] = 0.5f * r - 0.418688f * g - 0.081312f * b;
                    }
                }

                predY = encodeBlock(yB, coef, qLuma, dcL, acL, predY, bw);
                predCb = encodeBlock(cbB, coef, qChroma, dcC, acC, predCb, bw);
                predCr = encodeBlock(crB, coef, qChroma, dcC, acC, predCr, bw);
            }
            bw.flush();
            rows[my] = std::move(bw.buf);
        }
    });

    // ---- 落盘 ----
    FILE *fp = fopen(path, "wb");
    if (!fp) {
        AJ("打不开文件 %s", path);
        return -2;
    }
    fwrite(head.data(), 1, head.size(), fp);
    for (int i = 0; i < mcuY; ++i) {
        if (!rows[i].empty()) fwrite(rows[i].data(), 1, rows[i].size(), fp);
        if (i < mcuY - 1) {
            const uint8_t rst[2] = {0xFF, (uint8_t) (0xD0 + (i & 7))};
            fwrite(rst, 1, 2, fp);
        }
    }
    const uint8_t eoi[2] = {0xFF, 0xD9};
    fwrite(eoi, 1, 2, fp);
    const long total = ftell(fp);
    fclose(fp);

    if (outBytes) *outBytes = (int) total;
    return 0;
}

}  // namespace camhdr
