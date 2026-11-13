package com.camera.app

import android.util.Log

/**
 * native 管线（C++/NEON）的桥。
 *
 * 为什么搬：12MP × 5 帧的逐像素循环原来全在 Kotlin，靠 JIT 跑，
 * 骁龙 8 系上约 9 秒，老机器 20 秒以上。同样的循环用 clang -O3 + NEON
 * 多线程，实测能到 1/3 以内，而且没有 JIT 预热开销——冷启动第一张就是这个速度。
 *
 * 加载失败（架构不支持 / so 没打进包）时 [available] 为 false，
 * 上层自动退回原来的 Kotlin 实现，功能不会缺。
 */
object NativeHdr {

    private const val TAG = "NativeHdr"

    @Volatile
    var available: Boolean = false
        private set

    /** so 里 nativeVersion() 的返回值。改了 C++ 就应该能在这里看到版本号变了——
     *  "日志里还是旧版本号"就等于"这个 so 没打进包"，能省掉一轮瞎猜。 */
    @Volatile
    var version: Int = 0
        private set

    init {
        try {
            System.loadLibrary("camhdr")
            version = nativeVersion()
            available = version >= 1
            Log.d(TAG, "native 管线已加载，版本 $version")
        } catch (e: Throwable) {
            Log.w(TAG, "native 管线加载失败，退回 Kotlin 实现: ${e.message}")
            available = false
        }
    }

    @JvmStatic
    private external fun nativeVersion(): Int

    /**
     * 多帧合并（对齐 + 逐相位加权）。
     * frames 用 Array<ShortArray> 传，JNI 侧直接 pin 内存，避免 125MB 的拷贝。
     */
    @JvmStatic
    external fun nativeMerge(
        frames: Array<ShortArray>,
        w: Int,
        h: Int,
        exposures: LongArray,
        refIndex: Int,
        black: Int,
        white: Int,
        out: ShortArray
    )

    /** 渲染：暗角 + 归一化 + RCD + 白平衡/矩阵 + 动态范围压缩 + 色调曲线 -> ARGB */
    @JvmStatic
    external fun nativeRender(
        raw: ShortArray,
        w: Int,
        h: Int,
        black4: IntArray,
        white: Float,
        shading: FloatArray?,
        mw: Int,
        mh: Int,
        channelOf: IntArray,
        cfaColors: IntArray,
        wbGains: FloatArray,
        matrix: FloatArray,
        useRcd: Int,
        hlRecon: Int,
        /**
         * 动态范围压缩比 = 采集时的欠曝倍数。1.0 表示不压缩。
         * 它决定两件事：高光余量，以及 §6 exposure fusion 里两个合成曝光的倍数。
         */
        drcK: Float,
        /**
         * 色相保持修正强度 0..1。
         * 显示曲线是逐通道套的，逐通道非线性会随亮度旋转色相（实测 Y≈55~90 多转 +12.5°），
         * 在平滑的明暗过渡上就是一条跟着场景走的色带。1 = 用保色相版本的方向修正色相。
         */
        huePreserve: Float,
        /** 1 = 用图像本身的 log-chroma 直方图重估白平衡（与 ISP 结果按 α=0.35 融合） */
        wbRefine: Int,
        /**
         * 超分分支：三平面线性 RGB（R 平面在前，每平面 w*h，Q14 定点）。
         * 非空时跳过去马赛克，其余色彩链路不变。按 R/G/B 顺序拼接。
         */
        rgb3: ShortArray?,
        out: IntArray
    )

    /**
     * 多帧亚像素超分合并：分层对齐 -> LK 亚像素细化 -> 逐通道核回归。
     * [out3] 长度必须 ≥ 3*w*h，按 R/G/B 三个平面连续写入（Q14 定点）。
     * 返回 0 = 成功。
     */
    @JvmStatic
    external fun nativeSuperRes(
        frames: Array<ShortArray>,
        w: Int,
        h: Int,
        black: Int,
        white: Int,
        cfaColors: IntArray,
        sigmaR: Float,
        shading: FloatArray?,
        mw: Int,
        mh: Int,
        channelOf: IntArray,
        /** 核回归的线程数上限（0 = 用 laneCount()）。手机是 1+3+4 大小核，见 parallelRows 注释 */
        lanes: Int,
        out3: ShortArray
    ): Int

    /** 收尾：局部影调 + 色度降噪 + 色差校正 + 亮度锐化 + 色相微调 + 抖动 */
    /** HEIF 能力探针，返回 0 表示 HEVC Main10 + P010 + HEIF muxer 全部可用 */
    @JvmStatic
    external fun nativeHeifProbe(path: String): Int

    /** 从 10bit 打包像素直接编 HEIF（HEVC Main10），返回 0 表示成功 */
    @JvmStatic
    external fun nativeHeifEncode(px: IntArray, w: Int, h: Int, rot: Int, quality: Int, path: String): Int

    /** 从 10bit 打包像素写 16bit PNG（无损、4:4:4），返回 0 表示成功 */
    @JvmStatic
    external fun nativePng16(px: IntArray, w: Int, h: Int, rot: Int, level: Int, path: String): Int

    /** 把 10bit 打包像素就地转成 8bit ARGB（给预览/缩略图用） */
    @JvmStatic
    external fun nativeQuantize(px: IntArray, w: Int, h: Int)

    /**
     * 坏点修复，就地改 [raw]（DN，未减黑电平）。返回修掉的像素数，<0 表示参数不合法。
     *
     * native 版约 40ms，Kotlin 版 340ms。这一步在整条链路最前面，省的是直接可感知的时间。
     */
    @JvmStatic
    external fun nativeFixDefects(raw: ShortArray, w: Int, h: Int, black: Int, white: Float): Int

    /**
     * JPEG 4:4:4 编码，直接落盘。返回字节数，<0 表示失败。
     *
     * 输入是 8bit ARGB（尺寸 [w]×[h]），[rot] 为 90/180/270 时在采样阶段旋转。
     * 实测 12MP q95：Kotlin 版 1083ms → native 版约 200ms（DCT 能吃 NEON）。
     */
    @JvmStatic
    external fun nativeJpeg444(px: IntArray, w: Int, h: Int, rot: Int, quality: Int,
                               path: String): Int

    /** 合成图自检 */
    @JvmStatic
    external fun nativeHeifSelfTest(w: Int, h: Int, quality: Int, bitDepth: Int, path: String): Int

    @JvmStatic
    external fun nativeFinish(
        px: IntArray,
        w: Int,
        h: Int,
        tmoStrength: Float,
        chromaStrength: Float,
        sharpenAmount: Float,
        caStrength: Float,
        hueStrength: Float,
        enableTmo: Int,
        enableNr: Int,
        /** 1 = 末尾量化成 8bit ARGB；0 = 保留 10bit 打包（高位深输出用） */
        quantize: Int
    )
}
