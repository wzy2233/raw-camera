#ifndef CAMHDR_DEEP_COMMON_H
#define CAMHDR_DEEP_COMMON_H

namespace camhdr {

/**
 * 高位深输出的方向处理。
 *
 * 8bit 那条路是 makeBitmap 用 Matrix 把 Bitmap 转正的，而 PNG/HEIF 是直接
 * 从 10bit 缓冲写出去的，所以旋转必须在这里做。用"目标坐标反查源坐标"的方式
 * 逐像素取值，不做插值——纯整数旋转，一个像素都不会被重新采样。
 *
 * rot 是 SENSOR_ORIENTATION（0/90/180/270，顺时针）。
 * 目标尺寸：rot 为 90/270 时宽高互换。
 */
inline void deepDestSize(int sw, int sh, int rot, int *dw, int *dh) {
    if (rot == 90 || rot == 270) {
        *dw = sh;
        *dh = sw;
    } else {
        *dw = sw;
        *dh = sh;
    }
}

inline void deepMap(int x, int y, int sw, int sh, int rot, int *sx, int *sy) {
    switch (rot) {
        case 90:   *sx = y;             *sy = sh - 1 - x; break;
        case 180:  *sx = sw - 1 - x;    *sy = sh - 1 - y; break;
        case 270:  *sx = sw - 1 - y;    *sy = x;          break;
        case 0:
        default:   *sx = x;             *sy = y;          break;
    }
}

}  // namespace camhdr

#endif  // CAMHDR_DEEP_COMMON_H
