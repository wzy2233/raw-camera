#include "heif_encode.h"

#include <android/log.h>
#include <fcntl.h>
#include <media/NdkImage.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaMuxer.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "deep_common.h"
#include "hdr_common.h"

#define AHEIF(...) __android_log_print(ANDROID_LOG_DEBUG, "HdrHeif", __VA_ARGS__)

namespace camhdr {

namespace {

constexpr int32_t kHevcProfileMain10 = 2;
constexpr int32_t kColorYuvP010 = 54;
constexpr int32_t kColorYuv420Flexible = 0x7F000789;
// COLOR_FormatYUV420SemiPlanar = 21 = NV12（8bit，Y 平面 + UV 交错）
constexpr int32_t kColorYuv420SemiPlanar = 21;

// P010 的采样在 16 位字的**高位**（低 6 位填 0），这是 P010 的硬约定。
// 写错就是整体发暗 64 倍，而且是"看起来像曝光不对"那种错，很难查。
constexpr int kP010Shift = 6;

int64_t nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

AMediaFormat *makeFormat(int32_t colorFormat, int32_t w, int32_t h, int32_t bitrate) {
    AMediaFormat *f = AMediaFormat_new();
    AMediaFormat_setString(f, AMEDIAFORMAT_KEY_MIME, "video/hevc");
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_WIDTH, w);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_HEIGHT, h);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_COLOR_FORMAT, colorFormat);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_BIT_RATE, bitrate);
    // frame-rate = 1：bitrate 就近似等于"这一帧"的大小，不会被 /30 摊掉。
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_FRAME_RATE, 1);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 0);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, (int32_t) ((int64_t) w * h * 4));
    // 字面量键：NDK 没为这几个导出常量，但 MediaFormat 是字符串键的表。
    AMediaFormat_setInt32(f, "profile", kHevcProfileMain10);
    AMediaFormat_setInt32(f, "color-standard", 1);   // BT709
    AMediaFormat_setInt32(f, "color-range", 2);      // LIMITED
    AMediaFormat_setInt32(f, "color-transfer", 3);   // SDR_VIDEO
    AMediaFormat_setInt32(f, "prepend-sps-pps-to-idr-frames", 1);
    return f;
}

/**
 * RGB(10bit) → Y'CbCr 4:2:0 P010，BT.709 + 有限范围（Y 16-235 / UV 16-240）。
 *
 * 输入已经是**显示编码后**的值（sRGB OETF 之后），所以直接做线性矩阵，
 * 不能再套一次 gamma —— 编码器要的就是 gamma 编码过的信号。
 */
void rgbToP010(const uint32_t *px, int sw, int sh, int rot, int w, int h,
               uint8_t *yPlane, int yStride, uint8_t *uvPlane, int uvStride, int sliceH) {
    const float kr = 0.2126f, kb = 0.0722f;
    const float kg = 1.f - kr - kb;
    const float inv255 = 1.f / 255.f;
    const uint16_t blackY = (uint16_t) (16 * 4) << kP010Shift;

    // ---- 亮度平面 ----
    for (int yy = 0; yy < sliceH; ++yy) {
        uint16_t *row = reinterpret_cast<uint16_t *>(yPlane + (size_t) yy * yStride);
        const int rowSamples = yStride / 2;
        if (yy >= h) {
            for (int x = 0; x < rowSamples; ++x) row[x] = blackY;
            continue;
        }
        for (int x = 0; x < rowSamples; ++x) {
            if (x >= w) { row[x] = blackY; continue; }
            int sx = 0, sy = 0;
            deepMap(x, yy, sw, sh, rot, &sx, &sy);
            const uint32_t p = px[(size_t) sy * sw + sx];
            const float r = pixR(p) * kPixTo255 * inv255;
            const float g = pixG(p) * kPixTo255 * inv255;
            const float b = pixB(p) * kPixTo255 * inv255;
            float luma = kr * r + kg * g + kb * b;
            if (luma < 0.f) luma = 0.f;
            if (luma > 1.f) luma = 1.f;
            // 有限范围 Y = 16 + 219·luma（8bit 尺度），再 ×4 到 10bit
            const int v10 = (int) (4.f * (16.f + 219.f * luma) + 0.5f);
            row[x] = (uint16_t) clampInt(v10, 0, 1023) << kP010Shift;
        }
    }

    // ---- 色度平面（2×2 盒式平均 + 交织 UV）----
    const int cw = (w + 1) / 2, chh = (h + 1) / 2;
    const uint16_t neutral = (uint16_t) (128 * 4) << kP010Shift;
    for (int cy = 0; cy < (sliceH + 1) / 2; ++cy) {
        uint16_t *row = reinterpret_cast<uint16_t *>(uvPlane + (size_t) cy * uvStride);
        const int pairs = uvStride / 4;
        if (cy >= chh) {
            for (int cx = 0; cx < pairs; ++cx) { row[2 * cx] = neutral; row[2 * cx + 1] = neutral; }
            continue;
        }
        for (int cx = 0; cx < pairs; ++cx) {
            if (cx >= cw) { row[2 * cx] = neutral; row[2 * cx + 1] = neutral; continue; }
            float sr = 0, sg = 0, sb = 0;
            int n = 0;
            for (int dy = 0; dy < 2; ++dy) {
                const int sy = 2 * cy + dy;
                if (sy >= h) break;
                for (int dx = 0; dx < 2; ++dx) {
                    const int dxp = 2 * cx + dx;
                    if (dxp >= w) break;
                    int sx = 0, syy = 0;
                    deepMap(dxp, sy, sw, sh, rot, &sx, &syy);
                    const uint32_t p = px[(size_t) syy * sw + sx];
                    sr += pixR(p);
                    sg += pixG(p);
                    sb += pixB(p);
                    ++n;
                }
            }
            if (n == 0) n = 1;
            const float r = sr / n * kPixTo255 * inv255;
            const float g = sg / n * kPixTo255 * inv255;
            const float b = sb / n * kPixTo255 * inv255;
            const float luma = kr * r + kg * g + kb * b;
            float cb = (b - luma) / (2.f * (1.f - kb));
            float cr = (r - luma) / (2.f * (1.f - kr));
            cb = clampf(cb, -0.5f, 0.5f);
            cr = clampf(cr, -0.5f, 0.5f);
            row[2 * cx] = (uint16_t) clampInt((int) (4.f * (128.f + 224.f * cb) + 0.5f), 0, 1023)
                          << kP010Shift;
            row[2 * cx + 1] = (uint16_t) clampInt((int) (4.f * (128.f + 224.f * cr) + 0.5f), 0, 1023)
                              << kP010Shift;
        }
    }

    // ---- 写入端自检 ----
    // 解码结果偏灰时，必须先把"我写进去的值"和"解码器读出来的值"分开。
    // Y 的合法范围（有限范围，10bit 尺度）= 4*16 .. 4*235，再 <<6；
    // UV 合法范围 = 4*16 .. 4*240。
    // 中心像素在自检图里是绿渐变条，所以 Cb < 128 < ... 两侧不等才正常。
    {
        const int cxc = w / 2, cyc = h / 2;
        const uint16_t *yc = reinterpret_cast<const uint16_t *>(yPlane + (size_t) cyc * yStride);
        const int cwHalf = (w + 1) / 2;
        const uint16_t *uvc = reinterpret_cast<const uint16_t *>(
                uvPlane + (size_t) (cyc / 2) * uvStride);
        const int uxc = cwHalf / 2;
        AHEIF("P010 写入抽样: Y(中心)=%u -> 8bit %.1f | Y(左上)=%u | "
              "U(中心)=%u(8bit %.1f) V(中心)=%u(8bit %.1f) 中性=%u",
              yc[cxc], (double) yc[cxc] / 64.0 / 4.0,
              reinterpret_cast<const uint16_t *>(yPlane)[0],
              uvc[2 * uxc], (double) uvc[2 * uxc] / 64.0 / 4.0,
              uvc[2 * uxc + 1], (double) uvc[2 * uxc + 1] / 64.0 / 4.0,
              neutral);
    }
}


/**
 * RGB(10bit) -> Y'CbCr 4:2:0 **8bit NV12**（semi-planar，Y 平面 + UV 交错）。
 *
 * 为什么要同时保留这条 8bit 路径：
 *   1) P010 的 16 位打包出问题时，它是唯一能把"容器/色彩转换/编码链路"和
 *      "10bit 打包"切开来的对照实验 —— 如果 8bit 出图正常，问题就必定在 P010；
 *   2) 万一某台设备的 HEVC 编码器不接受 P010，它是可用的降级方案。
 *
 * 8bit 的布局没有歧义：每样本 1 字节，Y 平面 w 行、每行 stride 字节；
 * UV 平面 h/2 行、每行 stride 字节、每 2 字节一对 (U, V)。
 */
void rgbToNV12(const uint32_t *px, int sw, int sh, int rot, int w, int h,
               uint8_t *yPlane, int yStride, uint8_t *uvPlane, int uvStride, int sliceH) {
    const float kr = 0.2126f, kb = 0.0722f;
    const float kg = 1.f - kr - kb;
    const float inv255 = 1.f / 255.f;

    for (int yy = 0; yy < sliceH; ++yy) {
        uint8_t *row = yPlane + (size_t) yy * yStride;
        if (yy >= h) {
            memset(row, 16, (size_t) yStride);
            continue;
        }
        for (int x = 0; x < yStride; ++x) {
            if (x >= w) { row[x] = 16; continue; }
            int sx = 0, sy = 0;
            deepMap(x, yy, sw, sh, rot, &sx, &sy);
            const uint32_t p = px[(size_t) sy * sw + sx];
            const float r = pixR(p) * kPixTo255 * inv255;
            const float g = pixG(p) * kPixTo255 * inv255;
            const float b = pixB(p) * kPixTo255 * inv255;
            float luma = kr * r + kg * g + kb * b;
            if (luma < 0.f) luma = 0.f;
            if (luma > 1.f) luma = 1.f;
            row[x] = (uint8_t) clampInt((int) (16.f + 219.f * luma + 0.5f), 0, 255);
        }
    }

    const int cw = (w + 1) / 2, chh = (h + 1) / 2;
    for (int cy = 0; cy < (sliceH + 1) / 2; ++cy) {
        uint8_t *row = uvPlane + (size_t) cy * uvStride;
        const int pairs = uvStride / 2;
        if (cy >= chh) {
            for (int cx = 0; cx < pairs; ++cx) { row[2 * cx] = 128; row[2 * cx + 1] = 128; }
            continue;
        }
        for (int cx = 0; cx < pairs; ++cx) {
            if (cx >= cw) { row[2 * cx] = 128; row[2 * cx + 1] = 128; continue; }
            float sr = 0, sg = 0, sb = 0;
            int n = 0;
            for (int dy = 0; dy < 2; ++dy) {
                const int sy = 2 * cy + dy;
                if (sy >= h) break;
                for (int dx = 0; dx < 2; ++dx) {
                    const int dxp = 2 * cx + dx;
                    if (dxp >= w) break;
                    int sx = 0, syy = 0;
                    deepMap(dxp, sy, sw, sh, rot, &sx, &syy);
                    const uint32_t p = px[(size_t) syy * sw + sx];
                    sr += pixR(p); sg += pixG(p); sb += pixB(p);
                    ++n;
                }
            }
            if (n == 0) n = 1;
            const float r = sr / n * kPixTo255 * inv255;
            const float g = sg / n * kPixTo255 * inv255;
            const float b = sb / n * kPixTo255 * inv255;
            const float luma = kr * r + kg * g + kb * b;
            float cb = (b - luma) / (2.f * (1.f - kb));
            float cr = (r - luma) / (2.f * (1.f - kr));
            cb = clampf(cb, -0.5f, 0.5f);
            cr = clampf(cr, -0.5f, 0.5f);
            row[2 * cx] = (uint8_t) clampInt((int) (128.f + 224.f * cb + 0.5f), 0, 255);
            row[2 * cx + 1] = (uint8_t) clampInt((int) (128.f + 224.f * cr + 0.5f), 0, 255);
        }
    }
}

// 试一种输入格式：configure / start / 真实行对齐。给探针用。
void tryConfig(const char *name, int32_t colorFormat, int32_t w, int32_t h, int *outScore) {
    AMediaCodec *c = AMediaCodec_createEncoderByType("video/hevc");
    if (!c) {
        AHEIF("%-14s 创建编码器失败", name);
        *outScore = -100;
        return;
    }
    AMediaFormat *f = makeFormat(colorFormat, w, h, w * h * 3);
    media_status_t st = AMediaCodec_configure(c, f, nullptr, nullptr,
                                              AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AHEIF("%-14s color-format=%d  configure=%d", name, colorFormat, (int) st);
    if (st != AMEDIA_OK) {
        AMediaFormat_delete(f);
        AMediaCodec_delete(c);
        *outScore = -1;
        return;
    }
    st = AMediaCodec_start(c);
    AHEIF("%-14s start=%d", name, (int) st);
    if (st != AMEDIA_OK) {
        AMediaFormat_delete(f);
        AMediaCodec_delete(c);
        *outScore = -2;
        return;
    }
    AMediaFormat *in = AMediaCodec_getInputFormat(c);
    if (in) {
        int32_t stride = -1, slice = -1, cf = -1;
        AMediaFormat_getInt32(in, AMEDIAFORMAT_KEY_STRIDE, &stride);
        AMediaFormat_getInt32(in, AMEDIAFORMAT_KEY_SLICE_HEIGHT, &slice);
        AMediaFormat_getInt32(in, AMEDIAFORMAT_KEY_COLOR_FORMAT, &cf);
        AHEIF("%-14s 输入格式: stride=%d slice-height=%d color=%d",
              name, stride, slice, cf);
        AMediaFormat_delete(in);
        *outScore = (stride > 0 && slice > 0) ? 10 : 5;
    } else {
        AHEIF("%-14s 拿不到输入格式", name);
        *outScore = 3;
    }
    AMediaCodec_stop(c);
    AMediaCodec_delete(c);
    AMediaFormat_delete(f);
}

// ---------------------------------------------------------------- HEIF 容器
//
// 为什么要自己写容器：这台 ROM 的 AMediaMuxer 在
// AMEDIAMUXER_OUTPUT_FORMAT_HEIF 下**静默写成 MP4**（实测 ftyp=mp42，有 moov/stsd，
// 没有 pitm/iloc/ispe），Photos 根本不当图片打开。
// 编码仍交给 MediaCodec（它确实能出 Main10），我们只负责最后那层盒子。

inline void putU32(std::vector<uint8_t> &v, uint32_t x) {
    v.push_back((uint8_t) (x >> 24)); v.push_back((uint8_t) (x >> 16));
    v.push_back((uint8_t) (x >> 8));  v.push_back((uint8_t) x);
}
inline void putU16(std::vector<uint8_t> &v, uint16_t x) {
    v.push_back((uint8_t) (x >> 8)); v.push_back((uint8_t) x);
}
inline void putTag(std::vector<uint8_t> &v, const char *t) {
    v.push_back((uint8_t) t[0]); v.push_back((uint8_t) t[1]);
    v.push_back((uint8_t) t[2]); v.push_back((uint8_t) t[3]);
}

inline std::vector<uint8_t> box(const char *type, const std::vector<uint8_t> &payload) {
    std::vector<uint8_t> b;
    b.reserve(payload.size() + 8);
    putU32(b, (uint32_t) (payload.size() + 8));
    putTag(b, type);
    b.insert(b.end(), payload.begin(), payload.end());
    return b;
}

inline std::vector<uint8_t> fullBoxV(const char *type, uint8_t version,
                                     const std::vector<uint8_t> &payload) {
    std::vector<uint8_t> p;
    putU32(p, (uint32_t) version << 24);      // version + flags(0)
    p.insert(p.end(), payload.begin(), payload.end());
    return box(type, p);
}

inline std::vector<uint8_t> fullBox(const char *type, const std::vector<uint8_t> &payload) {
    std::vector<uint8_t> p;
    putU32(p, 0);
    p.insert(p.end(), payload.begin(), payload.end());
    return box(type, p);
}

// 把 Annex-B 切成 NAL 列表
std::vector<std::vector<uint8_t>> splitAnnexB(const uint8_t *src, size_t n) {
    std::vector<std::vector<uint8_t>> out;
    std::vector<std::pair<size_t, size_t>> spans;
    size_t i = 0;
    while (i + 3 <= n) {
        size_t sc = 0;
        if (i + 4 <= n && src[i] == 0 && src[i + 1] == 0 && src[i + 2] == 0 && src[i + 3] == 1) sc = 4;
        else if (src[i] == 0 && src[i + 1] == 0 && src[i + 2] == 1) sc = 3;
        if (!sc) { ++i; continue; }
        const size_t st = i + sc;
        // 注意：这里必须走到 j == n 才停。写成 `while (j + 3 <= n)` 会在
        // 倒数第 3 个字节处提前退出，把每个 NAL 的**最后 2 个字节**丢掉
        // （实测 sample 比输入少 2 字节）。对 2.5MB 的 IDR 来说只是尾巴，
        // 但最后一个 slice 被截断会让解码图像大面积未定义。
        size_t j = st;
        while (j < n) {
            if (j + 3 <= n && src[j] == 0 && src[j + 1] == 0 &&
                (src[j + 2] == 1 || (j + 3 < n && src[j + 2] == 0 && src[j + 3] == 1))) break;
            ++j;
        }
        if (j > st) spans.emplace_back(st, j - st);
        i = j;
    }
    for (auto &sp : spans) out.emplace_back(src + sp.first, src + sp.first + sp.second);
    return out;
}

/**
 * 去掉防竞争字节（emulation prevention）。
 *
 * H.265 为了防止码流里出现 00 00 00/01/02/03，会在两个 0 之后插入一个 0x03。
 * 按原始字节读 SPS 的 profile_tier_level 会读错——实测 level_idc 读成 0，
 * 原因就是约束标志那 6 个字节里夹了 03，整体位移。
 */
std::vector<uint8_t> unescapeRbsp(const std::vector<uint8_t> &n) {
    std::vector<uint8_t> o;
    o.reserve(n.size());
    int zeros = 0;
    for (size_t i = 0; i < n.size(); ++i) {
        if (zeros >= 2 && n[i] == 0x03) { zeros = 0; continue; }
        o.push_back(n[i]);
        if (n[i] == 0) ++zeros; else zeros = 0;
    }
    return o;
}

inline int nalType(const std::vector<uint8_t> &n) {
    return n.size() >= 2 ? ((n[0] >> 1) & 0x3F) : -1;
}

/**
 * 组装 HEVCDecoderConfigurationRecord（hvcC 盒子的载荷）。
 *
 * 为什么必须自己拼：这台编码器吐出来的 `csd-0` 是**带起始码的 Annex-B 裸流**
 * （实测开头是 00 00 00 01 40 01 …，即 VPS/SPS/PPS），不是 hvcC 记录。
 * 直接把它当 hvcC 写进盒子，结构上看着对，但解码器拿不到 profile/level/参数集，打不开。
 *
 * profile/tier/level 从 SPS 里直接读：HEVC 的 SPS 在 2 字节 NAL 头之后是
 * 1 字节（vps_id + max_sub_layers + nesting），紧跟着就是 profile_tier_level，
 * 前 12 个字节正好是 profile_space|tier|profile_idc(1) + compat(4) + constraints(6) + level(1)。
 */
std::vector<uint8_t> buildHvcC(const std::vector<std::vector<uint8_t>> &nals, int bitDepth) {
    std::vector<uint8_t> vps, sps, pps;
    for (auto &n : nals) {
        switch (nalType(n)) {
            case 32: if (vps.empty()) vps = n; break;
            case 33: if (sps.empty()) sps = n; break;
            case 34: if (pps.empty()) pps = n; break;
            default: break;
        }
    }
    if (sps.size() < 16) {
        AHEIF("SPS 太短，无法组装 hvcC");
        return {};
    }
    // 必须先去掉防竞争字节，否则 profile_tier_level 的字节位置是错的
    const std::vector<uint8_t> spsU = unescapeRbsp(sps);
    if (spsU.size() < 16) {
        AHEIF("SPS 去转义后太短");
        return {};
    }
    const uint8_t *ptl = spsU.data() + 3;       // 跳过 NAL 头(2) + 1 字节
    std::vector<uint8_t> v;
    v.push_back(1);                             // configurationVersion
    v.push_back(ptl[0]);                        // profile_space|tier|profile_idc
    for (int i = 1; i <= 4; ++i) v.push_back(ptl[i]);    // compatibility flags
    for (int i = 5; i <= 10; ++i) v.push_back(ptl[i]);   // constraint flags
    v.push_back(ptl[11]);                       // general_level_idc
    v.push_back(0xF0); v.push_back(0x00);       // min_spatial_segmentation_idc = 0
    v.push_back(0xFC);                          // parallelismType = 0
    v.push_back(0xFD);                          // chromaFormat = 1 (4:2:0)
    // bit_depth_*_minus8 必须和**实际编出来的码流**一致。
    // 原来这里写死成 2（10bit），于是 8bit 码流被声明成 10bit，
    // 解码器按 10bit 解释 8bit 数据 —— 纯白会变成粉红 (214,98,161)。
    const int bdMinus8 = (bitDepth == 8) ? 0 : 2;
    v.push_back((uint8_t) (0xF8 | bdMinus8));    // reserved(5) + bitDepthLumaMinus8
    v.push_back((uint8_t) (0xF8 | bdMinus8));    // reserved(5) + bitDepthChromaMinus8
    v.push_back(0); v.push_back(0);             // avgFrameRate
    v.push_back(0x0F);                          // constantFrameRate=0,numTemporalLayers=0,temporalIdNested=0,lengthSizeMinusOne=3
    v.push_back(3);                             // numOfArrays
    auto array = [&](int type, const std::vector<uint8_t> &nal) {
        if (nal.empty()) return;
        v.push_back((uint8_t) (0x80 | (type & 0x3F)));   // array_completeness=1
        putU16(v, 1);
        putU16(v, (uint16_t) nal.size());
        v.insert(v.end(), nal.begin(), nal.end());
    };
    array(32, vps);
    array(33, sps);
    array(34, pps);
    return v;
}

/**
 * 单张图 HEIF：ftyp + meta(hdlr/pitm/iinf/iloc/iprp) + mdat
 *
 * iloc 里的 extent_offset 是相对文件开头的绝对偏移，而它自己又在 meta 里面——
 * 先按 offset=0 建一遍量出 meta 的大小，再用真值重建一遍。
 * （之前用"记住字段索引、事后回填"的写法，回填位置算错了两字节，
 *   把 extent_count 覆盖掉了，文件结构合法但指不到数据。）
 */
bool writeHeif(const char *path, const std::vector<uint8_t> &hvcC,
               const std::vector<uint8_t> &sample, int dw, int dh) {
    std::vector<uint8_t> ftypPayload;
    putTag(ftypPayload, "heic");
    putU32(ftypPayload, 0);
    putTag(ftypPayload, "mif1");
    putTag(ftypPayload, "heic");
    const std::vector<uint8_t> ftyp = box("ftyp", ftypPayload);

    // 与偏移无关的盒子先建好
    const std::vector<uint8_t> hvcCBox = box("hvcC", hvcC);
    std::vector<uint8_t> ispePayload;
    putU32(ispePayload, (uint32_t) dw);
    putU32(ispePayload, (uint32_t) dh);
    const std::vector<uint8_t> ispe = fullBox("ispe", ispePayload);
    std::vector<uint8_t> pixiPayload;
    pixiPayload.push_back(3);
    pixiPayload.push_back(10); pixiPayload.push_back(10); pixiPayload.push_back(10);
    const std::vector<uint8_t> pixi = fullBox("pixi", pixiPayload);
    std::vector<uint8_t> colrPayload;
    putTag(colrPayload, "nclx");
    putU16(colrPayload, 1);    // BT.709 primaries
    putU16(colrPayload, 13);   // sRGB transfer
    putU16(colrPayload, 1);    // BT.709 matrix
    colrPayload.push_back(0);  // 有限范围（和编码时 color-range=2 一致）
    const std::vector<uint8_t> colr = box("colr", colrPayload);

    std::vector<uint8_t> ipcoPayload;
    ipcoPayload.insert(ipcoPayload.end(), hvcCBox.begin(), hvcCBox.end());
    ipcoPayload.insert(ipcoPayload.end(), ispe.begin(), ispe.end());
    ipcoPayload.insert(ipcoPayload.end(), pixi.begin(), pixi.end());
    ipcoPayload.insert(ipcoPayload.end(), colr.begin(), colr.end());
    const std::vector<uint8_t> ipco = box("ipco", ipcoPayload);

    std::vector<uint8_t> ipmaPayload;
    putU32(ipmaPayload, 1);
    putU16(ipmaPayload, 1);
    ipmaPayload.push_back(4);
    ipmaPayload.push_back(0x80 | 1);   // hvcC essential
    ipmaPayload.push_back(0x02);
    ipmaPayload.push_back(0x03);
    ipmaPayload.push_back(0x04);
    const std::vector<uint8_t> ipma = fullBox("ipma", ipmaPayload);

    std::vector<uint8_t> iprpPayload;
    iprpPayload.insert(iprpPayload.end(), ipco.begin(), ipco.end());
    iprpPayload.insert(iprpPayload.end(), ipma.begin(), ipma.end());
    const std::vector<uint8_t> iprp = box("iprp", iprpPayload);

    std::vector<uint8_t> hdlrPayload;
    putU32(hdlrPayload, 0);
    putTag(hdlrPayload, "pict");
    for (int i = 0; i < 12; ++i) hdlrPayload.push_back(0);
    hdlrPayload.push_back(0);
    const std::vector<uint8_t> hdlr = fullBox("hdlr", hdlrPayload);

    std::vector<uint8_t> pitmPayload;
    putU16(pitmPayload, 1);
    const std::vector<uint8_t> pitm = fullBox("pitm", pitmPayload);

    std::vector<uint8_t> infePayload;
    putU16(infePayload, 1);
    putU16(infePayload, 0);
    putTag(infePayload, "hvc1");
    infePayload.push_back(0);
    std::vector<uint8_t> iinfPayload;
    putU16(iinfPayload, 1);
    // infe 必须是 **version 2**：只有 v2 才有 item_type 字段。
    // 用 v0 的话解码器看不到 "hvc1"，会认为这不是一张图 —— 实测就是"解码失败"。
    const std::vector<uint8_t> infe = fullBoxV("infe", 2, infePayload);
    iinfPayload.insert(iinfPayload.end(), infe.begin(), infe.end());
    const std::vector<uint8_t> iinf = fullBox("iinf", iinfPayload);

    // meta 只依赖 extent_offset 的值、不依赖它的宽度，所以两遍重建尺寸完全一致
    auto buildMeta = [&](uint32_t extentOffset) {
        std::vector<uint8_t> ilocPayload;
        ilocPayload.push_back(0x44);      // offset_size=4 length_size=4
        ilocPayload.push_back(0x00);      // base_offset_size=0
        putU16(ilocPayload, 1);           // item_count
        putU16(ilocPayload, 1);           // item_ID
        putU16(ilocPayload, 0);           // data_reference_index
        putU16(ilocPayload, 1);           // extent_count
        putU32(ilocPayload, extentOffset);
        putU32(ilocPayload, (uint32_t) sample.size());
        const std::vector<uint8_t> iloc = fullBox("iloc", ilocPayload);

        std::vector<uint8_t> mp;
        mp.insert(mp.end(), hdlr.begin(), hdlr.end());
        mp.insert(mp.end(), pitm.begin(), pitm.end());
        mp.insert(mp.end(), iinf.begin(), iinf.end());
        mp.insert(mp.end(), iloc.begin(), iloc.end());
        mp.insert(mp.end(), iprp.begin(), iprp.end());
        return fullBox("meta", mp);
    };

    const std::vector<uint8_t> metaProbe = buildMeta(0);
    const uint32_t payloadOff = (uint32_t) (ftyp.size() + metaProbe.size() + 8);
    const std::vector<uint8_t> meta = buildMeta(payloadOff);
    if (meta.size() != metaProbe.size()) {
        AHEIF("meta 尺寸不稳定 %zu vs %zu", meta.size(), metaProbe.size());
        return false;
    }

    const std::vector<uint8_t> mdat = box("mdat", sample);
    FILE *f = fopen(path, "wb");
    if (!f) return false;
    fwrite(ftyp.data(), 1, ftyp.size(), f);
    fwrite(meta.data(), 1, meta.size(), f);
    fwrite(mdat.data(), 1, mdat.size(), f);
    fclose(f);
    return true;
}

}  // namespace

int heifProbe(const char *muxerPath) {
    AHEIF("===== HEIF 能力探针 =====");
    // NDK 的 AMediaMuxer_new 收的是**文件描述符**，不是路径。
    int fd = open(muxerPath, O_CREAT | O_RDWR | O_TRUNC, 0644);
    AMediaMuxer *m = nullptr;
    if (fd >= 0) {
        m = AMediaMuxer_new(fd, AMEDIAMUXER_OUTPUT_FORMAT_HEIF);
        AHEIF("muxer(HEIF) 创建=%s", m ? "成功" : "失败");
        if (m) AMediaMuxer_delete(m);
        close(fd);
        std::remove(muxerPath);
    } else {
        AHEIF("muxer 打不开文件 fd=%d", fd);
    }

    const int32_t w = 4096, h = 3072;
    int s10 = 0, s8 = 0;
    tryConfig("P010(10bit)", kColorYuvP010, w, h, &s10);
    tryConfig("Flexible(8bit)", kColorYuv420Flexible, w, h, &s8);
    AHEIF("结论：P010=%d  8bit=%d  muxer=%d", s10, s8, m ? 1 : 0);
    return (s10 >= 5 && m) ? 0 : -1;
}

int encodeHeif10(const uint32_t *px, int sw, int sh, int rot, int quality,
                 int bitDepth, const char *path, int *outBytes, int *outMs) {
    const int64_t t0 = nowMs();
    int dw = 0, dh = 0;
    deepDestSize(sw, sh, rot, &dw, &dh);

    AMediaCodec *enc = AMediaCodec_createEncoderByType("video/hevc");
    if (!enc) {
        AHEIF("创建 HEVC 编码器失败");
        return -1;
    }
    const float q = clampf((quality - 60) / 40.f, 0.f, 1.f);
    // 单帧 CBR 的坑：编码器的比特预算按 "bitrate × 1/frame-rate" 给，但第一帧
    // 没有前向参考，它往往用一个非常保守（高）的 QP 就收工。实测 quality=95、
    // 标称 4bpp 时只输出 25KB（0.1bpp），画面被抹成一个模糊渐变。
    // 但实测提高 bitrate 完全无效（甚至文件更小：40KB -> 6KB），说明这个编码器
    // 在单帧 CBR 下不按 bitrate 定质量。改用 **CQ（恒定质量）模式 + quality 键**，
    // 直接指定 QP：BITRATE_MODE_CQ = 0。
    // 标称 bpp 要放大 8 倍才够。实测：标称 33bpp 时实际输出 2.14MB（12MP = 1.4bpp，
    // 视觉无损），标称 4bpp 只出 460KB（0.3bpp，明显掉质）。这台编码器的
    // "目标码率 → 实际码率"大约 1/20，所以这里乘 8 留余量。
    const int bpp = (int) ((1.5f + 3.0f * q) * 8.f);
    const int32_t bitrate = (int32_t) ((int64_t) dw * dh * bpp);

    // bitDepth = 10 → P010（16 位字的高 10 位）；8 → NV12（每样本 1 字节）。
    // 8bit 这条路径主要用来把"10bit 打包"和"容器/色彩转换"切开做对照，
    // 同时充当不支持 P010 的设备上的降级方案。
    const bool tenBit = (bitDepth == 10);
    AMediaFormat *fmt = makeFormat(tenBit ? kColorYuvP010 : kColorYuv420SemiPlanar,
                                   dw, dh, bitrate);
    if (!tenBit) AMediaFormat_setInt32(fmt, "profile", 1);      // HEVC Main（8bit）
    // 参数集只放在 hvcC 里（hvc1 的要求），不要内联到码流开头
    AMediaFormat_setInt32(fmt, "prepend-sps-pps-to-idr-frames", 0);
    // 码率模式必须显式给 CBR：默认 VBR 下编码器会用自己的 QP，
    // bitrate 基本被忽略——实测设了 50Mbps 却只出 0.56MB（0.37bpp），画质明显不够。
    // 码率模式：**不要用 CBR**。
    // 单帧 + CBR 时编码器会把整幅图压到一个 DC 系数（文件 1~13KB，画面变成
    // 一块纯色的平均），因为它的比特预算按"每秒"算，而只送来 1 帧时预算语义
    // 混乱。VBR 下同内容能出 0.5MB 量级、细节完整。
    // 1 = VBR。**不要用 CBR(2)**：单帧 + CBR 时编码器的比特预算语义混乱。
    // 也试过 AOSP 的 video-qp-i/min/max 钳位键，实测被编码器忽略（文件大小不变），
    // 已删掉，避免下次误以为它在生效。
    AMediaFormat_setInt32(fmt, "bitrate-mode", 1);
    media_status_t st = AMediaCodec_configure(enc, fmt, nullptr, nullptr,
                                              AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    if (st != AMEDIA_OK) {
        AHEIF("configure 失败 %d", (int) st);
        AMediaFormat_delete(fmt);
        AMediaCodec_delete(enc);
        return -2;
    }
    if (AMediaCodec_start(enc) != AMEDIA_OK) {
        AHEIF("start 失败");
        AMediaFormat_delete(fmt);
        AMediaCodec_delete(enc);
        return -3;
    }

    // 真实的平面指针与 stride 只能从 AImage 拿。
    // 之前用"MediaFormat 的 stride + 缓冲容量反推单位"是错的：
    // 编码器给的 max-input-size 很宽松（w*h*3），"字节"和"样本"两种解释都装得下，
    // 于是保留了错的那个 → 每行错开一点，累积成一条斜向拖影。
    int stride = tenBit ? dw * 2 : dw, sliceH = dh;
    AMediaFormat *inFmt = AMediaCodec_getInputFormat(enc);
    if (inFmt) {
        int32_t s2 = -1, sh2 = -1, cf2 = -999;
        AMediaFormat_getInt32(inFmt, AMEDIAFORMAT_KEY_STRIDE, &s2);
        AMediaFormat_getInt32(inFmt, AMEDIAFORMAT_KEY_SLICE_HEIGHT, &sh2);
        AMediaFormat_getInt32(inFmt, AMEDIAFORMAT_KEY_COLOR_FORMAT, &cf2);
        if (s2 > 0) stride = s2;
        if (sh2 > 0) sliceH = sh2;
        // 这一行是关键证据：如果 cf2 不等于请求的 54，说明编码器把输入格式改掉了，
        // 那它就会按别的布局解析我们写进去的 16 位样本（最典型的表现是"整体极暗"，
        // 因为它把每个 16 位样本当成两个 8 位像素，值大的高字节被丢掉）。
        const char *desc = AMediaFormat_toString(inFmt);
        AHEIF("输入格式回读: color-format=%d（请求 %d） stride=%d slice=%d\n  %s",
              cf2, kColorYuvP010, s2, sh2, desc ? desc : "(null)");
        AMediaFormat_delete(inFmt);
    }
    AHEIF("MediaFormat stride=%d slice=%d (dw=%d dh=%d)", stride, sliceH, dw, dh);

    auto cleanup = [&]() {
        AMediaCodec_stop(enc);
        AMediaCodec_delete(enc);
        AMediaFormat_delete(fmt);
    };

    ssize_t idx = AMediaCodec_dequeueInputBuffer(enc, 2000000);
    if (idx < 0) {
        AHEIF("拿不到输入缓冲");
        cleanup();
        return -4;
    }
    size_t cap = 0;
    uint8_t *buf = AMediaCodec_getInputBuffer(enc, (size_t) idx, &cap);
    size_t need = (size_t) stride * sliceH + (size_t) stride * ((sliceH + 1) / 2);
    if (cap < need) {                        // stride 报的是样本数
        stride *= 2;
        need = (size_t) stride * sliceH + (size_t) stride * ((sliceH + 1) / 2);
    }
    if (!buf || cap < need) {
        AHEIF("输入缓冲不够 cap=%zu need=%zu", cap, need);
        cleanup();
        return -5;
    }
    // 没有 AImage 可拿（NDK 不提供 AMediaCodec_getInputImage），只能靠 MediaFormat 的
    // stride。这里把三种可能的解释都打出来，用"解码结果"反推哪个是对的：
    //   bytes: 每行 stride 字节  /  samples: 每行 stride 个 16 位样本
    uint8_t *yPtr = buf;
    uint8_t *uvPtr = buf + (size_t) stride * sliceH;
    int yStride = stride, uvStride = stride;
    size_t queueSize = need;
    AHEIF("stride=%d slice=%d cap=%zu need=%zu  (dw=%d dh=%d)  "
          "[按字节: %d 样本/行; 按样本: %d 样本/行]",
          stride, sliceH, cap, need, dw, dh, stride / 2, stride);

    const int64_t tc = nowMs();
    if (bitDepth == 88) {
        // 诊断用：整块填中性灰（Y=128, U=V=128）。解码出来应该是**均匀灰**。
        // 若仍是渐变/条纹，说明编码器读的根本不是这块内存，或者它的平面布局
        // 和我们写的位置不一致（那就是 stride / plane offset 的问题）。
        memset(buf, 0x80, (size_t) stride * sliceH * 3 / 2);
        AHEIF("诊断：整块填 0x80（%zu 字节）", (size_t) stride * sliceH * 3 / 2);
    } else if (tenBit) {
        rgbToP010(px, sw, sh, rot, dw, dh, yPtr, yStride, uvPtr, uvStride, sliceH);
    } else {
        rgbToNV12(px, sw, sh, rot, dw, dh, yPtr, yStride, uvPtr, uvStride, sliceH);
    }
    const int64_t tcDone = nowMs();

    {
        // 返回值必须检查：12MP P010 是 37.7MB，queue 失败时编码器什么都不吐，
        // 外层只会看到"码流为空"，很容易误判成容器或布局问题。
        const media_status_t qst = AMediaCodec_queueInputBuffer(
                enc, (size_t) idx, 0, queueSize, 0, AMEDIACODEC_BUFFER_FLAG_KEY_FRAME);
        if (qst != AMEDIA_OK) {
            AHEIF("送帧失败 st=%d cap=%zu queueSize=%zu stride=%d slice=%d tenBit=%d",
                  (int) qst, cap, queueSize, stride, sliceH, tenBit ? 1 : 0);
            cleanup();
            return -6;
        }
    }
    // 必须送 EOS，否则编码器不会吐出完整 IDR
    {
        ssize_t ie = AMediaCodec_dequeueInputBuffer(enc, 1000000);
        if (ie >= 0) {
            AMediaCodec_queueInputBuffer(enc, (size_t) ie, 0, 0, 0,
                                         AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
        }
    }

    std::vector<uint8_t> hvcC;
    std::vector<uint8_t> sample;
    AMediaCodecBufferInfo info;
    for (int guard = 0; guard < 400; ++guard) {
        idx = AMediaCodec_dequeueOutputBuffer(enc, &info, 2000000);
        if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat *of = AMediaCodec_getOutputFormat(enc);
            void *data = nullptr;
            size_t sz = 0;
            if (AMediaFormat_getBuffer(of, "csd-0", &data, &sz) && data && sz) {
                // csd-0 是 Annex-B 裸流（VPS/SPS/PPS），要**组装成 hvcC 记录**，
                // 不能原样塞进 hvcC 盒子——那样解码器拿不到参数集，文件打不开。
                const auto nals = splitAnnexB((const uint8_t *) data, sz);
                hvcC = buildHvcC(nals, tenBit ? 10 : 8);
            }
            AMediaFormat_delete(of);
            AHEIF("拿到 hvcC %zu 字节", hvcC.size());
        } else if (idx >= 0) {
            size_t osz = 0;
            uint8_t *ob = AMediaCodec_getOutputBuffer(enc, (size_t) idx, &osz);
            if (ob && info.size > 0) {
                const uint8_t *p = ob + info.offset;
                // 输出可能是 Annex-B，也可能是 4 字节长度前缀，两条路都归一成
                // "4 字节长度前缀 + 丢掉参数集"（hvc1 要求参数集只在 hvcC 里）。
                const bool annexB = (p[0] == 0 && p[1] == 0 &&
                                     (p[2] == 1 || (p[2] == 0 && p[3] == 1)));
                if (annexB) {
                    for (auto &nal : splitAnnexB(p, (size_t) info.size)) {
                        const int t = nalType(nal);
                        if (t == 32 || t == 33 || t == 34) continue;
                        putU32(sample, (uint32_t) nal.size());
                        sample.insert(sample.end(), nal.begin(), nal.end());
                    }
                } else {
                    size_t o = 0;
                    while (o + 4 <= (size_t) info.size) {
                        const uint32_t len = ((uint32_t) p[o] << 24) | ((uint32_t) p[o + 1] << 16) |
                                             ((uint32_t) p[o + 2] << 8) | p[o + 3];
                        if (len == 0 || o + 4 + len > (size_t) info.size) break;
                        const int type = (p[o + 4] >> 1) & 0x3F;
                        if (!(type == 32 || type == 33 || type == 34)) {
                            putU32(sample, len);
                            sample.insert(sample.end(), p + o + 4, p + o + 4 + len);
                        }
                        o += 4 + len;
                    }
                }
            }
            AMediaCodec_releaseOutputBuffer(enc, (size_t) idx, false);
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) break;
        }
    }
    cleanup();

    if (hvcC.empty() || sample.empty()) {
        AHEIF("码流为空 hvcC=%zu sample=%zu", hvcC.size(), sample.size());
        return -7;
    }
    if (!writeHeif(path, hvcC, sample, dw, dh)) {
        AHEIF("写 HEIF 容器失败");
        return -8;
    }

    FILE *f = fopen(path, "rb");
    long sz = 0;
    if (f) { fseek(f, 0, SEEK_END); sz = ftell(f); fclose(f); }
    if (outBytes) *outBytes = (int) sz;
    if (outMs) *outMs = (int) (nowMs() - t0);
    AHEIF("HEIF(自写容器) 完成 %.2f MB  总 %lldms（转换 %lldms） %dx%d bpp=%d",
          sz / 1048576.0, (long long) (nowMs() - t0), (long long) (tcDone - tc), dw, dh, bpp);
    return sz > 0 ? 0 : -9;
}

/**
 * 自检：合成一张 10bit 图跑通整条链路（含容器），验证 P010 布局、旋转、盒子结构。
 */
int heifSelfTest(int w, int h, int quality, int bitDepth, const char *path) {
    std::vector<uint32_t> buf((size_t) w * h);
    // 四个**纯色**竖条：红 / 绿 / 蓝 / 白。
    // 之前用渐变带，画质一差就分不清"是量化丢了细节"还是"是布局错了"；
    // 纯色块没有高频，任何色相错误都能从解码值上直接读出来。
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            // 上黑下白：无彩色干扰，Y 正确与否一眼可判
            const int v = (y < h / 2) ? 0 : 1023;
            int r = v, g = v, b = v;
            buf[(size_t) y * w + x] = pixPack(r, g, b);
        }
    }
    int bytes = 0, ms = 0;
    const int rc = encodeHeif10(buf.data(), w, h, 0, quality, bitDepth, path, &bytes, &ms);
    AHEIF("HEIF 自检 rc=%d  %dbit  %d 字节  %dms", rc, bitDepth, bytes, ms);
    return rc;
}

}  // namespace camhdr
