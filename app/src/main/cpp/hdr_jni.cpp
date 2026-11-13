// JNI 桥。只做参数搬运，不放任何算法。
//
// 大数组一律用 GetPrimitiveArrayCritical（等价于 pin 住堆内存，
// 避免 12MP 级别数组被 memcpy 一遍——那样会白花几百毫秒）。
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <vector>

#include "hdr_common.h"
#include "hdr_api.h"
#include "heif_encode.h"
#include "png16_encode.h"

extern "C" {

JNIEXPORT jint JNICALL
Java_com_camera_app_NativeHdr_nativeVersion(JNIEnv *, jclass) { return 32; }

JNIEXPORT void JNICALL
Java_com_camera_app_NativeHdr_nativeMerge(JNIEnv *env, jclass,
                                          jobjectArray frames, jint w, jint h,
                                          jlongArray jexposures, jint refIdx,
                                          jint black, jint white,
                                          jshortArray jout) {
    const int n = env->GetArrayLength(frames);
    if (n <= 0) return;

    std::vector<jshort *> pinned(n, nullptr);
    std::vector<const uint16_t *> ptrs(n, nullptr);
    for (int i = 0; i < n; ++i) {
        jshortArray arr = (jshortArray) env->GetObjectArrayElement(frames, i);
        pinned[i] = env->GetShortArrayElements(arr, nullptr);
        ptrs[i] = reinterpret_cast<const uint16_t *>(pinned[i]);
        env->DeleteLocalRef(arr);
    }

    (void) jexposures;
    (void) refIdx;
    jshort *out = env->GetShortArrayElements(jout, nullptr);
    camhdr::hdrplusMerge(ptrs.data(), n, w, h, black, white,
                         reinterpret_cast<uint16_t *>(out));
    env->ReleaseShortArrayElements(jout, out, 0);

    for (int i = 0; i < n; ++i) {
        jshortArray arr = (jshortArray) env->GetObjectArrayElement(frames, i);
        env->ReleaseShortArrayElements(arr, pinned[i], JNI_ABORT);
        env->DeleteLocalRef(arr);
    }
}

JNIEXPORT void JNICALL
Java_com_camera_app_NativeHdr_nativeRender(JNIEnv *env, jclass,
                                           jshortArray jraw, jint w, jint h,
                                           jintArray jblack4, jfloat white,
                                           jfloatArray jshading, jint mw, jint mh,
                                           jintArray jchannelOf, jintArray jcfa,
                                           jfloatArray jwb, jfloatArray jmatrix,
                                           jint useRcd, jint hlRecon, jfloat drcK,
                                           jfloat huePreserve, jint wbRefine,
                                           jshortArray jrgb3,
                                           jintArray jout) {
    jshort *raw = env->GetShortArrayElements(jraw, nullptr);
    jint *black4 = env->GetIntArrayElements(jblack4, nullptr);
    jint *channelOf = env->GetIntArrayElements(jchannelOf, nullptr);
    jint *cfa = env->GetIntArrayElements(jcfa, nullptr);
    jfloat *wb = env->GetFloatArrayElements(jwb, nullptr);
    jfloat *mat = env->GetFloatArrayElements(jmatrix, nullptr);
    jfloat *shading = jshading ? env->GetFloatArrayElements(jshading, nullptr) : nullptr;
    jshort *rgb3 = nullptr;
    if (jrgb3) {
        rgb3 = env->GetShortArrayElements(jrgb3, nullptr);
        // 长度必须是 raw 的 3 倍（R/G/B 三个平面）
        if (env->GetArrayLength(jrgb3) < 3L * w * h) rgb3 = nullptr;
    }
    jint *out = env->GetIntArrayElements(jout, nullptr);

    camhdr::render(reinterpret_cast<const uint16_t *>(raw), w, h,
                  black4, white, shading, mw, mh, channelOf, cfa, wb, mat,
                  useRcd, hlRecon, drcK, huePreserve, wbRefine,
                  reinterpret_cast<const uint16_t *>(rgb3),
                  reinterpret_cast<uint32_t *>(out));

    env->ReleaseIntArrayElements(jout, out, 0);
    if (rgb3) env->ReleaseShortArrayElements(jrgb3, rgb3, JNI_ABORT);
    if (shading) env->ReleaseFloatArrayElements(jshading, shading, JNI_ABORT);
    env->ReleaseFloatArrayElements(jmatrix, mat, JNI_ABORT);
    env->ReleaseFloatArrayElements(jwb, wb, JNI_ABORT);
    env->ReleaseIntArrayElements(jcfa, cfa, JNI_ABORT);
    env->ReleaseIntArrayElements(jchannelOf, channelOf, JNI_ABORT);
    env->ReleaseIntArrayElements(jblack4, black4, JNI_ABORT);
    env->ReleaseShortArrayElements(jraw, raw, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_camera_app_NativeHdr_nativeSuperRes(JNIEnv *env, jclass,
                                             jobjectArray frames, jint w, jint h,
                                             jint black, jint white,
                                             jintArray jcfa, jfloat sigmaR,
                                             jfloatArray jshading, jint mw, jint mh,
                                             jintArray jchannelOf, jint lanes,
                                             jshortArray jout3) {
    const int n = env->GetArrayLength(frames);
    if (n <= 0) return -1;

    const jlong need = (jlong) w * h;
    if (env->GetArrayLength(jout3) < need * 3) return -2;

    std::vector<jshort *> pinned(n, nullptr);
    std::vector<const uint16_t *> ptrs(n, nullptr);
    for (int i = 0; i < n; ++i) {
        jshortArray arr = (jshortArray) env->GetObjectArrayElement(frames, i);
        pinned[i] = env->GetShortArrayElements(arr, nullptr);
        ptrs[i] = reinterpret_cast<const uint16_t *>(pinned[i]);
        env->DeleteLocalRef(arr);
    }

    jint *cfa = env->GetIntArrayElements(jcfa, nullptr);
    jint *channelOf = env->GetIntArrayElements(jchannelOf, nullptr);
    jfloat *shading = jshading ? env->GetFloatArrayElements(jshading, nullptr) : nullptr;
    jshort *o3 = env->GetShortArrayElements(jout3, nullptr);

    uint16_t *oR = reinterpret_cast<uint16_t *>(o3);
    int rc = camhdr::hdrplusSuperRes(ptrs.data(), n, w, h, black, white, cfa, sigmaR,
                                     shading, mw, mh, channelOf, lanes,
                                     oR, oR + need, oR + 2 * need);

    env->ReleaseShortArrayElements(jout3, o3, 0);
    if (shading) env->ReleaseFloatArrayElements(jshading, shading, JNI_ABORT);
    env->ReleaseIntArrayElements(jchannelOf, channelOf, JNI_ABORT);
    env->ReleaseIntArrayElements(jcfa, cfa, JNI_ABORT);
    for (int i = 0; i < n; ++i) {
        jshortArray arr = (jshortArray) env->GetObjectArrayElement(frames, i);
        env->ReleaseShortArrayElements(arr, pinned[i], JNI_ABORT);
        env->DeleteLocalRef(arr);
    }
    return rc;
}

JNIEXPORT void JNICALL
Java_com_camera_app_NativeHdr_nativeFinish(JNIEnv *env, jclass,
                                           jintArray jpx, jint w, jint h,
                                           jfloat tmo, jfloat chroma, jfloat sharpen,
                                           jfloat ca, jfloat hue,
                                           jint enableTmo, jint enableNr, jint quantize) {
    jint *px = env->GetIntArrayElements(jpx, nullptr);
    camhdr::finish(reinterpret_cast<uint32_t *>(px), w, h, tmo, chroma, sharpen,
                  ca, hue, enableTmo, enableNr, quantize);
    env->ReleaseIntArrayElements(jpx, px, 0);
}

JNIEXPORT jint JNICALL
Java_com_camera_app_NativeHdr_nativeHeifProbe(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    const int r = camhdr::heifProbe(path ? path : "/data/local/tmp/_p.heif");
    if (path) env->ReleaseStringUTFChars(jpath, path);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_camera_app_NativeHdr_nativeHeifEncode(JNIEnv *env, jclass, jintArray jpx,
                                               jint w, jint h, jint rot, jint quality,
                                               jstring jpath) {
    jint *px = env->GetIntArrayElements(jpx, nullptr);
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    int bytes = 0, ms = 0;
    const int rc = camhdr::encodeHeif10(reinterpret_cast<uint32_t *>(px), w, h, rot, quality,
                                        10, path ? path : "/data/local/tmp/_x.heif", &bytes, &ms);
    if (path) env->ReleaseStringUTFChars(jpath, path);
    env->ReleaseIntArrayElements(jpx, px, JNI_ABORT);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_camera_app_NativeHdr_nativeHeifSelfTest(JNIEnv *env, jclass, jint w, jint h,
                                                 jint quality, jint bitDepth, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    const int rc = camhdr::heifSelfTest(w, h, quality, bitDepth,
                                        path ? path : "/data/local/tmp/_x.heif");
    if (path) env->ReleaseStringUTFChars(jpath, path);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_camera_app_NativeHdr_nativePng16(JNIEnv *env, jclass, jintArray jpx,
                                          jint w, jint h, jint rot, jint level, jstring jpath) {
    jint *px = env->GetIntArrayElements(jpx, nullptr);
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    int bytes = 0, ms = 0;
    const int rc = camhdr::encodePng16(reinterpret_cast<uint32_t *>(px), w, h, rot, level,
                                       path ? path : "/data/local/tmp/_x.png", &bytes, &ms);
    if (path) env->ReleaseStringUTFChars(jpath, path);
    env->ReleaseIntArrayElements(jpx, px, JNI_ABORT);
    return rc;
}

JNIEXPORT void JNICALL
Java_com_camera_app_NativeHdr_nativeQuantize(JNIEnv *env, jclass, jintArray jpx, jint w, jint h) {
    jint *px = env->GetIntArrayElements(jpx, nullptr);
    camhdr::quantizeToArgb(reinterpret_cast<uint32_t *>(px), w, h);
    env->ReleaseIntArrayElements(jpx, px, 0);
}

JNIEXPORT jint JNICALL
Java_com_camera_app_NativeHdr_nativeFixDefects(JNIEnv *env, jclass, jshortArray jraw,
                                               jint w, jint h, jint black, jfloat white) {
    if (env->GetArrayLength(jraw) < (jsize) ((jlong) w * h)) return -1;
    jshort *raw = env->GetShortArrayElements(jraw, nullptr);
    const int n = camhdr::fixDefects(reinterpret_cast<uint16_t *>(raw), w, h, black, white);
    // mode 0 = 拷回并释放：这一步是就地改数据
    env->ReleaseShortArrayElements(jraw, raw, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_camera_app_NativeHdr_nativeJpeg444(JNIEnv *env, jclass, jintArray jpx,
                                            jint w, jint h, jint rot, jint quality,
                                            jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    jint *px = env->GetIntArrayElements(jpx, nullptr);
    int bytes = 0;
    const int rc = camhdr::encodeJpeg444(reinterpret_cast<uint32_t *>(px), w, h, rot, quality,
                                         path ? path : "/data/local/tmp/_x.jpg", &bytes);
    env->ReleaseIntArrayElements(jpx, px, JNI_ABORT);
    if (path) env->ReleaseStringUTFChars(jpath, path);
    return rc == 0 ? bytes : rc;
}

}  // extern "C"
