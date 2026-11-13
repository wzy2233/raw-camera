package com.camera.app

import android.content.Context

/**
 * RAW 解码链上每一步的开关。
 *
 * 之所以每一步都能单独关：这条链路上每一步都在"改"传感器的原始数据，
 * 有时候你就是想看没改过的样子，或者想定位是哪一步把画面搞坏了。
 * 全部关掉 = 只做"减黑电平→归一化→sRGB 编码"，最接近传感器原始输出。
 *
 * 顺序和实际处理顺序一致，设置页也按这个顺序排。
 */
object RawSettings {

    private const val PREF = "raw_pipeline"

    /** 减黑电平（黑白电平归一化的前置步骤） */
    var blackLevel: Boolean
        get() = get(PREF, "black", true)
        set(v) = put(PREF, "black", v)

    /** 坏点 / 热像素修正 */
    var defectFix: Boolean
        get() = get(PREF, "defect", true)
        set(v) = put(PREF, "defect", v)

    /** 暗角（镜头阴影）补偿 */
    var shading: Boolean
        get() = get(PREF, "shading", true)
        set(v) = put(PREF, "shading", v)

    /** 去马赛克：true = RCD（方向自适应），false = 双线性 */
    var rcd: Boolean
        get() = get(PREF, "rcd", true)
        set(v) = put(PREF, "rcd", v)

    /** 白平衡（乘 AsShotNeutral 反算出的通道增益） */
    var whiteBalance: Boolean
        get() = get(PREF, "wb", true)
        set(v) = put(PREF, "wb", v)

    /** 3x3 色彩矩阵（相机 RGB -> sRGB） */
    var colorMatrix: Boolean
        get() = get(PREF, "ccm", true)
        set(v) = put(PREF, "ccm", v)

    /** 高光重建：任一通道到白电平就把该像素恢复成中性白 */
    var highlightRecon: Boolean
        get() = get(PREF, "hlrecon", true)
        set(v) = put(PREF, "hlrecon", v)

    /** 高光肩部：超过 0.88 的部分平滑压向 1，避免硬削顶 */
    var shoulder: Boolean
        get() = get(PREF, "shoulder", true)
        set(v) = put(PREF, "shoulder", v)

    /**
     * HDR+（Hasinoff 2016）：**等曝光**欠曝连拍 5 帧 → 四级金字塔分块对齐 →
     * 分块 2D DFT 上的成对 Wiener 融合 → 曝光融合做动态范围压缩。
     *
     * 等曝光是前提，不是省事：不同曝光的帧混在一起，同一个 2×2 Bayer 单元里
     * R/G/B 相位常常来自不同帧，白平衡关系一断就偏色（前几版反复出现的
     * 发绿、暗部发紫、亮缝黑块都是这么来的）。
     *
     * 代价：需要场景静止（5 帧约 1.5 秒），只影响成片；DNG 保存第一帧原始数据。
     */
    var hdr: Boolean
        get() = get(PREF, "hdr", false)
        set(v) = put(PREF, "hdr", v)

    /**
     * HDR 局部影调（收尾阶段的 local tone mapping，与 DRC 是两层不同的东西）。
     *
     * DRC（hdr_drc.cpp）负责"整体影调搬进 8bit"；这一层负责"局部对比"——
     * 把画面拆成"基底 + 细节"，只抬暗区基底、细节全留，暗部才有质感。
     * 关掉只会少一点暗部层次，不影响曝光是否正确。
     */
    var hdrLocalContrast: Boolean
        get() = get(PREF, "hdrTmo", true)
        set(v) = put(PREF, "hdrTmo", v)

    /**
     * HDR 色度降噪。
     *
     * HDR 提亮暗部时会把 R/B 的噪声一起放大（白平衡 R×1.85、B×1.62，本来就比 G 脏），
     * 实测暗部 Cb/Cr 的 σ 是量化步长的 6~7 倍。这里只平滑色度、亮度逐位不动，
     * 所以彩色颗粒会消失但细节不糊。
     */
    var hdrChromaNr: Boolean
        get() = get(PREF, "hdrNr", true)
        set(v) = put(PREF, "hdrNr", v)

    /**
     * 局部影调强度，单位百分比。
     * 65 = 自然（接近单帧的亮部关系，只把暗部拉开一点）
     * 100 = 标准
     * 135 = 强（暗部大幅拉开，接近"轻 HDR"的观感）
     */
    var hdrStrength: Int
        get() = prefs(null)?.getInt("hdrStrength", 100) ?: 100
        set(v) = prefs(null)?.edit()?.putInt("hdrStrength", v.coerceIn(0, 200))?.apply() ?: Unit

    /**
     * 用 native（C++/NEON）管线代替 Kotlin 实现。
     *
     * 默认开：同样的逐像素循环，native 快 2~3 倍，老机器尤其明显。
     * 关掉就退回原来的 Kotlin 版（两边数学一致，用于对照和出问题时的逃生口）。
     */
    var nativePipeline: Boolean
        get() = get(PREF, "nativePipe", true)
        set(v) = put(PREF, "nativePipe", v)

    /**
     * 高光余量（欠曝档数）。**0 = 按场景自动**（推荐），1/2/3/4 = 手动固定。
     *
     * HDR+ 的核心之一：采集时**故意欠曝**，让高光有机会被记下来，暗部交给多帧平均去补。
     * 论文允许最多 8 倍（3 档），但实测 96% 的场景 8 倍就够、多数只要 1~2 倍。
     *
     * 为什么不能写死："欠曝 K 倍"等于把暗部信号变弱 K 倍，而 5 帧只能补回 √5≈2.2 倍。
     * 拍没有高光的场景（阴天、白墙、室内平光）还欠 2 倍，就是白丢 0.5 档暗部信噪比。
     * 自动模式看 settle 阶段预览帧的贴顶比例和 P99.5 分位来决定，见
     * RawCaptureController.chooseUnderexpose。
     *
     *   0 = 自动（默认）
     *   1 = 不欠曝（等于放弃高光余量，最干净）
     *   2 = 欠 1 档
     *   4 = 欠 2 档，光比极大的场景（窗外、灯下）用
     */
    var hdrUnderexpose: Int
        get() = (prefs(null)?.getInt("hdrUexp", 0) ?: 0).coerceIn(0, 4)
        set(v) = prefs(null)?.edit()?.putInt("hdrUexp", v.coerceIn(0, 4))?.apply() ?: Unit

    /**
     * 锐化强度（百分比）。0 = 关，30 = 默认（与原来的 3×3 USM 观感对齐）。
     *
     * 现在的锐化是**多尺度金字塔**（≈1.5 / 3 / 6 px 三个频段），
     * 所以同一个数值和以前的 3×3 USM 不是同一种"锐"：这个数主要控制
     * 各频段的增益，不会像 USM 那样在边缘压出一圈白边。
     */
    var sharpenAmount: Int
        get() = (prefs(null)?.getInt("sharp", 30) ?: 30).coerceIn(0, 100)
        set(v) = prefs(null)?.edit()?.putInt("sharp", v.coerceIn(0, 100))?.apply() ?: Unit

    /**
     * 白平衡重估：用图像本身的 log-chroma 直方图再估一次，和 ISP 的结果按 α=0.35 融合。
     *
     * 为什么需要：低光下 ISP 的 AWB 会失效（Night Sight 论文列为最严重失效模式），
     * 而它给出的增益会被整段连拍沿用。这里在**合并后的 RAW**上重新统计
     * "中性色在哪"，噪声比 ISP 的预览图低一个量级。
     *
     * **默认关闭**：白纸 A/B 验证结果是"不达标"——B 改善了 2.5 个百分点，
     * 但 R 被改反了（真实需要 -2%，算法给了 -9.7%，大了 4.6 倍）。
     * 而 ISP 本身只偏 2~3%，已经在肉眼不可辨的范围，不值得用一个不可靠的估计去动它。
     * 根因未完全定位（已排除 shading）。代码保留，等找到问题再默认打开。
     */
    var wbRefine: Boolean
        get() = get(PREF, "wbRefine", false)
        set(v) = put(PREF, "wbRefine", v)

    /**
     * 色差校正。
     *
     * 横向色差的表现是"亮度边缘处冒出一条纯色镶边"。这里不建镜头模型，
     * 只在亮度梯度大的位置用 2 像素外的邻居色度中位数替换，平坦区一个像素都不碰。
     */
    var caFix: Boolean
        get() = get(PREF, "caFix", true)
        set(v) = put(PREF, "caFix", v)

    /**
     * 色相微调：把偏青偏紫的蓝推向透亮的蓝，并给蓝、绿加一点饱和度。
     *
     * 蓝天的观感问题往往不是"不够蓝"，而是蓝里混了品红（大气散射 + 白平衡把 R 也抬了）。
     */
    var hueTune: Boolean
        get() = get(PREF, "hueTune", true)
        set(v) = put(PREF, "hueTune", v)

    /**
     * 色相保持修正。
     *
     * 色调曲线是**逐通道**套上去的。逐通道非线性会随亮度旋转色相：同一块木头，
     * 亮一点和暗一点的部分色相差十几度；投影到画面里就是"跟着明暗走的色带"，
     * 在阴影过渡、桌面反光这类平滑渐变上最明显。
     *
     * 打开后：按亮度查同一条曲线得到"保色相"的颜色，只取它的**色相方向**，
     * 色度幅值仍用逐通道版本的——饱和度和现在一致，只是色相不再随亮度漂。
     */
    var huePreserve: Boolean
        get() = get(PREF, "huePreserve", true)
        set(v) = put(PREF, "huePreserve", v)

    /**
     * 白平衡来源：true = COLOR_CORRECTION_GAINS（= DNG 里 AsShotNeutral 的反算，
     * 也就是 Lightroom 打开同一张 DNG 时用的那组）；false = SENSOR_NEUTRAL_COLOR_POINT。
     *
     * 实测这台机器两条路差 6.6%（R 增益 1.482 vs 1.580），后者会让整体偏红。
     */
    var wbFromCcGains: Boolean
        get() = get(PREF, "wbFromCc", true)
        set(v) = put(PREF, "wbFromCc", v)

    /**
     * 多帧亚像素超分（SuperRes 2019）：不做去马赛克，用连拍的亚像素位移
     * 把三个颜色通道各自欠采样的信息补回来，直接重建全 RGB。
     *
     * 和 HDR 合并的区别：HDR 合并是"整像素对齐后平均"，只降噪不提分辨率；
     * 超分要求位移**不是整数**（手抖天然满足），于是 5 帧的采样格点互相错开，
     * 信息量真的增加了。离线实测（真实照片合成 burst，20 组随机位移）：
     * 比"整像素平均 + 去马赛克"高 +3.66 dB，20 组全为正、最差也有 +2.08。
     *
     * 代价：多花约 0.6~1 秒、多占 75 MB（三个 12MP 的 16bit 平面）。
     */
    var superRes: Boolean
        get() = get(PREF, "superRes", false)
        set(v) = put(PREF, "superRes", v)

    /**
     * 超分核宽 σ_r（全分辨率像素）。
     * 0.5 最锐（+3.66 dB）、0.9 最稳（+2.45 dB，对位移估计误差最不敏感）。
     * 默认 0.6：实际位移由 LK 估到 0.05~0.1 px，0.5 也够，但留一点余量更保险。
     */
    var srSigma: Float
        get() = prefs(null)?.getFloat("srSigma", 0.6f) ?: 0.6f
        set(v) = prefs(null)?.edit()?.putFloat("srSigma", v.coerceIn(0.3f, 1.5f))?.apply() ?: Unit

    /**
     * A/B 对比开关：开启后每次 HDR 出图会额外存一张**同一份数据、锐化关掉**的 JPEG
     * （文件名 `_ab_nosharp_<时间戳>.jpg`，和正常出图在同一目录）。
     *
     * 为什么需要它：两张独立的照片对比不可靠——构图偏一点、手抖一点、对焦差一点，
     * 都比"锐化强度"的影响大。只有同一次拍摄、同一份合并数据、只差一个参数，
     * 才能看出锐化到底做了什么。调参期间开，平时关。
     */
    var abTest: Boolean
        get() = get(PREF, "abTest", false)
        set(v) = put(PREF, "abTest", v)

    /**
     * 核回归的线程数上限。0 = 不限制（用 hardware_concurrency，本机是 8）。
     *
     * 手机是 1+3+4 的大小核，开满 8 个线程意味着有 4 个落在 A5xx 小核上。
     * 计算密集的 pass 少开几个线程、让每个都落在强核上，墙钟可能更短。
     * 实测见 HdrSR 日志的「并行效率」行。
     */
    var srLanes: Int
        get() = (prefs(null)?.getInt("srLanes", 0) ?: 0).coerceIn(0, 8)
        set(v) = prefs(null)?.edit()?.putInt("srLanes", v.coerceIn(0, 8))?.apply() ?: Unit

    /**
     * 白平衡 A/B：同一次拍摄额外存一张**只用 ISP 增益**的版本（`_ab_wbisp_*.jpg`）。
     * 用来判断重估到底让中性色更接近中性，还是更远。
     */
    var abWb: Boolean
        get() = get(PREF, "abWb", false)
        set(v) = put(PREF, "abWb", v)

    /**
     * 超分 A/B：同一次拍摄额外存一张**关闭超分**的普通 HDR 结果
     * （`_ab_nosr_<时间戳>.jpg`）。
     *
     * 为什么必须这样：两次独立拍摄的取景、对焦、手抖都不同，
     * 实测同一坐标的亮度能差 3 倍——那种"对比"比的是场景，不是算法。
     */
    var abSrTest: Boolean
        get() = get(PREF, "abSrTest", false)
        set(v) = put(PREF, "abSrTest", v)

    /** 调试输出目录（app 的外部私有目录下的 CameraApp） */
    fun appDir(): java.io.File? {
        val c = app ?: return null
        val d = java.io.File(c.getExternalFilesDir(null), "CameraApp")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 输出格式 */
    const val FORMAT_JPEG444 = 0
    const val FORMAT_JPEG420 = 1

    /**
     * PNG **16bit 真彩**（无损、4:4:4）。
     *
     * 数据来自管线内部的 10bit 缓冲，左移 6 位放进 16bit。台阶 0.098%。
     * 体积很大（12MP 约 30~45MB），换来的是"一个像素都不丢"。
     */
    const val FORMAT_PNG = 2

    /**
     * HEIF（HEVC Main10 / 10bit / 4:2:0）。
     *
     * 注意这里的 4:2:0：硬件编码器只吃 P010，色度分辨率会掉到 1/4，
     * 所以它是"亮度 10bit + 省空间"的选项，不是"全色度"选项。
     * 容器由我们自己写（本机 ROM 的 AMediaMuxer 在 HEIF 模式下会静默写成 MP4）。
     */
    const val FORMAT_HEIF = 3

    /**
     * 无损 WebP。
     * 为什么用它替代 HEIF：无损 WebP 是 RGB 逐通道无损，天然 4:4:4，没有色度下采样；
     * 体积通常比 PNG 小 20~30%。而且是公开 API（WEBP_LOSSLESS 需 API 30，
     * 低版本用有损 WEBP 兜底）。要"4:4:4 + 压缩"这条需求，它是目前唯一能落地的。
     */
    const val FORMAT_WEBP = 4

    var format: Int
        get() {
            return prefs(null)?.getInt("format", FORMAT_JPEG444) ?: FORMAT_JPEG444
        }
        set(v) = prefs(null)?.edit()?.putInt("format", v)?.apply() ?: Unit

    /** JPEG 质量 1..100 */
    var jpegQuality: Int
        get() = prefs(null)?.getInt("quality", 95) ?: 95
        set(v) = prefs(null)?.edit()?.putInt("quality", v.coerceIn(1, 100))?.apply() ?: Unit

    /** 全部恢复默认（全开，质量 95） */
    fun resetAll(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // 需要一个 context 才能拿 SharedPreferences，用 application 持有的那个
    @Volatile
    private var app: Context? = null

    fun attach(context: Context) {
        app = context.applicationContext
    }

    private fun prefs(context: Context?) =
        (context ?: app)?.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun get(p: String, key: String, def: Boolean): Boolean =
        prefs(null)?.getBoolean(key, def) ?: def

    private fun put(p: String, key: String, v: Boolean) {
        prefs(null)?.edit()?.putBoolean(key, v)?.apply()
    }
}
