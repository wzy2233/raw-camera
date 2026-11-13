#ifndef CAMHDR_PNG16_ENCODE_H
#define CAMHDR_PNG16_ENCODE_H

#include <cstdint>

namespace camhdr {

/**
 * 10bit RGB → 16bit PNG（无损、4:4:4）。
 * @param px    finish 输出的 10bit 打包像素（R<<20|G<<10|B）
 * @param rot   SENSOR_ORIENTATION（0/90/180/270，顺时针），在写出时逐像素旋转
 * @param level zlib 压缩级别 1..9
 */
int encodePng16(const uint32_t *px, int sw, int sh, int rot, int level, const char *path,
                int *outBytes, int *outMs);

}  // namespace camhdr

#endif  // CAMHDR_PNG16_ENCODE_H
