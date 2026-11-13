#include "png16_encode.h"

#include <android/log.h>
#include <zlib.h>

#include <chrono>
#include <cstdio>
#include <cstring>
#include <vector>

#include "deep_common.h"
#include "hdr_common.h"

#define APNG(...) __android_log_print(ANDROID_LOG_DEBUG, "HdrPng", __VA_ARGS__)

namespace camhdr {

namespace {

inline int64_t nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

inline void put32be(std::vector<uint8_t> &v, uint32_t x) {
    v.push_back((uint8_t) (x >> 24));
    v.push_back((uint8_t) (x >> 16));
    v.push_back((uint8_t) (x >> 8));
    v.push_back((uint8_t) x);
}

void writeChunk(FILE *f, const char *type, const uint8_t *data, size_t len) {
    const uint8_t hdr[4] = {(uint8_t) (len >> 24), (uint8_t) (len >> 16),
                            (uint8_t) (len >> 8), (uint8_t) len};
    fwrite(hdr, 1, 4, f);
    fwrite(type, 1, 4, f);
    if (len) fwrite(data, 1, len, f);
    uLong c = crc32(0L, Z_NULL, 0);
    c = crc32(c, (const Bytef *) type, 4);
    if (len) c = crc32(c, data, (uInt) len);
    const uint8_t cb[4] = {(uint8_t) (c >> 24), (uint8_t) (c >> 16),
                           (uint8_t) (c >> 8), (uint8_t) c};
    fwrite(cb, 1, 4, f);
}

inline int paeth(int a, int b, int c) {
    const int p = a + b - c;
    const int pa = p > a ? p - a : a - p;
    const int pb = p > b ? p - b : b - p;
    const int pc = p > c ? p - c : c - p;
    if (pa <= pb && pa <= pc) return a;
    if (pb <= pc) return b;
    return c;
}

}  // namespace

/**
 * 10bit RGB → **16bit 真彩 PNG**，无损 + 全色度（4:4:4）。
 *
 * 为什么是 PNG 而不是 HEIF：PNG 是唯一"无损 + 4:4:4 + 支持 16bit"的通用容器。
 * HEIF 走硬件 HEVC 只能 4:2:0 而且有损。
 *
 * 数据摆放：10bit 值左移 6 位放进 16bit（低 6 位恒为 0）——PNG 只有 8/16 两档位深。
 * 这样文件里的台阶是 1024 级（0.098%），而且低位全 0 的冗余会被 DEFLATE 压掉，
 * 比真的写满 16bit 数据更小。
 *
 * 滤波用 Paeth（类型 4），照片内容上压缩率最好。整个文件是**一个** zlib 流，
 * 分块写成多个 IDAT（每块约 1MB），而不是每行压一次——多个 zlib 流拼在一起
 * PNG 解码器只认第一个。
 */
int encodePng16(const uint32_t *px, int sw, int sh, int rot, int level, const char *path,
                int *outBytes, int *outMs) {
    const int64_t t0 = nowMs();
    int dw = 0, dh = 0;
    deepDestSize(sw, sh, rot, &dw, &dh);
    const size_t rowBytes = (size_t) dw * 6;
    const int bpp = 6;

    FILE *f = fopen(path, "wb");
    if (!f) {
        APNG("打不开 %s", path);
        return -1;
    }
    static const uint8_t kSig[8] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    fwrite(kSig, 1, 8, f);

    {
        std::vector<uint8_t> ihdr;
        put32be(ihdr, (uint32_t) dw);
        put32be(ihdr, (uint32_t) dh);
        ihdr.push_back(16);   // 位深
        ihdr.push_back(2);    // 颜色类型 2 = RGB
        ihdr.push_back(0);
        ihdr.push_back(0);
        ihdr.push_back(0);
        writeChunk(f, "IHDR", ihdr.data(), ihdr.size());
    }

    z_stream zs;
    memset(&zs, 0, sizeof(zs));
    if (deflateInit(&zs, level) != Z_OK) {
        fclose(f);
        return -2;
    }
    std::vector<uint8_t> obuf(1 << 20);
    std::vector<uint8_t> prev(rowBytes, 0), row(rowBytes, 0);
    std::vector<uint8_t> cur(rowBytes + 1, 0);
    long totalOut = 0;

    auto drain = [&](int flush) -> bool {
        for (;;) {
            zs.next_out = obuf.data();
            zs.avail_out = (uInt) obuf.size();
            const int rc = deflate(&zs, flush);
            if (rc == Z_STREAM_ERROR) return false;
            const size_t have = obuf.size() - zs.avail_out;
            if (have) {
                writeChunk(f, "IDAT", obuf.data(), have);
                totalOut += (long) have;
            }
            if (flush == Z_NO_FLUSH) {
                if (zs.avail_out != 0) break;      // 输出没满 = 这一轮已经吐完
            } else if (rc == Z_STREAM_END) {
                break;
            }
        }
        return true;
    };

    for (int y = 0; y < dh; ++y) {
        uint8_t *p = row.data();
        for (int x = 0; x < dw; ++x) {
            int sx, sy;
            deepMap(x, y, sw, sh, rot, &sx, &sy);
            const uint32_t q = px[(size_t) sy * sw + sx];
            const uint16_t r = (uint16_t) (pixR(q) << 6);
            const uint16_t g = (uint16_t) (pixG(q) << 6);
            const uint16_t b = (uint16_t) (pixB(q) << 6);
            *p++ = (uint8_t) (r >> 8);
            *p++ = (uint8_t) r;
            *p++ = (uint8_t) (g >> 8);
            *p++ = (uint8_t) g;
            *p++ = (uint8_t) (b >> 8);
            *p++ = (uint8_t) b;
        }
        cur[0] = 4;                                  // Paeth
        for (size_t i = 0; i < rowBytes; ++i) {
            const int a = i >= (size_t) bpp ? row[i - bpp] : 0;
            const int b = prev[i];
            const int c = i >= (size_t) bpp ? prev[i - bpp] : 0;
            cur[i + 1] = (uint8_t) ((row[i] - paeth(a, b, c)) & 0xFF);
        }
        prev = row;

        zs.next_in = cur.data();
        zs.avail_in = (uInt) (rowBytes + 1);
        if (!drain(Z_NO_FLUSH)) {
            deflateEnd(&zs);
            fclose(f);
            return -3;
        }
    }
    zs.next_in = nullptr;
    zs.avail_in = 0;
    if (!drain(Z_FINISH)) {
        deflateEnd(&zs);
        fclose(f);
        return -4;
    }
    deflateEnd(&zs);

    writeChunk(f, "IEND", nullptr, 0);
    const long sz = ftell(f);
    fclose(f);

    if (outBytes) *outBytes = (int) sz;
    if (outMs) *outMs = (int) (nowMs() - t0);
    APNG("PNG16 完成 %dx%d  %.1f MB  %dms  (IDAT %ld 字节)", dw, dh, sz / 1048576.0,
         (int) (nowMs() - t0), totalOut);
    return sz > 0 ? 0 : -5;
}

}  // namespace camhdr
