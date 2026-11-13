package com.camera.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import java.io.File
import java.io.FileOutputStream

/**
 * RAW 采集。
 *
 * CameraX 1.3 不支持 RAW 输出，只能临时把相机从 CameraX 手里"接管"过来，
 * 走 Camera2 原始接口拿 RAW_SENSOR，再用 DngCreator 封装成 DNG。
 *
 * 流程：打开相机 -> 建会话(RAW + 一个小尺寸 YUV) -> 用小 YUV 跑 repeating request 让 AE/AWB 收敛
 *      -> 发起一次带 RAW 目标的拍照 -> 写 DNG -> 关掉，把相机还给 CameraX。
 */
class RawCaptureController(private val context: Context) {

    fun interface Callback {
        /** DNG 落盘后就回调，此时相机已经还给 CameraX */
        fun onResult(dng: File?, error: String?)

        /** 自研管线解出来的 JPEG 会晚一点到，失败就不回调 */
        fun onJpeg(jpg: File) {}
    }

    /** 色卡标定用：把 RAW 原样交出去，不写 DNG、不出 JPEG */
    fun interface RawCallback {
        /**
         * @param luma 同一帧的 YUV 亮度图（半分辨率、传感器方向）。
         *             色卡识别用它——ISP 处理过的亮度对比度正常，而且与 RAW 同视野，只差缩放。
         *             极少数情况下取不到，此时为 null。
         */
        fun onRaw(
            image: Image,
            characteristics: CameraCharacteristics,
            result: TotalCaptureResult,
            luma: Bitmap?
        )
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var yuvReader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var outputFile: File? = null
    private var rawCallback: RawCallback? = null

    private var finished = false
    private var callback: Callback? = null

    /** 一次性标记，AE 一收敛或超时就置位，防止重复触发 */
    private var captureFired = false
    private var startedAt = 0L
    private var openedAt = 0L

    // ---------------- HDR ----------------
    /** 开 HDR 时拍 3 帧不同曝光，在 RAW 域合并 */
    private var hdrMode = false
    private val hdrFrames = ArrayList<ShortArray>()
    /**
     * 连拍曝光序列：**全部等曝光**，这是 HDR+ 的前提。
     *
     * 旧方案是 −2/0/+2 包围，把不同曝光的帧混在一起融合。
     * 那正是我们前几轮所有色彩问题（偏绿、暗部发紫、键缝黑块）的根源：
     * 各帧曝光不同，同一个 2×2 单元里 R/G/B 相位常常来自不同帧，
     * 白平衡关系一断，颜色就错。HDR+ 的做法是等曝光欠曝连拍，
     * 动态范围交给后期 tone mapping 压，噪声靠多帧平均降。
     */
    /**
     * 本次连拍的**实际帧数**（运行时按场景决定，见 [chooseBurstCount]）。
     *
     * 原来写死 5 帧。为什么不写死：
     *   · 亮场景（ISO ≤ 400）噪点本来就小，5 帧是浪费——4 帧已经达到肉眼阈值，
     *     少一帧就少 24 MB 内存和 33 ms；
     *   · 暗场景（ISO > 1600）每多一帧，噪声降 1/√n，5→8 相当于多 0.34 档；
     *     而且**超分尤其吃帧数**——每个通道的有效样本数直接等于帧数×相位覆盖，
     *     5 帧时每通道只有 1~3 个样本，8 帧能到 3~5 个；
     *   · 但帧数不能无限加：总采集时间超过 0.9 秒，手持就会漂，
     *     所有帧一起糊，反而不如少拍几帧清楚的。
     */
    private var burstN = 5

    /** 每帧的 EV。等曝光是 HDR+ 的前提，这里恒为 0；长度由 burstN 决定。 */
    private val BURST_EV = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    /** 基准帧下标（等曝光下任意一帧都行，取第一帧省一次判断） */
    private val REF_INDEX = 0

    private val hdrExposures = ArrayList<Long>()
    private var hdrBaseImage: Image? = null
    private var hdrResult: TotalCaptureResult? = null
    private var hdrWhite = 1023
    private var hdrBlack = 64

    /**
     * 本次连拍的欠曝倍数（= 动态范围压缩比 K）。
     *
     * 论文 §3 的"故意欠曝"：AE 是按中灰测光的，高光早就爆了。
     * 欠曝 K 倍之后，同一个位置能记下 K 倍更亮的场景，这部分余量在收尾阶段
     * 用 exposure fusion 压回来——这就是 HDR 里"高光"那一半的来源。
     * 代价是暗部信号弱 K 倍，只能靠 5 帧平均补回约 1.2 档。
     */
    private var hdrDrcK = 2f

    /**
     * settle 阶段测到的场景高光统计（来自 ISP 的 YUV 预览帧）。
     *
     * [0] = Y 贴到白点（>=235）的像素占比；[1] = Y 的 99.5 分位。
     * -1 表示还没测到（刚打开相机）。
     *
     * 为什么用这两个：只看"贴顶比例"会被 ISP 的高光肩部骗——它会把接近饱和的
     * 区域压到 235 以下，于是"看起来没有过曝"。分位数补上这一半：即使没有
     * 像素真的贴顶，P99.5 高到 230 也说明高光就在白点边上，仍然需要余量。
     */
    @Volatile private var sceneClip = -1f
    @Volatile private var sceneP995 = -1f

    /** 起一次采样。调用前请先让 CameraX unbindAll，否则相机会被占用。 */
    fun capture(cameraId: String, file: File, settleMs: Long, cb: Callback, hdr: Boolean = false) {
        callback = cb
        outputFile = file
        rawCallback = null
        finished = false
        openedAt = System.currentTimeMillis()
        hdrMode = hdr
        hdrDrcK = 2f   // 占位：真正的值在 fireHdrBracket 里按场景定
        hdrFrames.clear()
        hdrExposures.clear()
        hdrBaseImage = null
        hdrResult = null
        openCameraInternal(cameraId, settleMs)
    }

    /**
     * 色卡标定：只要 RAW 数据，不写 DNG、不做 JPEG 解码。
     * 处理在相机线程上同步做（此时马上要关相机，不需要并发）。
     */
    fun captureRaw(cameraId: String, settleMs: Long, onRaw: RawCallback, cb: Callback? = null) {
        callback = cb
        outputFile = null
        rawCallback = onRaw
        finished = false
        openedAt = System.currentTimeMillis()
        openCameraInternal(cameraId, settleMs)
    }

    private fun openCameraInternal(cameraId: String, settleMs: Long) {
        val t = HandlerThread("raw-capture").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        armWatchdog()

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            characteristics = manager.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            finish(null, "读取相机参数失败")
            return
        }

        // 探测这台机器能不能出全像素（Quad Bayer 传感器默认是 4 合 1）
        try {
            val ch = characteristics!!
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val raw = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            Log.d(TAG, "RAW 尺寸（默认模式）: " +
                raw?.joinToString { "${it.width}x${it.height}" })
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                // SENSOR_PIXEL_MODES 是 name-based key，公开常量在部分 SDK 里取不到
                val modes = try {
                    ch.get(
                        CameraCharacteristics.Key("android.sensor.pixelModes", IntArray::class.java)
                    )
                } catch (e: Exception) {
                    null
                }
                Log.d(TAG, "SENSOR_PIXEL_MODES: " + (modes?.joinToString() ?: "不可读"))
                val maxMap = ch.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
                )
                val maxRaw = maxMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
                Log.d(TAG, "RAW 尺寸（全像素）: " +
                    (maxRaw?.joinToString { "${it.width}x${it.height}" } ?: "无"))
                val maxJpeg = maxMap?.getOutputSizes(ImageFormat.JPEG)
                    ?.maxByOrNull { it.width * it.height }
                Log.d(TAG, "最大 JPEG（全像素）: ${maxJpeg?.width}x${maxJpeg?.height}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "探测全像素能力失败: ${e.message}")
        }

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    Log.d(TAG, "预分段：打开设备 ${System.currentTimeMillis() - openedAt}ms")
                    createSession(settleMs)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                    finish(null, "相机被其他应用占用")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    finish(null, "打开相机失败: $error")
                }
            }, h)
        } catch (e: SecurityException) {
            finish(null, "没有相机权限")
        } catch (e: Exception) {
            finish(null, "打开相机失败: ${e.message}")
        }
    }

    private fun createSession(settleMs: Long) {
        val camera = device ?: return
        val ch = characteristics ?: return
        val h = handler ?: return

        // ---- ImageReader 与监听器**只建一次**，跨 session 复用 ----
        //
        // 常驻模式改成"device 常驻 + session 每枪重建"（见 endShot 的注释）：
        // close session 不会影响 ImageReader，但**绝不能重建 reader**——
        // 上一枪的 hdrBaseImage 可能还在转换线程里用，关掉 reader 会出错。
        if (rawReader == null) {
            val rawSizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            if (rawSizes.isNullOrEmpty()) {
                finish(null, "这台相机不支持 RAW 输出")
                return
            }
            val rawSize: Size = rawSizes.maxByOrNull { it.width.toLong() * it.height }!!

            // 小尺寸 YUV 只用来让 3A 跑起来、把曝光和白平衡收敛到位，不参与成像
            val yuvSizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
            val yuvSize: Size = yuvSizes?.filter { it.width <= 1280 && it.height <= 960 }
                ?.maxByOrNull { it.width.toLong() * it.height }
                ?: Size(640, 480)

            // maxImages 必须 >=3：连续流（setRepeatingRequest）下如果只有 2 块缓冲，
            // 生产者拿到第二帧时就会因为我们还没 release 而阻塞，帧率反而更差。
            // 每块 4096×3072×2B = 25MB，3 块 75MB，可接受。
            // 4 个缓冲：连拍最多 8 帧、帧间隔 33 ms，如果某一帧的读取被 GC 或
            // 别的线程抢占超过 100 ms，3 个缓冲就会开始丢帧（实测 8 帧时偶发）。
            // 多一个缓冲成本只有 24 MB 的虚拟地址，换来的是"帧数多了也不掉帧"。
            val raw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 4)
            val yuv = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2)
            rawReader = raw
            yuvReader = yuv

            raw.setOnImageAvailableListener({ reader -> onRawAvailable(reader) }, h)
            yuv.setOnImageAvailableListener({ reader ->
                val img = try {
                    reader.acquireNextImage()
                } catch (e: Exception) {
                    null
                } ?: return@setOnImageAvailableListener
                try {
                    // settle 阶段（快门还没按下）持续统计场景高光，用来决定欠曝倍数。
                    // 这一步对应论文 §3 的 AE 数据库：那里是查 5000 张标注图，
                    // 这里退化成"看这张图有多少像素已经贴到 ISP 的白点"——拿到的是同样
                    // 一个决策所需的信息（这张场景到底需不需要高光余量）。
                    if (!captureFired) {
                        val th = System.currentTimeMillis()
                        val st = yuvHighlightStats(img)
                        val hp = System.currentTimeMillis() - th
                        setuvAccum += hp; setuvCount++
                        if (st != null) {
                            sceneClip = st[0]
                            sceneP995 = st[1]
                        }
                    }
                    // 拍照那一帧的 YUV 留下来做色卡识别：它是 ISP 处理过的亮度，
                    // 对比度正常；而且和 RAW 同方向、同视野，坐标只需缩放。
                    // 只在单帧 RAW 路径需要——HDR 走的是连拍，不需要这份灰度
                    // （以前 HDR 的连拍请求里没有 YUV，所以这段本来就不会执行；
                    //   现在连拍请求保留了 YUV 以免重配管线，必须显式排除掉）。
                    if (captureFired && !hdrMode && stillLuma == null) {
                        stillLuma = yuvToLumaBitmap(img)
                        runRawCallback(force = true)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "YUV 转灰度失败: ${e.message}")
                } finally {
                    try {
                        img.close()
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }, h)
        }
        val raw = rawReader ?: return
        val yuv = yuvReader ?: return

        // 常驻模式下三路一起配：预览(给 TextureView) + RAW + YUV。
        // 三路同时配置比两路贵，但只付一次——换来的是按快门时不用重配。
        val surfaces = ArrayList<android.view.Surface>(3)
        surfaces.add(raw.surface)
        surfaces.add(yuv.surface)
        previewSurface?.let { surfaces.add(it) }

        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        sessionCallback = this
                        rebuilding = false
                        rebuildTry = 0          // 成功即清零
                        Log.d(TAG, "预分段：建会话 ${System.currentTimeMillis() - openedAt}ms")
                        startPreviewAndCapture(settleMs)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        rebuilding = false
                        finish(null, "RAW 会话创建失败")
                    }

                    /**
                     * 旧会话**真正关闭**之后才回调这里。
                     *
                     * 上一轮的错误就是在 close() 之后立刻 createCaptureSession()：
                     * close() 只是"发起关闭"，此时旧 buffer 还在 HAL 手里，
                     * 新会话又要拿同一批 surface，于是新会话只出一帧就死、随后抛异常。
                     * 必须在 onClosed 之后才建新会话。
                     */
                    override fun onClosed(s: CameraCaptureSession) {
                        Log.d(TAG, "PSHOT 旧会话已关闭（onClosed），待重建=$rebuilding")
                        if (!rebuilding) return
                        rebuilding = false
                        // 【实验】连拍之后把 ImageReader 也重建。
                        //
                        // 前面几轮验证过：换 session 无效、放掉 hdrBaseImage 无效。
                        // 唯一没动过的就是 reader 本身——怀疑连拍（AE off + 长曝光）
                        // 之后 HAL 把这条 RAW 输流的缓冲队列置成了不可恢复的状态。
                        // 顺序必须是：会话已关闭 -> 关 reader -> 重建。
                        if (persistent && REBUILD_READERS_ON_END) {
                            try { hdrBaseImage?.close() } catch (e: Exception) { }
                            hdrBaseImage = null
                            try { rawReader?.close() } catch (e: Exception) { }
                            rawReader = null
                            try { yuvReader?.close() } catch (e: Exception) { }
                            yuvReader = null
                            Log.d(TAG, "PSHOT 已丢弃旧 ImageReader，随会话一起重建")
                        }
                        if (persistent && device != null && handler != null) {
                            try {
                                createSession(0L)
                            } catch (e: Throwable) {
                                // 重建再失败就停手，避免 onClosed -> createSession 无限递归
                                Log.e(TAG, "重建会话失败（第 $rebuildTry 次）", e)
                                rebuilding = false
                            }
                        }
                    }
                },
                h
            )
        } catch (e: Exception) {
            finish(null, "RAW 会话创建异常: ${e.message}")
        }
    }

    /**
     * 起 3A 并等 AE 收敛，然后拍 RAW。
     *
     * 以前这里是死等 1.2 秒（settleMs），不管 AE 早就算好了没有。
     * 现在改成：主动打一发 AE precapture，然后盯着 CaptureResult 里的 CONTROL_AE_STATE，
     * 一旦 CONVERGED 就立刻拍，通常 300ms 左右就够；只有特别暗、一直不收敛的场景才等到超时。
     */
    private fun startPreviewAndCapture(settleMs: Long) {
        // 常驻模式：这里不拍，只是把预览跑起来并持续记录已收敛的 3A 结果
        if (persistent) {
            startPersistentPreview()
            return
        }
        val camera = device ?: return
        val s = session ?: return
        val yuv = yuvReader ?: return
        val h = handler ?: return

        startedAt = System.currentTimeMillis()
        captureFired = false
        settleFrames = 0

        // 如果 CameraX 预览刚给出过"已收敛"的曝光，就直接用它，不再等收敛。
        // 这一段把 683ms 的白等压到几十毫秒，见 PrewarmState 的注释。
        val pre = PrewarmState.take()
        prewarm = pre
        if (pre != null) {
            Log.d(TAG, "预置曝光：${pre.exposureNs / 1000}us ISO ${pre.iso} "
                    + "焦距 ${pre.focusDiopters?.let { "%.3f".format(it) } ?: "不锁"}"
                    + "（来自预览，${System.currentTimeMillis() - pre.atMs}ms 前）")
        }

        try {
            val preview = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(yuv.surface)
                // **不再把 RAW 挂进预览请求**。
                // 之前挂上是为了避免"目标集合变化导致管线重配"，但实测发现：
                //   · 挂上后 settle 阶段首个 repeating request 要 580ms 才出第一帧
                //     （12MP RAW + 12MP YUV 两路同时启动，HAL 要分配/预取大量缓冲）
                //   · 而所谓"重配 1.5 秒"其实是 ImageReader 背压，读取改成批量拷之后
                //     这个代价已经消失
                // 所以现在走"轻预览（纯 YUV）+ 连拍时切换"：预览启动快，切换也不贵。
                // 用 EXP_RAW_IN_PREVIEW 可以切回去做对照实验。
                if (EXP_RAW_IN_PREVIEW) rawReader?.surface?.let { addTarget(it) }
                if (pre != null) {
                    // 预览已经确认过的**曝光**，直接锁死：AE 关掉后就没有"收敛"这回事，
                    // 第一帧曝光就是对的。
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, pre.exposureNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, pre.iso)
                }
                // **焦距绝不从预置里锁。**
                //
                // 实测踩到的坑：预置拿到的 LENS_FOCUS_DISTANCE 是 0.005 屈光度（无限远），
                // 而实际被摄物在 1.126（约 0.9 米）——差了 100 倍。一旦锁死，整段连拍全虚。
                // 更麻烦的是它会造成死循环：
                //   AF_MODE_OFF -> 结果里 AF_STATE 恒为 0(INACTIVE)
                //   -> hdrCanLockFocus 恒为 false
                //   -> 连拍只能退回 CONTINUOUS_PICTURE
                //   -> 4 帧连拍里 AF 来不及收敛 -> 出软片
                // 所以这里改成**让 AF 在预览阶段一直跑**，等 settle 判定 AF 真的对上
                // （PASSIVE_FOCUSED/FOCUSED_LOCKED），再由 fireHdrBracket 锁那个距离。
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                // 让每一帧的结果里都带上暗角标定表，拍照时直接取最新的一份
                if (shadingSupported) {
                    set(
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        android.hardware.camera2.CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON
                    )
                }
            }.build()

            s.setRepeatingRequest(preview, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    lastResult = result
                    if (captureFired) return
                    if (System.currentTimeMillis() - startedAt < MIN_SETTLE_MS) return

                    // 有预置曝光时不需要等 AE 收敛（AE 已经是 OFF），
                    // 但仍要等 AWB 收住（DNG 的 AsShotNeutral / COLOR_CORRECTION_GAINS
                    // 取自这一帧的结果），**而且仍然必须等 AF 真的对上**。
                    //
                    // 这里原来只等固定帧数就开拍，等于完全跳过了对焦判断：
                    // 实测 AF_STATE 还是 0(INACTIVE) 就已经在拍了，4 帧连拍里 AF
                    // 根本没开始工作 -> 整幅虚。预置能省的只有 AE，不能省 AF。
                    val pre0 = prewarm
                    if (pre0 != null) {
                        settleFrames++
                        val afNow = result.get(CaptureResult.CONTROL_AF_STATE)
                        val afOkNow = afNow == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                                afNow == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        if ((afOkNow && settleFrames >= PREWARM_SETTLE_FRAMES) ||
                                settleFrames >= PREWARM_MAX_FRAMES) {
                            Log.d(TAG, "预置分支开拍：AF_STATE=$afNow 已等 $settleFrames 帧")
                            fireRawCapture()
                        }
                        return
                    }

                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    val aeReady = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED

                    // **对焦也必须等到收敛**。这里原来只看 AE，而 AE 和 AF 是两条独立的
                    // 收敛回路：实测拍远景时 AE 早就 CONVERGED，AF 还停在 PASSIVE_SCAN(1)，
                    // 报上来的距离是 0.53 屈光度 ≈ 1.9 米——拍几公里外的天际线，整幅全是虚的。
                    // PASSIVE_UNFOCUSED(5) 表示场景没纹理、AF 放弃了，这时再等也没用。
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    // **不再把 PASSIVE_UNFOCUSED 当成就绪。**
                    // 它的含义是"场景没纹理、AF 主动放弃"，此时镜头停在哪就是哪，
                    // 拍出来大概率是虚的。原来的写法等于"AF 放弃了我们就拍"，
                    // 这正是用户反馈的"经常对不上焦"。
                    // 真正的无纹理场景仍然有 2.5 秒兜底（见 fireRawCapture 的超时路径），
                    // 只是不再"一放弃就立刻拍"。
                    val afReady = afState == null ||
                            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                            afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED

                    if (aeReady && afReady) fireRawCapture()
                }
            }, h)

            // 主动触发一次预拍，别让 AE 慢慢自己漂到位
            val precapture = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(yuv.surface)
                set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
                )
                // 同时踢一脚 AF：CONTINUOUS_PICTURE 在逆光/远景下会一直 PASSIVE_SCAN，
                // 不主动触发的话等 2.5 秒也可能没对上
                set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_START
                )
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build()
            h.postDelayed({
                try {
                    s.capture(precapture, null, null)
                } catch (e: Exception) {
                    Log.w(TAG, "precapture 触发失败: ${e.message}")
                }
            }, 80)

            // 兜底：环境太暗 AE 一直不收敛，也不能让用户一直等
            h.postDelayed({ fireRawCapture() }, settleMs)
        } catch (e: Exception) {
            finish(null, "RAW 预览启动失败: ${e.message}")
        }
    }

    /**
     * HDR 曝光包围：连拍 3 帧（EV −2 / 0 / +2）。
     *
     * 为什么直接改 SENSOR_EXPOSURE_TIME 而不是用 AE 补偿（CONTROL_AE_EXPOSURE_COMPENSATION）：
     * AE 补偿每改一次都要等 AE 重新收敛，三帧会拖到一秒以上，手持必糊。
     * 关掉 AE 手动给曝光时间，改完立即生效，三帧之间通常只有几十毫秒。
     * ISO 三帧保持一致，只动曝光时间——否则合并时噪声特性不同，暗部会出现色块。
     */
    /**
     * [stopBurst] 默认 true：打开相机时要先确保没有残留的连拍流。
     * 但**每次快门单独 arm 时必须传 false**——否则会在连拍刚要开始的那一刻
     * 调用 stopHdrBurst()，把 burstRepeating 清掉、连 stopRepeating，
     * 结果就是一帧都收不到（这个副作用害我查了很久）。
     */
    private fun armWatchdog(stopBurst: Boolean = true) {
        val h = handler ?: return
        disarmWatchdog()
        // 连续流必须停掉，否则超时/失败之后相机还在一直出 RAW 帧
        if (stopBurst) stopHdrBurst()
        val r = Runnable {
            Log.w(TAG, "RAW 采集超时，强制收尾")
            finish(null, "采集超时：相机没有返回完整数据")
        }
        watchdog = r
        h.postDelayed(r, WATCHDOG_MS)
    }

    private fun disarmWatchdog() {
        val r = watchdog ?: return
        try {
            handler?.removeCallbacks(r)
        } catch (e: Exception) {
            // ignore
        }
        watchdog = null
    }

    /**
     * 串行发一帧。
     *
     * 为什么不能一次性排队：RAW 是重量级流，一次把 5 个 STILL_CAPTURE 请求塞进去，
     * 相机会**丢掉其中几个**（尤其是连拍请求和 YUV 抢占时）。原来就是这么写的，
     * 而收帧那边写的是"等满 5 帧才继续"，于是一少帧就永远卡在那里。
     * 现在改成收到第 i 帧再发第 i+1 帧，天然不会丢，代价只是总耗时多几百毫秒。
     */
    /**
     * 连续流出图：一个 repeating request 连拍 N 帧。
     *
     * 曝光/ISO/焦距全部固定（AE 关、AF 关），所以每一帧的成像条件完全一致，
     * 合并时不需要做曝光归一化，也不会因为 AWB 漂移导致帧间色偏。
     */
    private fun startHdrBurst() {
        val camera = device ?: return
        val s = session ?: return
        val raw = rawReader ?: return
        val h = handler ?: return
        val t = hdrExposures.firstOrNull()
        if (t == null) {
            Log.e(TAG, "PSHOT startHdrBurst 没有曝光序列（hdrExposures 为空），放弃")
            return
        }
        Log.d(TAG, "PSHOT startHdrBurst：目标 ${burstN} 帧，曝光 ${t}ns ISO $hdrIso "
                + "预览surface=${if (previewSurface != null) "有" else "无"}")
        burstStamps.clear()
        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(raw.surface)
            // 目标集合必须和预览请求**完全一致**，否则 HAL 要重配整条管线。
            // 常驻模式下预览请求是三路（预览+RAW+YUV），这里也必须是三路——
            // 漏掉预览 surface 会让集合从 3 变 2，实测连拍只出 1 帧就卡住。
            previewSurface?.let { addTarget(it) }
            yuvReader?.surface?.let { addTarget(it) }
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, t)
            set(CaptureRequest.SENSOR_SENSITIVITY, hdrIso)
            val fd = hdrFocusDistance
            if (hdrCanLockFocus && fd != null && fd > 0f) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, fd)
            } else {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        }.build()
        // 先置标志再发请求：第一帧可能比 setRepeatingRequest 的返回还早到
        burstRepeating = true
        val now = System.currentTimeMillis()
        Log.d(TAG, "预分段：AE/AF 收敛 ${now - openedAt}ms；"
                + "YUV 高光统计 ${setuvCount} 次共 ${setuvAccum}ms"
                + "（单次 ${if (setuvCount > 0) "%.1f".format(setuvAccum.toDouble() / setuvCount) else "-"}ms）")
        setuvAccum = 0; setuvCount = 0
        Log.d(TAG, "连拍前 settle 阶段收到 $settleRawFrames 帧 RAW" +
                "（最近一帧 ${if (settleRawLastAt > 0) now - settleRawLastAt else -1}ms 前）"
                + " → 预览管线帧率约 ${if (settleRawLastAt > 0 && settleRawFrames > 1) "%.1f".format(1000.0 * settleRawFrames / (now - startedAt)) else "?"} fps")
        settleRawFrames = 0
        settleRawLastAt = 0L
        try {
            s.setRepeatingRequest(req, null, h)
            Log.d(TAG, "PSHOT 连拍请求已下发成功（session=${s.hashCode()}）")
        } catch (e: Exception) {
            Log.e(TAG, "PSHOT 连拍请求下发失败", e)
            finish(null, "连拍请求失败: ${e.message}")
            return
        }
        Log.d(TAG, "HDR 连续流连拍开始（目标 ${burstN} 帧）")
    }

    /**
     * 结束连拍。
     *
     * **常驻模式下绝不调用 `stopRepeating()`。**
     * 实测：一旦 stopRepeating()，哪怕随后 setRepeatingRequest() 返回成功，
     * 这条会话也不会再出任何 RAW 帧——表现为第 2 次快门一帧都收不到、
     * 20 秒后超时。而 setRepeatingRequest 本身就会**替换**当前 repeating request，
     * 根本不需要先停。所以这里只清标志，靠 endShot 里下发预览请求来替换。
     */
    private fun stopHdrBurst() {
        if (!burstRepeating) return
        burstRepeating = false
        if (persistent) return
        try {
            session?.stopRepeating()
        } catch (e: Exception) {
            Log.w(TAG, "stopRepeating 失败: ${e.message}")
        }
    }

    private fun fireHdrFrame(i: Int) {
        val camera = device ?: return
        val s = session ?: return
        val raw = rawReader ?: return
        val h = handler ?: return
        if (i >= burstN) return
        val t = hdrExposures.getOrNull(i) ?: return
        try {
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(raw.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, t)
                set(CaptureRequest.SENSOR_SENSITIVITY, hdrIso)
                val fd = hdrFocusDistance
                if (hdrCanLockFocus && fd != null && fd > 0f) {
                    // 锁焦：整段连拍用同一个对焦距离
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, fd)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
            }.build()
            s.capture(req, null, h)
        } catch (e: Exception) {
            Log.e(TAG, "HDR 第 $i 帧触发失败", e)
            finish(null, "HDR 拍摄失败: ${e.message}")
        }
    }

    private fun fireHdrBracket() {
        val camera = device ?: return
        val s = session ?: return
        val raw = rawReader ?: return
        val h = handler ?: return
        val base = lastResult
        if (base == null) {
            finish(null, "HDR 缺少 AE 结果")
            return
        }
        val t0 = base.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = base.get(CaptureResult.SENSOR_SENSITIVITY)
        if (t0 == null || iso == null || t0 <= 0L) {
            finish(null, "HDR 读不到曝光参数")
            return
        }
        val ch = characteristics
        hdrWhite = ch?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        hdrBlack = try {
            ch?.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.getOffsetForIndex(0, 0) ?: 64
        } catch (e: Exception) {
            64
        }
        hdrResult = base
        hdrFrames.clear()
        hdrExposures.clear()

        hdrIso = iso
        hdrFocusDistance = try {
            base.get(CaptureResult.LENS_FOCUS_DISTANCE)
        } catch (e: Exception) {
            null
        }
        // **只有 AF 真的对上时才锁焦**。
        // AE 收敛 != AF 收敛：settle 阶段我们等的是 AE，AF 可能还在扫。
        // 一旦在 AF 没对上的时刻把 AF_MODE_OFF + 距离锁死，整段 5 帧就全是虚的，
        // 而且连写进 DNG 的第 0 帧也一样虚——用户看到的就是"整幅失焦"。
        val afState = try {
            base.get(CaptureResult.CONTROL_AF_STATE)
        } catch (e: Exception) {
            null
        }
        hdrCanLockFocus = hdrFocusDistance != null && hdrFocusDistance!! > 0f &&
                (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED)
        Log.d(TAG, "对焦：AF_STATE=$afState 距离=${hdrFocusDistance} 可否锁焦=${hdrCanLockFocus}")
        // 欠曝 K：0 = 按场景自动，1..4 = 用户指定。
        // 自动的分界见 chooseUnderexpose 的注释。
        val uexp = RawSettings.hdrUnderexpose
        hdrDrcK = if (uexp > 0) uexp.toFloat()
                else chooseUnderexpose(sceneClip, sceneP995).toFloat()
        Log.d(TAG, "欠曝倍数：%s -> K=%.0f×（贴顶 %.3f%% P99.5=%.0f）".format(
            if (uexp > 0) "手动 $uexp" else "自动",
            hdrDrcK, sceneClip * 100f, sceneP995
        ))
        // 欠曝：整段连拍都在 AE 的基础上除以 K。
        // 这一步是论文 §3 的核心——不是"拍完再压高光"，而是**采集时就不让它爆**。
        val tFrame = (t0 / hdrDrcK).toLong().coerceIn(1_000L, 4_000_000_000L)
        burstN = chooseBurstCount(iso, tFrame)
        Log.d(TAG, "帧数：%d（ISO %d，单帧 %.1fms，预算 900ms）".format(
            burstN, iso, tFrame / 1e6
        ))
        for (i in 0 until burstN) {
            // 等曝光：BURST_EV 全 0，保留这个数组是为了将来支持"亮度包围"
            val ev = BURST_EV.getOrElse(i) { 0.0 }
            val t = (t0 * Math.pow(2.0, ev) / hdrDrcK).toLong()
                .coerceIn(1_000L, 4_000_000_000L)
            hdrExposures.add(t)
        }
        // 优先用连续流（帧间隔 ~1/传感器帧率）；不支持就退回逐帧请求。
        try {
            startHdrBurst()
        } catch (e: Exception) {
            Log.w(TAG, "连续流不可用，退回逐帧请求: ${e.message}")
            burstRepeating = false
            fireHdrFrame(0)
        }
        Log.d(TAG, "HDR 串行连拍开始：AE ${t0}ns / 欠曝 ${hdrDrcK}× = ${hdrExposures.firstOrNull()}ns ISO $iso")
    }

    /** 把 ImageReader 的 Image 读成裸 16bit 数组（和 RawToBitmap.readRaw16 同构） */
    /**
     * RAW 平面 -> ShortArray。
     *
     * ---- 为什么这么写 ----
     * 原实现是逐像素 `rowBuf.getShort(x * pixelStride)`，4096×3072 = 1260 万次
     * JVM 方法调用 + 每次一个边界检查。实测**每帧约 200ms**，8 帧就是 1.6 秒，
     * 而这只是"把数据从 HAL 的缓冲区拷到 Java 数组"，一次 memcpy 就能做完。
     *
     * 现在两条快路径：
     *   1) pixelStride=2 且 rowStride=2w（绝大多数设备的 RAW10/RAW12 打包方式）
     *      → `asShortBuffer().get(out)` 一次整块拷，走 native bulk copy；
     *   2) 行有 padding → 每行一次 bulk copy（h 次，仍然比 12M 次快两个数量级）。
     * 只有 pixelStride≠2（像素跨 4 字节之类的怪布局）才退回逐像素老路。
     */
    private fun readRawShort(image: Image): ShortArray? {
        return try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val w = image.width
            val h = image.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val out = ShortArray(w * h)

            if (pixelStride == 2) {
                buf.order(java.nio.ByteOrder.nativeOrder())
                if (rowStride == 2 * w && buf.remaining() >= 2 * w * h) {
                    buf.position(0)
                    buf.asShortBuffer().get(out)
                    return out
                }
                // 行内连续、行间有 padding
                val sb = buf.asShortBuffer()
                val rowShorts = rowStride / 2
                for (y in 0 until h) {
                    sb.position(y * rowShorts)
                    if (sb.remaining() < w) break
                    sb.get(out, y * w, w)
                }
                return out
            }

            // 兜底：逐像素（只在 pixelStride 不是 2 的设备上走到）
            val row = ByteArray(rowStride)
            val rowBuf = java.nio.ByteBuffer.wrap(row).order(java.nio.ByteOrder.nativeOrder())
            for (y in 0 until h) {
                buf.position(y * rowStride)
                val n = minOf(rowStride, buf.remaining())
                buf.get(row, 0, n)
                val baseIdx = y * w
                for (x in 0 until w) {
                    out[baseIdx + x] = rowBuf.getShort(x * pixelStride)
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "读取 HDR 帧失败", e)
            null
        }
    }

    /**
     * RAW 域合并（带帧间对齐）。
     *
     * 关键 1：**先对齐再合并**。没对齐时手抖会让边缘双影，多帧平均反而更糊。
     * 关键 2：从**最亮**帧开始、饱和了才退到更暗的帧——暗部才能用上信噪比最好的曝光。
     * 关键 3：**以 2×2 Bayer 单元为最小单位决定用哪一帧**，不能逐像素判断。
     *
     * 为什么必须按单元：R、G、G、B 是四个不同位置，各自饱和的时刻不同。最亮帧里 R 通常
     * 先到顶、G 还留有余量；如果逐像素判断，同一单元内 R 取中间帧、G 取最亮帧，
     * G 就被相对抬高——整个画面发绿、R/B 被压低偏青。
     *
     * 对齐的位移恒为偶数像素，所以 4 个相位不会被拆散，颜色关系原样保留。
     * 每帧还有一份逐拼块置信度（对齐后仍对不上的地方 = 物体在动），
     * 用权重压下去，避免移动物体留下半透明残影。
     */
    /**
     * RAW 域多帧合并。**只走 native**（HDR+ §4 对齐 + §5 频域 Wiener，见 hdr_merge.cpp）。
     *
     * 旧的 Kotlin 逐相位加权合并在 2026-11-08 已整体删除：
     * 它和论文的做法在"权重怎么分配"上是两种东西（空域选帧 vs 频域 Wiener 收缩），
     * 留着当退路只会让排查问题时把两套结果混在一起。
     * native 不可用时宁可不做 HDR（退回单帧），也不拿旧算法糊一张。
     */
    private fun mergeHdr(frames: List<ShortArray>, w: Int, h: Int): ShortArray? {
        if (!NativeHdr.available) {
            Log.w(TAG, "native 管线不可用，HDR 合并跳过（退回单帧）")
            return null
        }
        val t0 = System.currentTimeMillis()
        val out = ShortArray(w * h)
        NativeHdr.nativeMerge(
            frames.toTypedArray(), w, h, hdrExposures.toLongArray(),
            REF_INDEX, hdrBlack, hdrWhite, out
        )
        Log.d(TAG, "native 合并 ${System.currentTimeMillis() - t0}ms")
        return out
    }

    private fun fireRawCapture() {
        Log.d(TAG, "PSHOT fireRawCapture：captureFired=$captureFired hdrMode=$hdrMode")
        if (captureFired) {
            Log.w(TAG, "PSHOT fireRawCapture 被 captureFired 挡住")
            return
        }
        captureFired = true

        if (hdrMode) {
            fireHdrBracket()
            return
        }

        val camera = device ?: return
        val s = session ?: return
        val raw = rawReader ?: return
        val h = handler ?: return

        try {
            val still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(raw.surface)
                // 同一帧再要一路 YUV，给色卡识别用（几何与 RAW 完全一致）
                yuvReader?.surface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                // 把刚才收敛到的曝光锁死，RAW 才不会跟预览跑偏
                set(CaptureRequest.CONTROL_AE_LOCK, true)
                // 白平衡同样要锁。连拍 5 帧期间 AWB 若继续收敛，会出现两个后果：
                //   · 5 帧的 AWB 各不相同，而我们只能拿**一帧**的 AWB 去乘全部帧——
                //     实测同一场景相邻两次拍摄，DNG 里记的 R 增益从 1.482 跳到 1.775；
                //   · JPG 用的那一帧和写进 DNG 的那一帧不是同一帧，两者能差 6.6%
                //     → 用户打开 DNG 觉得颜色对，看 JPG 却"暗部发红"。
                // Google HDR+ 的做法就是整个连拍固定曝光与白平衡。
                set(CaptureRequest.CONTROL_AWB_LOCK, true)
                // 索取这台机器实测的暗角标定表，用来在 RAW 管线里补偿边缘衰减
                if (shadingSupported) {
                    set(
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        android.hardware.camera2.CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON
                    )
                }
            }.build()
            s.capture(still, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    // 这一帧才是真正拍照的结果，里面的 shading map 和 RAW 数据是同一时刻的
                    lastResult = result
                }
            }, h)
        } catch (e: Exception) {
            finish(null, "RAW 拍照失败: ${e.message}")
        }
    }

    /** 这台设备支不支持上报 lens shading map */
    private val shadingSupported: Boolean by lazy {
        val modes = characteristics?.get(
            CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES
        )
        modes?.contains(
            android.hardware.camera2.CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON
        ) == true
    }

    @Volatile
    private var lastResult: TotalCaptureResult? = null

    /** HDR 连拍用的 ISO（整段固定） */
    private var hdrIso = 0

    /**
     * 是否用"连续流"方式连拍。
     *
     * 以前是每帧单独发一个 STILL_CAPTURE 请求（收到上一帧才发下一帧）。实测帧间隔
     * **470ms**，而曝光只有 5ms、读出只有 33ms——多出来的 430ms 是 HAL 每次
     * still-capture 都要重配置一次管线。2.4 秒的连拍里手持漂移了 95 像素
     * （日志里位移场均值 43→66→74→95 单调递增），tile 对齐追不上，出来就是虚影。
     *
     * 改成一个 setRepeatingRequest 持续出图：管线不重配，帧间隔由传感器帧率决定。
     * 收满帧数后 stopRepeating。
     */
    private var burstRepeating = false

    // ---------------- 常驻会话（"相机常开"）----------------
    //
    // 原来的流程每次按快门都要：让出 CameraX -> 打开设备(235ms) -> 建会话(168ms)
    // -> 等首帧(~500ms) -> 拍 -> 关相机 -> 交还 CameraX。这 ~0.9s 是纯开销。
    //
    // 常驻模式下：进相机界面就打开一次，会话里同时挂 **预览 + RAW + YUV**，
    // 按快门只是把同一个 repeating request 的曝光从"自动"换成"固定"，
    // 目标 surface 集合不变，HAL 不需要重配，实测切换只要 30ms。
    private var persistent = false
    private var previewSurface: android.view.Surface? = null
    /** 当前会话的 StateCallback（用来等 onClosed） */
    private var sessionCallback: CameraCaptureSession.StateCallback? = null
    /** 是否正在等旧会话关闭后重建 */
    private var rebuilding = false
    /** 本轮重建尝试次数。重建失败若再次触发重建，会 onClosed -> createSession 无限递归，
     *  实测直接 StackOverflowError。用一个上限断环。 */
    private var rebuildTry = 0
    /** 常驻模式下每次拍摄的回调（每次可不同） */
    private var shotCallback: Callback? = null

    /** 本次拍摄是否用了预览预置的曝光（null = 走原来的"等 AE/AF 收敛"路径） */
    private var prewarm: PrewarmState.Snapshot? = null
    private var settleFrames = 0
    /** RAW 帧到达计数，只用于诊断 */
    private var rawTotal = 0
    /** 已 acquire 但还没 close 的 RAW Image 数量（诊断用，正常应恒为 0 或 1） */
    private var rawHeld = 0
    private var rawNullAcq = 0

    /** 统一的 close：维护 rawHeld 计数，避免漏关时看不出来 */
    private fun closeRaw(img: android.media.Image?) {
        if (img == null) return
        try {
            img.close()
        } catch (e: Exception) {
            // ignore
        }
        rawHeld--
    }
    private var setuvAccum = 0L
    private var setuvCount = 0

    /** settle 阶段（还没开始连拍）收到的 RAW 帧数，用于判断预览管线的实际帧率 */
    private var settleRawFrames = 0
    private var settleRawLastAt = 0L

    /** 连拍各帧的传感器时间戳（ns），用来量真实帧间隔 */
    private val burstStamps = ArrayList<Long>()

    /**
     * HDR 连拍用的对焦距离（整段固定）。
     *
     * 串行连拍横跨 3 秒多，如果每帧都走 CONTINUOUS_PICTURE，AF 会在中途重新搜索，
     * 5 帧的焦点就不一样了——这种差异**对齐算法救不回来**，直接变成糊。
     * 收敛阶段已经对好焦，这里把那个距离锁死，整段不再动。
     */
    private var hdrFocusDistance: Float? = null

    /** AF 是否真的收敛了（只有收敛才敢锁焦，见 fireHdrBracket） */
    private var hdrCanLockFocus = false

    /**
     * 看门狗：整条采集流程的兜底超时。
     *
     * 没有它的话，只要任何一个回调没来（相机丢帧、session 被静默拆掉、厂商 ROM 吞了
     * onCaptureFailed），[finish] 就永远不会被调用，上层的 rawBusy 就一直为 true，
     * 之后每一次快门都被 `if (rawBusy) return` 挡掉——表现就是"卡住"，只能杀进程。
     */
    private var watchdog: Runnable? = null

    /** 拍照那一帧的 YUV 亮度图（半分辨率，仅用于色卡识别） */
    @Volatile
    private var stillLuma: Bitmap? = null

    private var pendingRaw: Image? = null
    private var pendingCh: CameraCharacteristics? = null
    private var pendingResult: TotalCaptureResult? = null

    /**
     * Y 平面直方图 -> [贴顶占比, P99.5 分位]。
     *
     * 步长 3：1280×960 只取 14 万个点，几十微秒；对"这张图有多少高光"这种
     * 统计量完全够用（误差 < 1%）。YUV 的 Y 是 ISP 处理过的，通常是
     * limited range 16..235，所以白点取 235 而不是 255。
     *
     * @return null 表示合格样本太少（刚打开相机的黑帧之类），调用方应保持旧值
     */
    private fun yuvHighlightStats(image: Image): FloatArray? {
        return try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = image.width
            val h = image.height
            if (w < 32 || h < 32) return null
            val hist = IntArray(256)
            var total = 0
            val bytes = ByteArray(rowStride)
            var y = 0
            while (y < h) {
                buf.position(y * rowStride)
                val n = minOf(rowStride, buf.remaining())
                if (n <= 0) break
                buf.get(bytes, 0, n)
                var x = 0
                while (x < w) {
                    val idx = x * pixelStride
                    if (idx < n) hist[bytes[idx].toInt() and 0xFF]++
                    total++
                    x += 3
                }
                y += 3
            }
            if (total < 2000) return null
            var clip = 0L
            for (v in 235..255) clip += hist[v]
            var acc = 0L
            var p995 = 255
            val target = (total * 0.995).toLong()
            for (v in 0..255) {
                acc += hist[v]
                if (acc >= target) { p995 = v; break }
            }
            floatArrayOf(clip.toFloat() / total, p995.toFloat())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 场景高光 -> 欠曝倍数 K。
     *
     * 论文 §3 的 DRC 上限是 8（3 档），但实测 96% 的场景 ≤8、多数只要 1~2。
     * 我们保守到 4，因为连拍只有 5 帧、补不回更多暗部。
     *
     * ---- 为什么只看"贴顶比例"，不看分位数 ----
     * 直觉上"画面越亮越要欠曝"是错的。AE 收敛之后，亮场景（白墙、雪地、阴天）
     * 的曝光已经被 ISP 压下来了，贴顶比例照样接近 0——它们根本不需要高光余量。
     * 需要余量的只有一种情况：**同一幅画面里既有大面积暗部、又有局部亮点**
     * （窗、灯、天空），AE 为了保住暗部只能让亮点爆掉。这时贴顶比例才会明显 > 0。
     *
     * 分位数（P99.5）对这件事没有区分力：白墙的 P99.5 也是 200+，
     * 按它判定会把最不需要欠曝的场景判成最需要。所以只留作日志。
     *
     *   贴顶 < 0.02%  → 1（没有高光，欠曝纯亏暗部）
     *   贴顶 < 0.5%   → 2（常规，等于原来的固定值）
     *   贴顶 < 3%     → 3（有窗、有灯）
     *   其余          → 4（逆光、正对窗外）
     */
    private fun chooseUnderexpose(clip: Float, p995: Float): Int {
        if (clip < 0f) return 2                     // 还没测到，退回原行为
        val byClip = when {
            clip < 0.0002f -> 1
            clip < 0.005f -> 2
            clip < 0.03f -> 3
            else -> 4
        }
        // ---- 亮度上限：这才是"过亮"的真正约束 ----
        //
        // 原来只看削顶比例。户外天空占比大 -> 削顶多 -> 直接给 K=4，
        // 于是整幅按 4 倍提亮，拍出来又灰又亮（实测 drcK=4.00 的户外样张）。
        //
        // K 的目的是"把欠曝的暗部提回来"，前提是**暗部确实欠曝**。
        // 如果场景本身已经中高调（P99.5 接近白点），那削顶的是天空/云，
        // 不是"暗部欠曝"，这时候再提 4 倍就是纯粹的过曝。
        // 所以用场景亮度再卡一道，取两者较小的。
        val byBrightness = when {
            p995 < 0f -> 4          // 没测到，不额外限制
            p995 < 180f -> 4        // 真暗场景，放心提
            p995 < 210f -> 3
            p995 < 232f -> 2
            else -> 1               // 已经很亮，不再提
        }
        Log.d(TAG, "欠曝 K：削顶 %.4f%% -> %d，P99.5 %.0f -> %d，取 %d"
                .format(clip * 100f, byClip, p995, byBrightness, minOf(byClip, byBrightness)))
        return minOf(byClip, byBrightness)
    }

    /**
     * 按场景决定连拍帧数（3~8）。
     *
     * 三条约束，取最严的一条：
     *
     * 1. **噪声需求**（按 ISO）：
     *    ISO ≤ 400    → 4 帧（噪点本来就小，再多是浪费内存）
     *    ISO ≤ 1600   → 6 帧
     *    ISO > 1600   → 8 帧（每多一帧噪声降 1/√n，5→8 相当于多 0.34 档）
     *
     * 2. **采集时间**：总时长不超过 0.9 秒。手持的抖动在 1 秒量级就开始累积，
     *    超过之后每帧都在漂，所有帧一起糊——那时多拍反而更差。
     *    这一条在极暗场景（单帧 > 150ms）会起作用，把帧数压到 6 以下。
     *
     * 3. **超分的需求**：SR 每个通道的有效样本数 ≈ 帧数 × 相位覆盖，
     *    帧数越多重建越干净。所以 SR 开启时对"帧数不足"更敏感，
     *    但内存也更紧张（8 帧 RAW = 192 MB，加上 SR 的三平面 75 MB 就到 267 MB），
     *    所以 SR 模式下上限压到 7。
     *
     * 注意 t 是**欠曝之后**的单帧曝光时间（已经除过 K）。
     */
    private fun chooseBurstCount(iso: Int, tNs: Long): Int {
        val byIso = when {
            iso <= 400 -> 4
            iso <= 1600 -> 6
            else -> 8
        }
        val budgetMs = 900.0
        val tMs = (tNs / 1e6).coerceAtLeast(1.0)
        val byTime = (budgetMs / tMs).toInt()
        val cap = if (RawSettings.superRes) 7 else 8
        return minOf(byIso, byTime, cap).coerceIn(3, cap)
    }

    /** YUV_420_888 的 Y 平面 -> 半分辨率灰度图 */
    private fun yuvToLumaBitmap(image: Image): Bitmap? {        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val outW = w / 2
        val outH = h / 2
        if (outW < 40 || outH < 40) return null
        val px = IntArray(outW * outH)
        val bytes = ByteArray(rowStride)
        for (y in 0 until outH) {
            buf.position(y * 2 * rowStride)
            val n = minOf(rowStride, buf.remaining())
            buf.get(bytes, 0, n)
            val row = y * outW
            for (x in 0 until outW) {
                val v = bytes[x * 2 * pixelStride].toInt() and 0xFF
                px[row + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, outW, 0, 0, outW, outH)
        return bmp
    }

    private fun onRawAvailable(reader: ImageReader) {
        var image: Image = try {
            val got = reader.acquireNextImage()
            if (got == null) {
                rawNullAcq++
                if (rawNullAcq <= 3 || rawNullAcq % 50 == 0) {
                    Log.w(TAG, "PSHOT acquireNextImage 返回 null（第 $rawNullAcq 次），"
                            + "rawHeld=$rawHeld")
                }
                return
            }
            rawHeld++
            got
        } catch (e: Exception) {
            finish(null, "读取 RAW 数据失败")
            return
        }

        // 预览请求里也挂了 raw.surface（为了让连拍时不必重配管线），
        // 所以 settle 阶段会不断收到 RAW 帧——这些帧只是管线的副产品，立刻释放。
        // 只有真正开始连拍（burstRepeating）之后的帧才收集。
        // stopRepeating 之后队列里可能还压着几帧，那时 burstRepeating 已经是 false，
        // 这个判断顺便把它们一起丢掉。
        rawTotal++
        if (rawTotal <= 3 || rawTotal % 20 == 0) {
            Log.d(TAG, "PSHOT RAW 帧到达 #$rawTotal burstRepeating=$burstRepeating "
                    + "hdrFrames=${hdrFrames.size} captureFired=$captureFired "
                    + "rawHeld=$rawHeld nullAcq=$rawNullAcq")
        }
        // **快门按下之前的 RAW 帧一律丢弃**，不分 HDR / 单帧。
        //
        // 预览请求里挂了 raw.surface（为了连拍时不重配管线），所以 settle 阶段
        // 会持续收到 RAW 帧。原来只挡了 HDR（`hdrMode && !burstRepeating`），
        // 单帧模式没挡——于是预览帧被当成拍摄帧去写 DNG，单帧 RAW 直接拍不出图
        // （用户反馈"关 hdr 拍不了"就是这个）。
        // 判据：按下快门之前 或 本次拍摄已经收尾之后的帧，都不是要的。
        // `finished` 那一半是必须的——finish() 之后相机还在关，队列里压着的帧会
        // 继续进来，被当成新一枪去重写 DNG、并对已经 close 的 Image 做转换
        // （实测报 "Image is already closed"）。
        if (!captureFired || finished) {
            if (captureFired && settleRawFrames == 0) {
                Log.w(TAG, "PSHOT 收到 RAW 帧但 burstRepeating=false，会被丢弃")
            }
            settleRawFrames++
            settleRawLastAt = System.currentTimeMillis()
            closeRaw(image)
            return
        }

        // 时间戳必须在 close() 之前读——Image 关掉之后 getTimestamp() 会抛
        // IllegalStateException（"Image is already closed"），这里踩过一次。
        val stamp = try {
            image.timestamp
        } catch (e: Exception) {
            0L
        }

        // HDR：连收 3 帧，中间那帧（EV 0）留着写 DNG，另外两帧只取数据后立刻释放
        if (hdrMode) {
            // 连续流在 stopRepeating 之后可能还有一两帧已经排在队列里。
            // 帧数够了就直接丢掉，否则会再走一遍合并（重复出图 / 重复 finish）。
            if (hdrFrames.size >= burstN || finished) {
                closeRaw(image)
                return
            }
            val tRead = System.currentTimeMillis()
            val raw = readRawShort(image)
            val readMs = System.currentTimeMillis() - tRead
            if (raw == null) {
                closeRaw(image)
                finish(null, "HDR 帧读取失败")
                return
            }
            hdrFrames.add(raw)
            if (hdrFrames.size - 1 == REF_INDEX) {
                hdrBaseImage = image
            } else {
                closeRaw(image)
            }
            burstStamps.add(stamp)
            val n = hdrFrames.size
            if (n >= 2) {
                val dt = (burstStamps[n - 1] - burstStamps[n - 2]) / 1_000_000.0
                Log.d(TAG, "HDR 收到第 $n/${burstN} 帧  间隔 ${"%.0f".format(dt)}ms  读取 ${readMs}ms")
            } else {
                Log.d(TAG, "HDR 收到第 $n/${burstN} 帧")
            }
            if (n < burstN) {
                // 连续流：帧自己会来，什么都不用做。
                // 逐帧退路：这一帧已到手，才发下一帧。
                if (!burstRepeating) fireHdrFrame(n)
                return
            }
            stopHdrBurst()
            if (burstStamps.size >= 2) {
                val total = (burstStamps.last() - burstStamps.first()) / 1_000_000.0
                Log.d(TAG, "HDR 连拍总跨度 ${"%.0f".format(total)}ms（${burstStamps.size} 帧）")
            }
            val baseImg = hdrBaseImage
            if (baseImg == null) {
                hdrFrames.clear()
                finish(null, "HDR 缺少基准帧")
                return
            }
            image = baseImg
        }

        val file = outputFile
        val ch = characteristics
        val result: TotalCaptureResult? = lastResult

        // 色卡标定：只把 RAW 交出去
        val onRaw = rawCallback
        if (onRaw != null) {
            if (ch == null || result == null) {
                closeRaw(image)
                finish(null, "缺少相机参数")
                return
            }
            pendingRaw = image
            pendingCh = ch
            pendingResult = result
            // YUV 通常同帧到达；万一没来，最多等 300ms 也不能卡住用户
            handler?.postDelayed({ runRawCallback(force = true) }, 300L)
            runRawCallback(force = false)
            return
        }

        if (file == null || ch == null || result == null) {
            closeRaw(image)
            finish(null, "缺少相机参数")
            return
        }

        try {
            FileOutputStream(file).use { out ->
                val creator = DngCreator(ch, result)
                val orientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                creator.setOrientation(
                    when (orientation) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90
                        180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270
                        else -> ExifInterface.ORIENTATION_NORMAL
                    }
                )
                creator.writeImage(out, image)
                creator.close()

                // DNG 写完后把它里面的色彩描述读出来存起来：
                // 厂商的 ForwardMatrix 比 ISP 那套 COLOR_CORRECTION_TRANSFORM 更接近
                // DNG 规范（也是 Photoshop 用的那套），出片用它颜色才对得上。
                //
                // **放到后台**：DNG 已经落盘了，解析 TIFF 标签（实测 ~220ms）没必要
                // 占着快门路径。下一次拍照才用到这份 profile，时间上完全来得及。
                val dngFile = file
                conversionExecutor.execute {
                    try {
                        DngProfile.read(dngFile)?.let { prof ->
                            DngProfile.save(context, prof)
                            Log.d(TAG, "已保存 DNG 色彩描述（${prof.source}）")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "提取 DNG 色彩描述失败: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "写 DNG 失败", e)
            closeRaw(image)
            finish(null, "写 DNG 失败: ${e.message}")
            return
        }

        // DNG 已经落盘，相机先还给 CameraX（用户不用盯着黑屏等解码）；
        // 自研管线解 JPEG 放到后台线程，解完再回调。
        val cb = callback
        val total = System.currentTimeMillis() - openedAt
        Log.d(TAG, "RAW 采集完成 ${total}ms（打开相机+3A收敛+写DNG）")
        finish(file, null, keepReader = true)
        // SR 开启时**不跑普通合并**：SR 用的是原始连拍，合并结果它一点也用不上，
        // 而那一步在 6 帧时要花 500 ms + 24 MB。只有 SR 失败时才需要合并结果兜底，
        // 那种情况在 convert 里退回单帧渲染（画面略脏但不会拍不出来）。
        //
        // 例外：开了 A/B（absr 1）时仍然合并一次，因为对比需要同一份数据的非 SR 版本。
        val wantSr = RawSettings.superRes && hdrMode && hdrFrames.size >= 2
        val needMerge = !wantSr || RawSettings.abSrTest
        val merged = if (hdrMode && needMerge && hdrFrames.size == burstN)
            mergeHdr(hdrFrames, image.width, image.height) else null
        // 超分要原始连拍（亚像素信息被合并平均掉就没了）。
        // 这里只复制引用，不复制数据；hdrFrames.clear() 之后数组仍然有效。
        val burst = if (wantSr) hdrFrames.toTypedArray() else null
        if (merged != null) Log.d(TAG, "HDR 合并完成，欠曝 ${hdrDrcK}× 的高光余量交给收尾阶段压回")
        hdrFrames.clear()
        // hdrScale = 欠曝倍数 K：合并数据按单帧满量程归一化，
        // 所以 1.0 对应"比正常曝光白亮 K 倍"的位置。渲染阶段靠这个值做 exposure fusion。
        startJpegConversion(image, ch, result, file, cb, reader, merged, hdrDrcK, burst)
    }

    /**
     * RAW -> JPEG 在后台线程做，不阻塞相机归还和 DNG 保存的提示。
     * Image 必须等解码结束才能 close，所以 ImageReader 也推迟到这里关。
     */
    private fun startJpegConversion(
        image: Image,
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        dng: File,
        cb: Callback?,
        reader: ImageReader,
        mergedRaw: ShortArray? = null,
        hdrScale: Float = 1f,
        burstFrames: Array<ShortArray>? = null
    ) {
        conversionExecutor.execute {
            var jpg: File? = null
            val convertStart = System.currentTimeMillis()
            try {
                val shading = OpticsCorrector.buildShading(characteristics, result)
                val userMatrix = RawCcmStore.load(context)?.matrix
                val deep = RawSettings.format == RawSettings.FORMAT_HEIF ||
                        RawSettings.format == RawSettings.FORMAT_PNG ||
                        // JPEG444 在 native 编码器可用时也走"直接落盘"这条路：
                        // 省掉那张只为旋转而建的 48MB Bitmap
                        (RawSettings.format == RawSettings.FORMAT_JPEG444 && NativeHdr.available)
                val file = File(
                    dng.parentFile,
                    dng.nameWithoutExtension + "." + ImageSaver.extension()
                )
                val bitmap = RawToBitmap.convert(
                    image, characteristics, result, shading, userMatrix,
                    DngProfile.load(context)?.matrix,
                    mergedRaw, hdrScale, burstFrames,
                    // HEIF / PNG16 / native-JPEG 都直接落盘，不返回 Bitmap
                    if (deep) file else null,
                    RawSettings.format
                )
                if (bitmap != null) {
                    val tBmp = System.currentTimeMillis()
                    // convert() 的返回值里已经包含 makeBitmap（IntArray -> Bitmap 的 48MB 拷贝），
                    // 所以这一段单独量，才分得清"拷贝"和"JPEG 压缩+写盘"各占多少
                    Log.d(TAG, "收尾到 Bitmap ${tBmp - convertStart}ms")
                    // 8bit 路径：ICC 与 EXIF 由 ImageSaver 一并处理
                    ImageSaver.save(bitmap, file, context)
                    Log.d(TAG, "编码+落盘 ${System.currentTimeMillis() - tBmp}ms"
                            + "（含 JPEG 压缩与写 ${if (file.exists()) file.length() / 1024 else 0}KB）")
                    bitmap.recycle()
                } else if (deep) {
                    // native 直接落盘的路径：只检查文件有没有写出来
                    // JPEG 走 native 编码器时，EXIF/ICC 得在这里补——ImageSaver 那条路
                    // 是在 save() 内部做的，而 native 只写纯码流。
                    if (RawSettings.format == RawSettings.FORMAT_JPEG444) {
                        JpegMeta.ensureSrgb(file, context)
                    }
                    Log.d(TAG, "native 直接落盘 ${System.currentTimeMillis() - convertStart}ms"
                            + "（${if (file.exists()) file.length() / 1024 else 0}KB）")
                }
                if (deep) {
                    jpg = if (file.exists() && file.length() > 0L) file else null
                } else if (bitmap != null) {
                    jpg = file
                }
            } catch (e: Exception) {
                Log.e(TAG, "RAW 转 JPEG 失败", e)
            } finally {
                try {
                    image.close()
                } catch (e: Exception) {
                    // ignore
                }
                try {
                    reader.close()
                } catch (e: Exception) {
                    // ignore
                }
            }

            val out = jpg
            Log.d(TAG, "RAW->JPG 解码完成 ${System.currentTimeMillis() - convertStart}ms")
            if (out != null && cb != null) {
                Handler(android.os.Looper.getMainLooper()).post { cb.onJpeg(out) }
            }
        }
    }

    /**
     * 等 RAW 和同帧 YUV 都到齐再执行标定。
     * 两个 ImageReader 共用同一个 handler，所以绝不能阻塞等待（会死锁），只能用事件驱动。
     */
    private fun runRawCallback(force: Boolean) {
        val img = pendingRaw ?: return
        val luma = stillLuma
        if (!force && luma == null) return

        val cb = rawCallback ?: return
        val ch = pendingCh ?: return
        val result = pendingResult ?: return

        pendingRaw = null
        rawCallback = null

        try {
            cb.onRaw(img, ch, result, luma)
        } catch (e: Exception) {
            Log.e(TAG, "RAW 标定处理失败", e)
        } finally {
            try {
                img.close()
            } catch (e: Exception) {
                // ignore
            }
            try {
                luma?.recycle()
            } catch (e: Exception) {
                // ignore
            }
            stillLuma = null
        }
        finish(null, null)
    }

    // ---------------- 常驻会话 API（"相机常开"）----------------

    /**
     * 打开常驻会话。[surface] 是 TextureView 的 Surface，用来当取景器。
     * 建好之后一直开着，直到 [stopPersistent]。
     */
    fun startPersistent(cameraId: String, surface: android.view.Surface, cb: Callback?) {
        callback = cb
        persistent = true
        previewSurface = surface
        outputFile = null
        rawCallback = null
        finished = false
        captureFired = false
        openedAt = System.currentTimeMillis()
        hdrMode = true
        hdrDrcK = 2f
        hdrFrames.clear()
        hdrExposures.clear()
        openCameraInternal(cameraId, 0L)
    }

    /** 常驻会话的预览请求：AE/AF 全自动，只负责把取景器喂出来 + 让 3A 一直收敛 */
    private fun startPersistentPreview() {
        val camera = device ?: return
        val s = session ?: return
        val raw = rawReader ?: return
        val h = handler ?: return

        val req = try {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                previewSurface?.let { addTarget(it) }
                addTarget(raw.surface)
                yuvReader?.surface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                if (shadingSupported) {
                    set(
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        android.hardware.camera2.CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON
                    )
                }
            }.build()
        } catch (e: Exception) {
            Log.e(TAG, "常驻预览请求构造失败", e)
            return
        }

        try {
            s.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    // 只记录。3A 结果给 shoot() 用，这里绝不发快门。
                    // 也不要在这里碰 burstRepeating——连拍期间这个回调本来就不再触发，
                    // 但一旦误设成 false，onRawAvailable 会把后续帧全部丢弃
                    // （实测只出第 1 帧就卡住）。
                    lastResult = result
                }
            }, h)
            Log.d(TAG, "常驻会话就绪（预览 + RAW + YUV），相机保持打开")
        } catch (e: Exception) {
            Log.e(TAG, "常驻预览启动失败", e)
        }
    }

    /**
     * 常驻模式下按一次快门。用最近一帧已收敛的 3A 结果直接起连拍，
     * 不再打开相机、不再建会话、不再等收敛。
     */
    fun shoot(dng: File, cb: Callback) {
        val h = handler ?: return
        shotCallback = cb
        h.post {
            Log.d(TAG, "PSHOT 快门进入：captureFired=$captureFired finished=$finished "
                    + "lastResult=${if (lastResult != null) "有" else "无"} "
                    + "burstRepeating=$burstRepeating")
            if (rebuilding) {
                Log.w(TAG, "会话正在重建，这次快门被拒绝（请稍后再按）")
                val c0 = shotCallback; shotCallback = null
                Handler(android.os.Looper.getMainLooper()).post {
                    c0?.onResult(null, "相机正在准备，请稍后再按")
                }
                return@post
            }
            if (captureFired) {
                Log.w(TAG, "上一次拍摄还没结束，忽略这次快门")
                return@post
            }
            // 每次快门单独设超时：endShot 里 disarm 之后没人再 arm，
            // 卡住时连"采集超时"都看不到（上一轮就是这样，日志一片空白）
            armWatchdog(stopBurst = false)
            val base = lastResult
            if (base == null) {
                val c = shotCallback; shotCallback = null
                Handler(android.os.Looper.getMainLooper()).post {
                    c?.onResult(null, "相机还没准备好")
                }
                return@post
            }
            outputFile = dng
            finished = false
            hdrResult = null
            hdrFrames.clear()
            hdrExposures.clear()
            hdrBaseImage = null
            fireRawCapture()
        }
    }

    /** 关掉常驻会话（离开相机界面时调用） */
    fun stopPersistent() {
        val h = handler
        if (h == null) {
            hardClose()
            return
        }
        h.post {
            persistent = false
            hardClose()
        }
    }

    /**
     * 一次拍摄结束后的轻收尾：把预览恢复、清掉本帧状态，**不关相机**。
     * 和 finish() 的区别只有这一点——finish() 是"拍完就撤"，这里是"拍完继续待命"。
     */
    private fun endShot(file: File?, error: String?) {
        hdrFrames.clear()
        hdrExposures.clear()
        // 【诊断】跨会话一直持有的 Image 会毒化 reader 的缓冲队列：
        // 实测每枪之后只再多出 2 帧就彻底停流，重建 session 也只顶一帧。
        // 这里立刻释放它，验证"持有 Image"是不是元凶。
        // 代价：这一枪的 JPEG 会读到已关闭的 Image（测试用，不能上线）。
        if (CLOSE_BASE_IMAGE_ON_END) {
            try {
                hdrBaseImage?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
        hdrBaseImage = null
        hdrResult = null
        captureFired = false
        finished = false
        outputFile = null
        prewarm = null
        burstRepeating = false
        disarmWatchdog()
        // ---- 重建 session，而不是复用 ----
        //
        // 实测结论（诊断第 3 轮）：这条 HAL 在 AE_MODE_OFF + 长曝光连拍结束之后，
        // 同一 session 里再下发任何 repeating request 都不再产出帧
        // （setRepeatingRequest 仍返回成功，不报错，帧就是不来）。
        // 换 session 是唯一可靠的办法。
        //
        // 但**只重建 session、不重建 device**：device 保留 -> 省掉 openCamera 235ms；
        // AE/AWB 在 device 内是保留的 -> 重建后第一帧曝光就是对的，不用重新收敛
        // （这正是 500ms 的"settle"来源）。
        val old = session
        session = null
        if (RESTORE_PREVIEW_AFTER_SHOT && old != null && rebuildTry < 3) {
            // 等 onClosed 回调再重建（见 StateCallback.onClosed 的注释）
            rebuildTry++
            rebuilding = true
            try {
                old.close()
            } catch (e: Exception) {
                Log.w(TAG, "关闭旧会话失败: ${e.message}")
            }
            // 兜底：某些 HAL 可能不回调 onClosed，1.5 秒后强制重建一次
            handler?.postDelayed({
                if (rebuilding) {
                    Log.w(TAG, "等不到 onClosed，强制重建会话")
                    rebuilding = false
                    if (persistent && device != null && handler != null) createSession(0L)
                }
            }, 1500L)
        } else if (RESTORE_PREVIEW_AFTER_SHOT) {
            if (device != null && characteristics != null && handler != null) {
                createSession(0L)
            } else {
                startPersistentPreview()
            }
        }

        val c = shotCallback
        shotCallback = null
        Handler(android.os.Looper.getMainLooper()).post { c?.onResult(file, error) }
    }

    /** 真正的关闭（常驻模式退出时用） */
    private fun hardClose() {
        try {
            session?.close()
        } catch (e: Exception) {
            // ignore
        }
        session = null
        try {
            device?.close()
        } catch (e: Exception) {
            // ignore
        }
        device = null
        try {
            rawReader?.close()
        } catch (e: Exception) {
            // ignore
        }
        rawReader = null
        try {
            yuvReader?.close()
        } catch (e: Exception) {
            // ignore
        }
        yuvReader = null
        thread?.quitSafely()
        thread = null
        handler = null
        persistent = false
        previewSurface = null
        Log.d(TAG, "常驻会话已关闭")
    }

    private fun finish(file: File?, error: String?, keepReader: Boolean = false) {
        if (persistent) {
            // 常驻模式：只收这一次拍摄，相机继续待命
            if (finished && file == null && error == null) return
            endShot(file, error)
            return
        }
        if (finished) return
        finished = true

        disarmWatchdog()

        try {
            session?.close()
        } catch (e: Exception) {
            // ignore
        }
        session = null

        try {
            device?.close()
        } catch (e: Exception) {
            // ignore
        }
        device = null

        if (!keepReader) {
            rawReader?.close()
        }
        rawReader = null
        yuvReader?.close()
        yuvReader = null

        thread?.quitSafely()
        thread = null
        handler = null

        val cb = callback
        callback = null

        // 回到主线程再回调，方便调用方直接更新 UI
        Handler(android.os.Looper.getMainLooper()).post { cb?.onResult(file, error) }
    }

    companion object {
        private const val TAG = "RawCapture"

        /** 至少让 AE 跑几帧，避免拿上一帧的旧状态直接拍 */
        private const val MIN_SETTLE_MS = 160L

        /**
         * 实验开关：预览请求里是否也挂 12MP RAW。
         * false（默认）= 轻预览，连拍时切目标集合；true = 目标集合恒定但启动慢。
         */
        private const val EXP_RAW_IN_PREVIEW = true
        /** 用预置曝光时，还等几帧让 AWB 收敛 */
        private const val PREWARM_SETTLE_FRAMES = 2
        /** 预置分支最多等多少帧（约 0.9 秒 @14fps）。等不到对焦也要拍，不能卡死 */
        private const val PREWARM_MAX_FRAMES = 14

        /** 整条采集流程的兜底超时。5 帧串行 + 写 DNG + 解 JPEG，正常 2~4 秒。 */
        private const val WATCHDOG_MS = 20_000L
        /** 诊断开关：拍完是否恢复预览 repeating request */
        private const val RESTORE_PREVIEW_AFTER_SHOT = true
        /** 诊断开关：拍完立刻释放 hdrBaseImage */
        private const val CLOSE_BASE_IMAGE_ON_END = false
        /** 实验开关：连拍后连 ImageReader 一起重建 */
        private const val REBUILD_READERS_ON_END = true

        /** RAW 解码比较吃 CPU，单独一条线程，别占住相机线程 */
        private val conversionExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor()
    }
}
