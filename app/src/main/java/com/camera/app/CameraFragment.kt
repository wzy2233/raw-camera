package com.camera.app

import android.app.Dialog
import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.Gravity
import android.view.WindowManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.util.Rational
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.camera2.interop.Camera2CameraControl
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max

class CameraFragment : Fragment() {

    /**
     * 安装常驻会话实验接口。
     *
     * 这一段是为了**先验证风险**：三路输出（预览 + 12MP RAW + YUV）能不能配出来。
     * 预览用 PRIVATE ImageReader 冒充（不需要界面），验证通过之后再换成真正的
     * TextureView。如果这一步过不了，整个"相机常开"方案就不成立。
     */
    private fun installPersistTest() {
        persistTest = object : PersistTest {
            private var ctl: RawCaptureController? = null
            private var preview: android.media.ImageReader? = null
            private var openedAt = 0L

            override fun open(): String {
                val ctx = context ?: return "无 context"
                val id = backCameraId() ?: return "没有找到后摄"
                // 必须先让出 CameraX，否则 Camera2 打不开
                cameraProvider?.unbindAll()
                persistHolding = true
                setRawBusy(true)
                val c = RawCaptureController(ctx)
                ctl = c
                // 缓冲数给足、消费者放在**独立后台线程**。
                // 之前用 2 个缓冲 + 主线程消费，第 1 枪结束时主线程一忙，
                // 队列就填满 -> HAL 整条管线被卡住 -> 后续快门一帧都收不到。
                // 这是脚手架的问题，不是方案的问题（真正的 TextureView 由显示
                // 系统持续消费，不存在这个坑）。
                val r = android.media.ImageReader.newInstance(
                    1280, 960, android.graphics.ImageFormat.PRIVATE, 6
                )
                persistPreviewThread = android.os.HandlerThread("persist-preview").also { it.start() }
                r.setOnImageAvailableListener({ rd ->
                    try {
                        rd.acquireLatestImage()?.close()
                    } catch (e: Exception) {
                        // ignore
                    }
                }, android.os.Handler(persistPreviewThread!!.looper))
                preview = r
                openedAt = System.currentTimeMillis()
                c.startPersistent(id, r.surface, null)
                return "popen 已发起（相机 $id，预览 1280x960 PRIVATE）"
            }

            override fun shoot(): String {
                val c = ctl ?: return "没有常驻会话"
                val f = File(outputDirectory, "p_${System.currentTimeMillis() % 100000}.dng")
                val t0 = System.currentTimeMillis()
                c.shoot(f) { dng, err ->
                    Log.d(TAG, "pshoot 完成 ${System.currentTimeMillis() - t0}ms "
                            + "dng=${dng?.name} err=$err")
                }
                return "pshoot -> ${f.name}"
            }

            override fun close(): String {
                persistHolding = false
                ctl?.stopPersistent()
                ctl = null
                try {
                    preview?.close()
                } catch (e: Exception) {
                    // ignore
                }
                preview = null
                persistPreviewThread?.quitSafely()
                persistPreviewThread = null
                setRawBusy(false)
                // 把相机还给 CameraX
                if (isAdded) startCamera()
                return "pclose 完成"
            }
        }
    }

    /** 常驻会话实验的对外接口（供 DebugBridge 调用；正式 UI 后续接同一套） */
    interface PersistTest {
        fun open(): String
        fun shoot(): String
        fun close(): String
    }

    companion object {
        /** 常驻会话实验接口，由当前 fragment 实例安装；未安装时为 null */
        @Volatile var persistTest: PersistTest? = null

        private const val TAG = "CameraFragment"
        // 滤镜处理时的解码上限。原来 2560 会把 4:3 的 4096x3072 直接降到 1536x2048，
        // 过一遍滤镜画质掉得厉害，这里放宽到与主摄输出一致。
        private const val MAX_FILTER_DIMENSION = 4096
    }

    private lateinit var viewFinder: PreviewView

    /** 白平衡校准时显示的取景方框，告诉用户把参考面对准哪里 */
    private var calibFrame: View? = null
    private lateinit var captureButton: ImageButton
    private lateinit var galleryButton: ImageButton
    private lateinit var filterButton: ImageButton
    private lateinit var ratioSpinner: Spinner
    private lateinit var settingsButton: android.widget.ImageButton
    private lateinit var switchCameraButton: ImageButton
    private lateinit var orientationButton: ImageButton
    private lateinit var whiteBalanceText: TextView
    private lateinit var aeLockText: TextView
    private lateinit var rawModeText: TextView

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentFilter: ImageFilter? = null
    private var filterManager: FilterManager? = null
    private var isLandscape = false
    private lateinit var outputDirectory: File

    private var lastSelectedRatio: Int = 0

    private var gpuProcessor: TetrahedralLUTFilter? = null
    private var lutStrength = 0.7f

    // 白平衡模式，默认跟随系统自动。App 本身不做任何色彩处理，
    // 这里只是把 Camera2 的 CONTROL_AWB_MODE 透出去，便于在偏色场景下换一个校正档位。
    private var awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO

    private enum class CalibrationStep { DARK, WHITE }

    /** RAW 模式：拍一张就走 Camera2 原始通道，落成 DNG */
    private var rawMode = false
    private var rawBusy = false

    /**
     * rawBusy 的兜底复位。
     *
     * 控制器里有 20 秒的看门狗，但万一回调链整条断掉（比如 session 被厂商 ROM 静默拆掉、
     * 进程被冻结），rawBusy 仍然会锁死，之后每一次快门都被挡掉——只能杀进程。
     * 这里在主线程再加一道：超过 25 秒还没解除就自己复位并把相机还给 CameraX。
     */
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 常驻会话是否正在持有相机（常驻期间不能触发"归还相机"的兜底） */
    @Volatile private var persistHolding = false
    private var persistPreviewThread: android.os.HandlerThread? = null

    private val rawBusyGuard: Runnable = object : Runnable {
        override fun run() {
            if (persistHolding) {
                // 常驻会话本来就会长时间持有相机，这不是"卡住"。
                // 必须重新排一次而不是复位——否则 25 秒后 startCamera() 会把相机抢回去，
                // 常驻会话的 onDisconnected 就会报"相机被其他应用占用"。
                uiHandler.postDelayed(this, 25_000L)
                return
            }
            if (rawBusy) {
                android.util.Log.w("CameraFragment", "rawBusy 超过 25 秒未解除，强制复位")
                rawBusy = false
                try {
                    startCamera()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun setRawBusy(v: Boolean) {
        rawBusy = v
        uiHandler.removeCallbacks(rawBusyGuard)
        if (v) uiHandler.postDelayed(rawBusyGuard, 25_000L)
    }

    /**
     * 暴露给 [DebugBridge] 的入口。
     *
     * 全部走和用户手点一样的函数（toggleRawMode / takePhoto），
     * 所以调试路径不会跑出一条"和真实使用不一样"的分支。
     */
    private val debugHooks = object : DebugBridge.Hooks {
        override fun isRawMode(): Boolean = rawMode

        override fun setRawMode(on: Boolean) {
            if (rawMode != on && !rawBusy) toggleRawMode()
        }

        override fun shoot() {
            takePhoto()
        }

        override fun busy(): Boolean = rawBusy
    }

    /** 曝光锁定。锁上之后 AE 不再自己重算，同一盏灯下颜色才不会飘 */
    private var aeLocked = false
    private var camera2Control: Camera2CameraControl? = null
    /** 点击对焦用的 CameraControl（Camera2CameraControl 没有 startFocusAndMetering） */
    private var cameraControl: androidx.camera.core.CameraControl? = null

    /** 正在进行校准采样时，快门按钮不响应普通拍照 */
    private var pendingCalibration: CalibrationStep? = null

    /** 白卡校准前用户用的白平衡档位，校准失败时回退用 */
    private var awbBeforeCalibration: Int? = null

    // ---------------- 取景实时处理（成片同款链路） ----------------

    /** API 33+ 用 AGSL 运行时着色器，把校准 + 暗部 + 滤镜一次算完 */
    private var previewPipeline: PreviewPipeline? = null

    /** 预览流的实时暗部统计，喂给上面的着色器 */
    private var statsAnalyzer: PreviewStatsAnalyzer? = null

    /** ImageAnalysis 单独一条线程，别跟主线程抢 */
    private var analysisExecutor: java.util.concurrent.ExecutorService? = null

    /** 低分辨率分析流，反复重绑时复用同一个实例 */
    private var analysisUseCase: ImageAnalysis? = null

    /** 当前生效的校准数据，避免每次刷新都去读 SharedPreferences */
    private var activeCalibration: ColorCalibration? = null

    /** 色卡解出的 3x3 色彩校正矩阵（线性光），null 表示没做过 */
    private var activeCcm: FloatArray? = null

    /** RenderEffect 只在"有/没有"之间切换，不要每帧重挂 */
    private var effectAttached = false

    /** RenderEffect 只在参数真的变了才重建（它会把 uniform 快照进去，无法原地改） */
    private var pipelineSignature: String? = null

    private var shadowDelta: FloatArray? = null
    private var shadowLuma = 255f

    private val awbOptions = listOf(
        "自动" to CaptureRequest.CONTROL_AWB_MODE_AUTO,
        "关闭（不做校正）" to CaptureRequest.CONTROL_AWB_MODE_OFF,
        "白炽灯" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
        "日光" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
        "荧光灯" to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
        "阴天" to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        "阴影" to CaptureRequest.CONTROL_AWB_MODE_SHADE
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewFinder = view.findViewById(R.id.viewFinder)
        viewFinder.scaleType = PreviewView.ScaleType.FILL_CENTER
        // SurfaceView 的内容在独立图层里，色彩效果套不上去；换成 TextureView 才能在预览上做校色
        viewFinder.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        installPersistTest()

        // ---- 点击对焦 ----
        //
        // 之前整个 App 没有任何对焦交互，完全靠被动对焦。RAW/HDR 模式下更糟：
        // 我们把 AF 关掉、锁死一个距离去连拍，如果被动对焦当时判断错了，
        // 整段都是虚的。让用户能指定对焦点是最直接的解法。
        //
        // 用的点是 viewFinder 的 meteringPointFactory —— 它会把 View 坐标换算成
        // 传感器坐标（包含裁剪/缩放），比手算准。
        viewFinder.setOnTouchListener { v, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP) {
                try {
                    val point = viewFinder.meteringPointFactory.createPoint(e.x, e.y)
                    val action = androidx.camera.core.FocusMeteringAction.Builder(
                        point,
                        androidx.camera.core.FocusMeteringAction.FLAG_AF or
                                androidx.camera.core.FocusMeteringAction.FLAG_AE
                    ).setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS).build()
                    cameraControl?.startFocusAndMetering(action)
                    Log.d(TAG, "点击对焦 (%.0f, %.0f)".format(e.x, e.y))
                } catch (t: Throwable) {
                    Log.w(TAG, "点击对焦失败: ${t.message}")
                }
                v.performClick()
            }
            false
        }

        captureButton = view.findViewById(R.id.captureButton)
        galleryButton = view.findViewById(R.id.galleryButton)
        filterButton = view.findViewById(R.id.filterButton)
        ratioSpinner = view.findViewById(R.id.ratioSpinner)
        settingsButton = view.findViewById(R.id.settingsButton)
        switchCameraButton = view.findViewById(R.id.switchCameraButton)
        orientationButton = view.findViewById(R.id.orientationButton)
        whiteBalanceText = view.findViewById(R.id.whiteBalanceText)
        aeLockText = view.findViewById(R.id.aeLockText)
        rawModeText = view.findViewById(R.id.rawModeText)
        calibFrame = view.findViewById(R.id.calibFrame)

        RawSettings.attach(requireContext().applicationContext)
        filterManager = FilterManager(requireContext())

        outputDirectory = File(
            requireContext().getExternalFilesDir(null) ?: requireContext().filesDir,
            "CameraApp"
        ).apply { mkdirs() }

        // 调试控制通道：用文件收发命令，省掉"在屏幕上找 RAW 按钮"这一步。
        // 只有调试用途，正式版可以整段删掉，不影响任何功能。
        DebugBridge.attachContext(requireContext())
        DebugBridge.attach(debugHooks)
        DebugBridge.start(outputDirectory)

        // 恢复白卡校准时用的固定档位，否则存下的增益和新管线对不上
        CalibrationStore.loadAwbMode(requireContext())?.let { awbMode = it }

        initPreviewPipeline()
        reloadCalibration()

        setupRatioSpinner()
        initGPUProcessor()
        setupClickListeners()
        updateViewAspectRatio()
        updateWhiteBalanceLabel()
        updateAeLabel()
        updateRawLabel()
        applyPreviewCalibration()
        startCamera()
    }

    /**
     * 预览实时处理管线。
     * RuntimeShader 要 API 33，低版本自动降级成原来的 ColorMatrix 近似。
     */
    private fun initPreviewPipeline() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        previewPipeline = PreviewPipeline.create() ?: return
        statsAnalyzer = PreviewStatsAnalyzer { delta, luma ->
            shadowDelta = delta
            shadowLuma = luma
            applyPreviewCalibration()
        }
        analysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    private fun reloadCalibration() {
        activeCalibration = if (isBackCamera) CalibrationStore.load(requireContext()) else null
        activeCcm = if (isBackCamera) CcmStore.load(requireContext())?.matrix else null
        statsAnalyzer?.setCalibration(activeCalibration)
    }

    private fun setupRatioSpinner() {
        val ratios = arrayOf("4:3", "16:9", "1:1")
        // 顶栏是深色半透明底 + Light 主题，默认 Spinner 文字是深色，几乎看不见，
        // 这里显式把收起状态的字改成白色、下拉列表保持深色字。
        val adapter = object : ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_spinner_item,
            ratios
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.setTextColor(android.graphics.Color.WHITE)
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.setTextColor(android.graphics.Color.BLACK)
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        ratioSpinner.adapter = adapter
        ratioSpinner.setSelection(0)

        ratioSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Spinner 初始化时会回调一次 0，和 onViewCreated 末尾的 startCamera 重复，
                // 这里只处理真正的手动切换。
                if (position == lastSelectedRatio) return
                lastSelectedRatio = position
                updateViewAspectRatio()
                startCamera()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun initGPUProcessor() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val processor = TetrahedralLUTFilter()
                if (!isAdded) {
                    // 视图已经销毁，别把 EGL 上下文漏在外面
                    processor.release()
                    return@launch
                }
                if (processor.isUsable()) {
                    gpuProcessor = processor
                    Log.d(TAG, "GPU LUT 处理器初始化成功")
                } else {
                    processor.release()
                    Log.w(TAG, "GPU LUT 处理器不可用，将使用 CPU 处理")
                }
            } catch (e: Exception) {
                Log.e(TAG, "GPU 处理器初始化失败: ${e.message}")
            }
        }
    }

    private fun setupClickListeners() {
        captureButton.setOnClickListener { takePhoto() }
        galleryButton.setOnClickListener { openGallery() }
        filterButton.setOnClickListener { showFilterDialog() }
        switchCameraButton.setOnClickListener { switchCamera() }
        whiteBalanceText.setOnClickListener { showWhiteBalanceDialog() }
        aeLockText.setOnClickListener { toggleAeLock() }
        rawModeText.setOnClickListener { toggleRawMode() }
        settingsButton.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), SettingsActivity::class.java))
        }
        orientationButton.setOnClickListener {
            isLandscape = !isLandscape
            updateViewAspectRatio()
            startCamera()
        }
    }

    private fun startCamera() {
        if (!isAdded) return
        val providerFuture = ProcessCameraProvider.getInstance(requireContext())
        providerFuture.addListener({
            if (!isAdded) return@addListener
            val provider = try {
                providerFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "获取 CameraProvider 失败", e)
                Toast.makeText(context, "相机初始化失败", Toast.LENGTH_SHORT).show()
                return@addListener
            }
            cameraProvider = provider

            val previewBuilder = Preview.Builder()
            val previewExtender = Camera2Interop.Extender(previewBuilder)
            applyAwbMode(previewExtender)
            // 旁路记录预览已经收敛的曝光/ISO/焦距。只读，不改 CameraX 的行为；
            // 拍照时 RAW 会话直接拿它当初始值，省掉 683ms 的重新收敛。
            previewExtender.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    val af = result.get(CaptureResult.CONTROL_AF_STATE)
                    // AE 已收敛 且 AF 要么已对焦、要么场景无纹理（再等也没用）
                    val aeOk = ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                            ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                    // 同理：UNFOCUSED 时不要把"距离"记进 PrewarmState。
                    // 记录之后 RAW 会话会 AF_MODE_OFF + 锁死这个距离，
                    // 等于把一次失败的对焦烧进整段连拍——这是最要命的那个 bug。
                    val afOk = af == null ||
                            af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                            af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    if (aeOk && afOk) {
                        val t = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                        val fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                        if (t != null && iso != null) {
                            PrewarmState.record(t, iso, fd)
                        }
                    }
                }
            })
            val preview = previewBuilder.build()
            preview.setSurfaceProvider(viewFinder.surfaceProvider)

            val capture = buildCaptureUseCase(ratioSpinner.selectedItemPosition)
            val analysis = ensureAnalysisUseCase()

            try {
                provider.unbindAll()
                // 三路同时绑定在少数机型上会被拒绝，这时退回"预览 + 拍照"，
                // 取景仍然有校准和滤镜，只是暗部统计不更新
                val bound = if (analysis != null) {
                    try {
                        provider.bindToLifecycle(
                            viewLifecycleOwner,
                            cameraSelector,
                            preview,
                            capture,
                            analysis
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "三路绑定失败，退回预览+拍照: ${e.message}")
                        provider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, capture)
                    }
                } else {
                    provider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, capture)
                }
                imageCapture = capture
                applyCropAspectRatio(capture, ratioSpinner.selectedItemPosition)
                camera2Control = Camera2CameraControl.from(bound.cameraControl)
                cameraControl = bound.cameraControl
                // 刚重绑定的时候 AE 还没收敛，锁早了会锁在一个错的曝光上
                if (aeLocked) {
                    lifecycleScope.launch {
                        delay(900)
                        applyAeLock()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "绑定相机失败", e)
                Toast.makeText(context, "无法启动相机", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /** 预览用的低分辨率分析流：只为了拿暗部统计，分辨率越低越省 */
    private fun ensureAnalysisUseCase(): ImageAnalysis? {
        analysisUseCase?.let { return it }
        val analyzer = statsAnalyzer ?: return null
        val executor = analysisExecutor ?: return null

        val selector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(320, 240),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val useCase = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        useCase.setAnalyzer(executor, analyzer)
        analysisUseCase = useCase
        return useCase
    }

    /**
     * 用 ResolutionSelector 选拍摄分辨率。
     * 旧的 setTargetResolution 已废弃，它拿到的尺寸列表是"长边在前"的传感器尺寸、
     * 和屏幕方向无关，容易出现"预览是竖的、拍出来是横的"。
     * 这里把 targetRotation 显式绑到当前显示方向，CameraX 会按方向输出正确的 EXIF。
     */
    private fun buildCaptureUseCase(ratioIndex: Int): ImageCapture {
        val resolutionSelector = when (ratioIndex) {
            1 -> ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                )
                .build()

            // 绝大多数机型没有原生 1:1 的 JPEG 尺寸，这里先用最高分辨率的 4:3，
            // 再由 applyCropAspectRatio() 裁成正方形，画质损失最小。
            2 -> ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                )
                .build()

            else -> ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                )
                .build()
        }

        val rotation = viewFinder.display?.rotation ?: Surface.ROTATION_0

        val builder = ImageCapture.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(rotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        applyAwbMode(Camera2Interop.Extender(builder))
        return builder.build()
    }

    /**
     * 让 CameraX 在保存前按所选比例裁剪。
     * 1:1 这类比例很多机型没有对应的 JPEG 尺寸，只能靠裁剪保证"所见即所得"。
     */
    private fun applyCropAspectRatio(capture: ImageCapture, ratioIndex: Int) {
        // 注意：这里的比例是相对 targetRotation（也就是用户看到的画面）的
        val ratio = when (ratioIndex) {
            1 -> if (isLandscape) Rational(16, 9) else Rational(9, 16)
            2 -> Rational(1, 1)
            else -> if (isLandscape) Rational(4, 3) else Rational(3, 4)
        }
        try {
            capture.setCropAspectRatio(ratio)
        } catch (e: Exception) {
            Log.w(TAG, "设置裁剪比例失败: ${ratioIndex}", e)
        }
    }

    /**
     * 把色彩校准、暗部处理、滤镜实时套到取景画面上。
     *
     * API 33+ 走 PreviewPipeline：一个 AGSL 着色器按"校准 -> 暗部 -> 滤镜"的顺序算，
     * 和 postProcessPhoto 完全同一个公式，所以取景什么样拍出来就什么样。
     * RenderEffect 节点只在"需要 / 不需要"之间切换，uniform 的更新直接进下一帧，
     * 不会因为滤镜或暗部数值变化而反复重建。
     */
    private fun applyPreviewCalibration() {
        if (view == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val pipeline = previewPipeline
            if (pipeline != null) {
                try {
                    pipeline.setCalibration(activeCalibration?.takeIf { !it.isIdentity })
                    pipeline.setCcm(activeCcm)
                    pipeline.setShadow(if (isBackCamera) shadowDelta else null, shadowLuma)
                    pipeline.setFilter(currentFilter?.id, currentFilter, lutStrength)

                    // RenderEffect 会把着色器 uniform 快照进节点，改完参数必须重建节点，
                    // 但只在参数真变了的时候重建。暗部 delta 量化到 ~5 个码值再比较，
                    // 否则 EMA 每帧的细微抖动会让节点每秒重建七八次，白白掉帧。
                    val signature = buildString {
                        append(currentFilter?.id).append('|')
                        append((lutStrength * 100).toInt()).append('|')
                        append(activeCalibration?.summary()).append('|')
                        append(activeCcm?.contentHashCode()).append('|')
                        val d = if (isBackCamera) shadowDelta else null
                        if (d != null) {
                            for (v in d) append((v * 50f).toInt()).append(',')
                            append((shadowLuma / 6f).toInt())
                        }
                    }

                    if (signature != pipelineSignature || !effectAttached) {
                        pipelineSignature = signature
                        val effect = pipeline.effectOrNull()
                        viewFinder.setRenderEffect(effect)
                        effectAttached = effect != null
                        Log.d(
                            TAG,
                            "预览管线更新: filter=${currentFilter?.name} " +
                                "cal=${activeCalibration?.isIdentity == false} " +
                                "shadow=${shadowDelta != null} on=$effectAttached"
                        )
                    }
                    return
                } catch (t: Throwable) {
                    Log.e(TAG, "预览着色器更新失败，退回矩阵近似: ${t.message}")
                    previewPipeline = null
                }
            }
        }

        applyPreviewCalibrationLegacy()
    }

    /** API 31~32 的退路：只有一条伽马空间的近似校准矩阵，滤镜和暗部处理不上预览 */
    private fun applyPreviewCalibrationLegacy() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }

        // 校准是拿主摄做的，换到前置就不该再套
        if (activeCalibration == null || activeCalibration!!.isIdentity) {
            viewFinder.setRenderEffect(null)
            effectAttached = false
            return
        }

        val filter = ColorMatrixColorFilter(activeCalibration!!.toColorMatrix())
        viewFinder.setRenderEffect(RenderEffect.createColorFilterEffect(filter))
        effectAttached = true
    }

    // ---------------- RAW / DNG ----------------

    private fun toggleRawMode() {
        if (rawBusy) return
        rawMode = !rawMode
        updateRawLabel()
        Toast.makeText(
            context,
            if (rawMode) "RAW 模式：快门输出 .dng，会多花一秒多" else "已回到普通 JPEG 拍照",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateRawLabel() {
        rawModeText.setTextColor(
            if (rawMode) 0xFFFFC107.toInt() else 0x99FFFFFF.toInt()
        )
    }

    private fun backCameraId(): String? = try {
        val manager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (e: Exception) {
        null
    }

    /**
     * RAW 采集：CameraX 不支持 RAW 输出，只能把相机让出去走 Camera2 原始通道。
     * 采集完再把相机还给 CameraX，所以取景会黑屏一两秒，属于预期。
     */
    private fun captureRaw() {
        if (rawBusy) return
        if (!isBackCamera) {
            Toast.makeText(context, "RAW 目前只做在主摄上", Toast.LENGTH_SHORT).show()
            return
        }
        val cameraId = backCameraId()
        if (cameraId == null) {
            Toast.makeText(context, "找不到主摄", Toast.LENGTH_SHORT).show()
            return
        }

        setRawBusy(true)
        Toast.makeText(context, "正在采集 RAW，保持不动…", Toast.LENGTH_SHORT).show()

        // 把相机从 CameraX 手里让出来，否则 Camera2 打不开
        cameraProvider?.unbindAll()
        imageCapture = null
        camera2Control = null

        val ctx = requireContext()
        val file = File(
            outputDirectory,
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".dng"
        )

        val controller = RawCaptureController(ctx)
        // DNG 一落盘相机就还回来（onResult），自研解出的 JPEG 稍后到（onJpeg）
        val callback = object : RawCaptureController.Callback {
            override fun onResult(dng: File?, error: String?) {
                setRawBusy(false)
                if (!isAdded) return

                // 不管成功失败，都要把相机还给 CameraX
                startCamera()

                if (dng == null) {
                    Toast.makeText(context, error ?: "RAW 采集失败", Toast.LENGTH_LONG).show()
                    return
                }

                lifecycleScope.launch {
                    val size = withContext(Dispatchers.IO) {
                        exportDngToDownloads(ctx, dng)
                    }
                    Toast.makeText(
                        context,
                        "RAW 已保存 (%.1f MB)，正在生成 JPG".format(size / 1024f / 1024f),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onJpeg(jpg: File) {
                if (!isAdded) return
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            saveToGallery(jpg)
                        } catch (e: Exception) {
                            Log.e(TAG, "RAW 转出的 JPEG 存相册失败", e)
                        }
                    }
                    Toast.makeText(context, "自研解码的 JPG 已放进相册", Toast.LENGTH_SHORT).show()
                }
            }
        }
        // 兜底等待放宽到 2.5 秒：对焦没收敛比慢一点更糟（远景/逆光很容易超过 900ms）
        controller.capture(cameraId, file, 2500L, callback, RawSettings.hdr)
    }

    /** 把 DNG 放进公共的"下载"目录，否则文件管理器里看不到 */
    private fun exportDngToDownloads(ctx: Context, file: File): Long {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CameraApp")
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                    }
                } else {
                    Log.e(TAG, "无法在下载目录创建 DNG 条目")
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "CameraApp"
                ).apply { mkdirs() }
                val dest = File(dir, file.name)
                file.inputStream().use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                MediaScannerConnection.scanFile(
                    ctx, arrayOf(dest.absolutePath), arrayOf("image/x-adobe-dng"), null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "导出 DNG 失败", e)
        }
        return file.length()
    }

    /**
     * 曝光锁定开关。
     * 锁的是 AE 算法本身（CONTROL_AE_LOCK），不用自己去设曝光时间和 ISO，
     * 所以锁的就是"当前这一帧测出来的曝光"。
     * 走 Camera2CameraControl 直接改请求参数，不重绑会话，画面不会闪、AE 也不会被打回重测。
     */
    private fun toggleAeLock() {
        aeLocked = !aeLocked
        applyAeLock()
        updateAeLabel()
        Toast.makeText(
            context,
            if (aeLocked) "曝光已锁定，场景亮度大变时要手动解锁" else "曝光已交还自动",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyAeLock() {
        val control = camera2Control ?: return
        try {
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
                .build()
            control.addCaptureRequestOptions(options)
        } catch (e: Exception) {
            Log.w(TAG, "设置曝光锁定失败", e)
        }
    }

    private fun updateAeLabel() {
        aeLockText.text = if (aeLocked) "AE 锁定" else "AE 自动"
        aeLockText.setTextColor(
            if (aeLocked) 0xFFFFC107.toInt() else android.graphics.Color.WHITE
        )
    }

    private fun askToLockExposure() {
        if (aeLocked) return
        AlertDialog.Builder(requireContext())
            .setTitle("要顺便锁住曝光吗")
            .setMessage(
                "白卡刚在这盏灯下校完。把曝光也锁住，之后同一场景连拍颜色会完全一致；" +
                    "代价是场景亮度大变时（比如拉窗帘、开灯）要手动解锁重测。"
            )
            .setPositiveButton("锁定") { _, _ -> if (!aeLocked) toggleAeLock() }
            .setNegativeButton("以后再说", null)
            .show()
    }

    /** 把当前白平衡档位写进预览/拍照的 CaptureRequest */
    private fun applyAwbMode(extender: Camera2Interop.Extender<*>) {
        try {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        } catch (e: Exception) {
            Log.w(TAG, "设置白平衡模式失败: $awbMode", e)
        }
    }

    private fun updateWhiteBalanceLabel() {
        val label = awbOptions.firstOrNull { it.second == awbMode }?.first ?: "自动"
        whiteBalanceText.text = "AWB $label"
    }

    private fun showWhiteBalanceDialog() {
        val labels = awbOptions.map { it.first }.toTypedArray()
        val checked = awbOptions.indexOfFirst { it.second == awbMode }.coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("白平衡")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                awbMode = awbOptions[which].second
                updateWhiteBalanceLabel()
                if (CalibrationStore.load(requireContext())?.hasWhite() == true) {
                    Toast.makeText(
                        context,
                        "白卡校准是在固定白平衡下做的，改档位后需要重新校准",
                        Toast.LENGTH_LONG
                    ).show()
                }
                // 白平衡是绑在 CaptureRequest 上的，改完要重新绑定才生效
                startCamera()
                dialog.dismiss()
            }
            .setNeutralButton("色彩校准") { _, _ -> showCalibrationDialog() }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 两点色彩校准 ----------------

    private fun showCalibrationDialog() {
        val ctx = requireContext()
        val cal = CalibrationStore.load(ctx)
        val ccm = CcmStore.load(ctx)

        // 不用 setItems + setMessage：两者同时给会被 AlertController 挤掉一个（实测列表直接不显示），
        // 这里自己排一个竖列表，行为和布局都可控
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 28, 56, 8)
        }

        val status = TextView(ctx).apply {
            text = buildString {
                append(cal?.summary() ?: "当前：未校准")
                append("\n")
                append(if (ccm != null) "色卡(JPEG) 平均 ΔE %.1f".format(ccm.meanDeltaE) else "色卡(JPEG) 未校准")
                val rawCcm = RawCcmStore.load(ctx)
                append("\n")
                append(
                    if (rawCcm != null) "色卡(RAW) 平均 ΔE %.1f".format(rawCcm.meanDeltaE)
                    else "色卡(RAW) 未校准"
                )
            }
            textSize = 12f
            setTextColor(0xFF777777.toInt())
            setPadding(0, 0, 0, 20)
        }
        body.addView(status)

        val entries = mutableListOf<Pair<String, () -> Unit>>()
        entries.add("暗场校准" to { confirmDarkCalibration() })
        entries.add("白平衡校准（中性面）" to { confirmWhiteCalibration() })
        entries.add("色卡校准 · RAW（推荐）" to { confirmRawCcmCalibration() })
        entries.add("色卡校准 · JPEG（临时）" to { confirmCcmCalibration() })
        if (ccm != null) {
            entries.add("停用色卡矩阵(JPEG)" to {
                CcmStore.clear(ctx)
                reloadCalibration()
                applyPreviewCalibration()
                Toast.makeText(context, "已停用色卡矩阵(JPEG)", Toast.LENGTH_SHORT).show()
            })
        }
        val rawCcmProfile = RawCcmStore.load(ctx)
        if (rawCcmProfile != null) {
            entries.add("停用色卡矩阵(RAW)" to {
                RawCcmStore.clear(ctx)
                Toast.makeText(context, "已停用色卡矩阵(RAW)", Toast.LENGTH_SHORT).show()
            })
        }
        entries.add("清除全部校准" to { clearCalibration() })

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("色彩校准")
            .setView(body)
            .setNegativeButton("关闭", null)
            .create()

        for ((label, action) in entries) {
            val row = TextView(ctx).apply {
                text = label
                textSize = 16f
                setPadding(0, 30, 0, 30)
                isClickable = true
                setBackgroundColor(0x00000000)
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
            body.addView(row)
        }

        dialog.show()
    }

    private fun clearCalibration() {
        CalibrationStore.clear(requireContext())
        CcmStore.clear(requireContext())
        reloadCalibration()
        applyPreviewCalibration()
        Toast.makeText(context, "已清除色彩校准", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDarkCalibration() {
        AlertDialog.Builder(requireContext())
            .setTitle("暗场校准")
            .setMessage("请用手掌或纸巾把主摄镜头完全盖住，不要漏光，尽量不要让屏幕光反射进去。\n\n准备好后点「开始采样」。")
            .setPositiveButton("开始采样") { _, _ -> startCalibration(CalibrationStep.DARK) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmWhiteCalibration() {
        AlertDialog.Builder(requireContext())
            .setTitle("白平衡校准")
            .setMessage(
                "把一块中性参考面放进取景框中间的方框，占满它，然后按「开始采样」。\n\n" +
                    "· 白纸、18% 灰卡、灰色墙面、另一块屏幕上的中性灰都可以\n" +
                    "· 不要过曝（画面里出现死白会被拒绝），避开反光点\n" +
                    "· 不要用带色物体（牛皮纸、米色纸、有色墙漆）\n" +
                    "· 用你实际拍摄时的那盏灯，换灯后需要重新校准\n\n" +
                    "这一步只消除色偏，不会改变整体亮度——曝光仍然由相机决定。"
            )
            .setPositiveButton("开始采样") { _, _ -> startCalibration(CalibrationStep.WHITE) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startCalibration(step: CalibrationStep) {
        if (imageCapture == null) {
            Toast.makeText(context, "相机尚未就绪", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            var switched = false
            if (step == CalibrationStep.WHITE && awbMode != CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT) {
                // 白平衡必须在固定档位下测。AUTO 每一帧都在重新估增益，
                // 采到的是一个瞬时白点，存成固定增益之后下一张必然又偏。
                awbBeforeCalibration = awbMode
                awbMode = CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                updateWhiteBalanceLabel()
                startCamera()
                switched = true
            }

            if (step == CalibrationStep.WHITE) {
                // 给用户一点时间把参考面对准方框；换档位时相机重绑本身也要等 AE 收敛
                calibFrame?.visibility = View.VISIBLE
                Toast.makeText(context, "把参考面对准方框，别过曝", Toast.LENGTH_SHORT).show()
                delay(if (switched) 1600 else 400)
                delay(900)
            } else {
                delay(300)
            }

            pendingCalibration = step
            val file = File(outputDirectory, "calib_${System.currentTimeMillis()}.jpg")
            captureTo(file) { saved ->
                val current = pendingCalibration
                pendingCalibration = null
                calibFrame?.visibility = View.GONE
                if (current == null) return@captureTo

                lifecycleScope.launch measure@{
                    val result = withContext(Dispatchers.IO) {
                        if (current == CalibrationStep.WHITE) {
                            measureNeutralSurface(saved)
                        } else {
                            val mean = measureCenterMean(saved)
                            if (mean == null) SurfaceSample.Fail("采样失败")
                            else SurfaceSample.Ok(mean, 0f, 0f)
                        }
                    }
                    saved.delete()

                    when (result) {
                        is SurfaceSample.Fail -> {
                            restoreAwbAfterFailedCalibration()
                            Toast.makeText(context, result.reason, Toast.LENGTH_LONG).show()
                        }

                        is SurfaceSample.Ok -> applyCalibrationSample(current, result)
                    }
                }
            }
        }
    }

    /** 采样结果：要么给出一组通道读数，要么给出人话的失败原因 */
    private sealed interface SurfaceSample {
        class Ok(val rgb: FloatArray, val luma: Float, val clip: Float) : SurfaceSample
        class Fail(val reason: String) : SurfaceSample
    }

    /**
     * 中性面采样。
     *
     * 早先的做法是"取最亮的 25% 像素求中位数"，问题在于：
     *   - 白纸上的反光点、甚至已经开始削顶的像素都会被算进去，白点被拉偏；
     *   - 对着灰卡时又会去抓最亮的那一小块，等于换了个参考面；
     *   - 完全不检查过曝比例，一块 10% 已经 249 的白纸照样能"成功"。
     *
     * 现在改成：
     *   1) 采样区取中间 50%，再往里收 10%，避开边缘、手指和阴影
     *   2) 先统计过曝（任一通道 ≥250）比例，超过 2% 直接拒绝——削顶的像素里没有颜色信息
     *   3) 取中位亮度 ±25% 这个"同一照度带"里的像素，避开高光和暗部
     *   4) 落在带内的像素不足 30% 就拒绝：说明参考面不平整或者没填满
     *   5) 带内三通道各取中位数（中位数比均值抗局部反光）
     */
    private fun measureNeutralSurface(file: File): SurfaceSample {
        return try {
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                ?: return SurfaceSample.Fail("照片解码失败")

            try {
                val regionW = (bitmap.width * 0.5f).toInt().coerceAtLeast(8)
                val regionH = (bitmap.height * 0.5f).toInt().coerceAtLeast(8)
                val insetX = (regionW * 0.1f).toInt()
                val insetY = (regionH * 0.1f).toInt()
                val cw = (regionW - insetX * 2).coerceAtLeast(4)
                val chh = (regionH - insetY * 2).coerceAtLeast(4)
                val x0 = (bitmap.width - cw) / 2
                val y0 = (bitmap.height - chh) / 2

                val pixels = IntArray(cw * chh)
                bitmap.getPixels(pixels, 0, cw, x0, y0, cw, chh)
                val total = pixels.size

                val lumHist = IntArray(256)
                var clipped = 0
                var nearBlack = 0
                for (p in pixels) {
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val luma = (r * 77 + g * 150 + b * 29) shr 8
                    lumHist[luma]++
                    if (r >= 250 || g >= 250 || b >= 250) clipped++
                    if (luma <= 6) nearBlack++
                }

                val clipRatio = clipped.toFloat() / total
                if (clipRatio > 0.02f) {
                    return SurfaceSample.Fail(
                        "过曝了：%.1f%% 的像素已经到 250 以上，削顶的地方没有颜色信息。\n请避开反光和直射光，或让参考面稍微转个角度再试"
                            .format(clipRatio * 100f)
                    )
                }
                if (nearBlack > total * 0.3f) {
                    return SurfaceSample.Fail("画面里大部分是暗的，参考面没有填满中间的方框")
                }

                val medianLuma = percentile(lumHist, total, 0.5f)
                if (medianLuma < 28f) {
                    return SurfaceSample.Fail(
                        "参考面太暗（亮度 %.0f/255），噪点会主导采样。请靠近光源或把曝光调亮一点".format(medianLuma)
                    )
                }

                // 只取中位亮度 ±25% 的像素：同一照度、同一反射率，最干净的一撮
                val lo = medianLuma * 0.75f
                val hi = medianLuma * 1.25f
                val histR = IntArray(256)
                val histG = IntArray(256)
                val histB = IntArray(256)
                var count = 0
                // 顺便统计色度波动：中性面在什么灯光下都只有"整体偏色"，不会有"局部颜色跳变"，
                // 而键盘、花布、带字的纸这类物体恰恰相反 —— 这是唯一能识破"对着彩色物体校准"的信号
                var sumRB = 0.0
                var sumRB2 = 0.0
                var sumGM = 0.0
                var sumGM2 = 0.0
                for (p in pixels) {
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val luma = ((r * 77 + g * 150 + b * 29) shr 8).toFloat()
                    if (luma in lo..hi) {
                        histR[r]++
                        histG[g]++
                        histB[b]++
                        count++
                        val rb = (r - b).toDouble()
                        val gm = (g - (r + b) * 0.5).toDouble()
                        sumRB += rb
                        sumRB2 += rb * rb
                        sumGM += gm
                        sumGM2 += gm * gm
                    }
                }
                if (count < total * 0.3f) {
                    return SurfaceSample.Fail(
                        "参考面不够平整：只有 %.0f%% 的像素落在同一亮度带里。\n请让一张白纸/灰卡平整地占满方框，避免褶皱和阴影"
                            .format(count * 100f / total)
                    )
                }

                val n = count.toDouble()
                val sdRB = kotlin.math.sqrt((sumRB2 / n - (sumRB / n) * (sumRB / n)).coerceAtLeast(0.0))
                val sdGM = kotlin.math.sqrt((sumGM2 / n - (sumGM / n) * (sumGM / n)).coerceAtLeast(0.0))
                val chromaSpread = kotlin.math.max(sdRB, sdGM)
                if (chromaSpread > 14.0) {
                    return SurfaceSample.Fail(
                        "参考面颜色太杂（色度波动 %.0f）。\n可能对着彩色物体、键盘或带图案的纸了，请换成纯色的中性灰/白面"
                            .format(chromaSpread)
                    )
                }

                val rgb = floatArrayOf(
                    medianOf(histR, count),
                    medianOf(histG, count),
                    medianOf(histB, count)
                )
                Log.d(
                    TAG,
                    "中性面采样: %d/%d/%d  亮度=%.0f  过曝=%.2f%%  带内=%d%%  色度波动=%.1f".format(
                        rgb[0].toInt(), rgb[1].toInt(), rgb[2].toInt(),
                        medianLuma, clipRatio * 100f, count * 100 / total, chromaSpread
                    )
                )
                SurfaceSample.Ok(rgb, medianLuma, clipRatio)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "中性面采样失败", e)
            SurfaceSample.Fail("采样异常：${e.message}")
        }
    }

    private fun percentile(hist: IntArray, total: Int, p: Float): Float {
        val target = (total * p).toInt().coerceAtLeast(1)
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= target) return v.toFloat()
        }
        return 255f
    }

    private fun medianOf(hist: IntArray, total: Int): Float {
        val half = total / 2
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= half) return v.toFloat()
        }
        return 0f
    }

    // ---------------- 色卡校准（3x3 CCM） ----------------

    // ---------------- 色卡标定：RAW（推荐） ----------------

    private fun confirmRawCcmCalibration() {
        AlertDialog.Builder(requireContext())
            .setTitle("色卡校准 · RAW")
            .setMessage(
                "这条路径不经过系统 ISP：直接读传感器 RAW16，自己减黑电平、补暗角、" +
                    "在 Bayer 域统计每个色块，再解 3×3 矩阵。\n\n" +
                    "为什么要 RAW：JPEG 已经做过去马赛克、降噪、锐化、饱和度提升和 S 形曲线，" +
                    "高饱和色还可能削顶。用 JPEG 解出来的只能算「对 ISP 输出的纠偏」，不是传感器特性。\n\n" +
                    "准备：色卡平放、铺满画面 2/3 以上、镜头正对、光线均匀、避开反光。\n" +
                    "拍摄时会短暂接管相机（约 2 秒），期间画面会停住。"
            )
            .setPositiveButton("拍色卡") { _, _ -> startRawCcmCalibration() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startRawCcmCalibration() {
        if (!isBackCamera) {
            Toast.makeText(context, "RAW 标定只做在主摄上", Toast.LENGTH_SHORT).show()
            return
        }
        val cameraId = backCameraId()
        if (cameraId == null) {
            Toast.makeText(context, "找不到主摄", Toast.LENGTH_SHORT).show()
            return
        }
        if (rawBusy) return

        setRawBusy(true)
        Toast.makeText(context, "正在采集 RAW，保持不动…", Toast.LENGTH_SHORT).show()

        cameraProvider?.unbindAll()
        imageCapture = null
        camera2Control = null

        val ctx = requireContext()
        val controller = RawCaptureController(ctx)
        var result: RawChartCalibrator.Result? = null
        var failure: String? = null

        controller.captureRaw(cameraId, 900L, { image, ch, captureResult, luma ->
            try {
                result = RawChartCalibrator.calibrate(image, ch, captureResult, luma, outputDirectory)
                if (result == null) {
                    failure = ChartAutoDetector.lastDiagnostic.ifBlank { "没找到色卡" }
                }
            } catch (e: Exception) {
                Log.e(TAG, "RAW 标定失败", e)
                failure = e.message ?: "RAW 标定异常"
            }
        }, object : RawCaptureController.Callback {
            override fun onResult(dng: File?, error: String?) {
                setRawBusy(false)
                if (!isAdded) return
                // 不管成败，相机都要还给 CameraX
                startCamera()

                val r = result
                if (r == null) {
                    Toast.makeText(
                        context,
                        error ?: failure ?: "RAW 标定失败",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                showRawCcmResult(r)
            }
        })
    }

    private fun showRawCcmResult(r: RawChartCalibrator.Result) {
        val worst = r.perPatchAfter.indices.maxByOrNull { r.perPatchAfter[it] } ?: 0
        val verdict = when {
            r.meanAfter <= 2f -> "很好"
            r.meanAfter <= 3.5f -> "可用"
            r.meanAfter <= 6f -> "合格"
            r.meanAfter <= 10f -> "勉强"
            else -> "失败"
        }
        val usable = r.meanAfter <= 10f

        val message = buildString {
            append("传感器 RAW，不经过 ISP\n")
            append("落点 %d/24 格，削顶 %d 像素\n\n".format(r.detected, r.clipped))
            append("平均 ΔE76：%.1f → %.1f\n".format(r.meanBefore, r.meanAfter))
            append("最差色块：%.1f（%s）\n\n".format(
                r.maxAfter, ColorChecker.PATCH_NAMES[worst]
            ))
            append("采集时白平衡增益 %.2f / %.2f / %.2f\n\n".format(r.gainR, r.gainG, r.gainB))

            when {
                r.clipped > 500 ->
                    append("⚠ 有较多像素削顶，高饱和色已经丢信息，这次结果会偏差。\n")
                        .append("把曝光压低一点（或让色卡别正对灯）重拍。\n\n")

                r.meanAfter > 6f ->
                    append("⚠ 残差偏大。多半是反光、色卡没铺满，或者环境光太杂。\n\n")

                else -> {}
            }

            append("矩阵（作用在去马赛克后的线性光上）：\n")
            for (i in 0 until 3) {
                append("%+.3f  %+.3f  %+.3f\n".format(
                    r.matrix[i * 3], r.matrix[i * 3 + 1], r.matrix[i * 3 + 2]
                ))
            }
            append("\n这份矩阵只在 RAW 模式下生效（RAW 拍的照片/自研解码的 JPG）。")
        }

        val builder = AlertDialog.Builder(requireContext())
            .setTitle("色卡标定 · $verdict")
            .setMessage(message)
            .setNeutralButton("重拍") { _, _ -> startRawCcmCalibration() }
            .setNegativeButton("放弃", null)

        if (usable) {
            builder.setPositiveButton("保存并启用") { _, _ ->
                RawCcmStore.save(requireContext(), r.matrix, r.meanAfter)
                Toast.makeText(
                    context,
                    "RAW 颜色矩阵已启用，下一次 RAW 出片生效",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        builder.show()
    }

    private fun confirmCcmCalibration() {
        AlertDialog.Builder(requireContext())
            .setTitle("色卡校准 · JPEG（临时方案）")
            .setMessage(
                "注意：这条路径走的是系统 ISP 输出的 JPEG，上面已经带着去马赛克、降噪、锐化、" +
                    "饱和度提升和 S 形曲线，高饱和色可能已经削顶。\n\n" +
                    "用它解出来的矩阵只是「对 ISP 输出的二次纠偏」，不是这颗传感器的真实特性，" +
                    "换机器/换固件就失效。只适合临时救急，正式标定请用 RAW 那条。\n\n" +
                    "准备：色卡平放、整张放进画面、尽量正对、光线均匀、避开反光和阴影。"
            )
            .setPositiveButton("拍色卡") { _, _ -> startCcmCalibration() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startCcmCalibration() {
        if (imageCapture == null) {
            Toast.makeText(context, "相机尚未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, "拍色卡：整张放进去，尽量正对", Toast.LENGTH_SHORT).show()

        val file = File(outputDirectory, "ccm_${System.currentTimeMillis()}.jpg")
        captureTo(file) { saved ->
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) { decodeForChart(saved) }
                saved.delete()
                if (bitmap == null) {
                    Toast.makeText(context, "照片解码失败", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 先试自动识别；失败再退回手动点四角
                val auto = withContext(Dispatchers.IO) { autoAnalyzeChart(bitmap) }
                if (auto != null) {
                    Log.d(TAG, "色卡自动识别成功: ${auto.summary()}")
                    bitmap.recycle()
                    showCcmResult(auto, autoDetected = true)
                } else {
                    Log.d(TAG, "色卡自动识别失败: ${ChartAutoDetector.lastDiagnostic}")
                    // 留一张现场图，方便事后调检测参数
                    try {
                        FileOutputStream(File(outputDirectory, "ccm_debug.png")).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "保存调试图失败: ${e.message}")
                    }
                    Toast.makeText(
                        context,
                        "没自动找到色卡，手动点四个角",
                        Toast.LENGTH_SHORT
                    ).show()
                    showQuadPicker(bitmap)
                }
            }
        }
    }

    /**
     * 自动识别版：找到 24 格 → 每格取中心 30% 的中位数 → 试 8 种朝向里误差最小的那种。
     *
     * 朝向为什么要试：手机拍出来可能是横的、竖的、倒的，而色卡的参考值是固定的顺序。
     * 与其判断"哪边朝上"，不如把 4 个旋转 × 镜像都解一遍，谁拟合得好就是谁——反正只要几十毫秒。
     */
    /**
     * 自动识别版：拿到若干候选网格 → 逐个采样解矩阵 → 取 ΔE 最小的那个。
     *
     * 为什么要多个候选：几何上常常能解出几个"都说得通"的网格（比如把对角方向当格距），
     * 光看几何分不出来。但 24 个颜色是已知的，错的那个 ΔE 会差到 30+，对的那个在 10 以内。
     * 这一步同时也是朝向判定（8 种排法各解一遍）。
     */
    private fun autoAnalyzeChart(bitmap: Bitmap): ColorChecker.Result? {
        val grids = ChartAutoDetector.detectCandidates(bitmap)
        if (grids.isEmpty()) return null

        val luts = activeCalibration?.takeIf { !it.isIdentity }?.buildChannelLuts()
        var best: ColorChecker.Result? = null

        for ((index, grid) in grids.withIndex()) {
            // 透视下格子的宽高比不一定是 1:1，横纵分开算半边长
            val halfX = (grid.cellW * bitmap.width * 0.26f).toInt().coerceAtLeast(2)
            val halfY = (grid.cellH * bitmap.height * 0.26f).toInt().coerceAtLeast(2)

            val colors = Array(grid.rows * grid.cols) { i ->
                val cx = (grid.centers[i * 2] * bitmap.width).toInt()
                val cy = (grid.centers[i * 2 + 1] * bitmap.height).toInt()
                samplePatch(bitmap, cx, cy, halfX, halfY, luts)
            }
            val r = ColorChecker.solveBestOrientation(colors, grid.rows, grid.cols) ?: continue
            Log.d(
                TAG,
                "色卡候选#$index: %dx%d 格，%d 块落点，平均 ΔE %.1f -> %.1f".format(
                    grid.rows, grid.cols, grid.detected, r.meanBefore, r.meanAfter
                )
            )
            if (best == null || r.meanAfter < best.meanAfter) best = r
        }
        return best
    }

    /** 取以 (cx,cy) 为中心、半边长 halfX/halfY 的方形区域内，各通道中位数 */
    private fun samplePatch(
        bitmap: Bitmap,
        cx: Int,
        cy: Int,
        halfX: Int,
        halfY: Int,
        luts: Array<IntArray>?
    ): FloatArray {
        val x0 = (cx - halfX).coerceIn(0, bitmap.width - 1)
        val x1 = (cx + halfX).coerceIn(0, bitmap.width - 1)
        val y0 = (cy - halfY).coerceIn(0, bitmap.height - 1)
        val y1 = (cy + halfY).coerceIn(0, bitmap.height - 1)
        if (x1 <= x0 || y1 <= y0) return floatArrayOf(128f, 128f, 128f)

        val pw = x1 - x0 + 1
        val ph = y1 - y0 + 1
        val buf = IntArray(pw * ph)
        bitmap.getPixels(buf, 0, pw, x0, y0, pw, ph)

        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        for (p in buf) {
            histR[(p shr 16) and 0xFF]++
            histG[(p shr 8) and 0xFF]++
            histB[p and 0xFF]++
        }
        val count = buf.size
        if (count == 0) return floatArrayOf(128f, 128f, 128f)

        var r = medianOf(histR, count)
        var g = medianOf(histG, count)
        var b = medianOf(histB, count)
        if (luts != null) {
            r = luts[0][r.toInt().coerceIn(0, 255)].toFloat()
            g = luts[1][g.toInt().coerceIn(0, 255)].toFloat()
            b = luts[2][b.toInt().coerceIn(0, 255)].toFloat()
        }
        return floatArrayOf(r, g, b)
    }

    /** 8 种朝向各解一遍、取残差最小的一次，具体实现在 ColorChecker 里（便于单元测试） */

    /**
     * 色卡照片不需要原始分辨率，缩到 1600 以内既够采样也省内存。
     * 必须按 EXIF 摆正——CameraX 存的是传感器方向的像素，不摆正的话
     * 屏幕上看到的色卡会和拍的时候差 90°，点四角必然点错。
     */
    private fun decodeForChart(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (kotlin.math.max(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return decoded

        val matrix = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, matrix, true
        )
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    /** 全屏显示刚拍的色卡，让用户点四个角 */
    private fun showQuadPicker(bitmap: Bitmap) {
        val ctx = requireContext()
        val container = FrameLayout(ctx)

        val imageView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(bitmap)
        }
        container.addView(
            imageView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val hint = TextView(ctx).apply {
            text = "依次点色卡左上、右上、右下、左下（贴着色块外沿）"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 18, 24, 18)
        }
        container.addView(
            hint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        val picker = QuadPickView(ctx)
        container.addView(
            picker,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(container)
        val width = resources.displayMetrics.widthPixels
        val height = (width * bitmap.height.toFloat() / bitmap.width).toInt()
        dialog.window?.setLayout(width, height)
        dialog.window?.setBackgroundDrawableResource(android.R.color.black)

        picker.onComplete = { pts ->
            val sx = bitmap.width.toFloat() / picker.width
            val sy = bitmap.height.toFloat() / picker.height
            val corners = pts.map { PointF(it.x * sx, it.y * sy) }
            dialog.dismiss()
            analyzeChart(bitmap, corners)
        }

        dialog.setOnDismissListener { hint.text = "" }
        dialog.show()
    }

    private fun analyzeChart(bitmap: Bitmap, corners: List<PointF>) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val measured = sampleChartPatches(bitmap, corners) ?: return@withContext null
                ColorChecker.solve(measured)
            }
            if (result == null) {
                Toast.makeText(
                    context,
                    "解析失败：可能是四角点歪了，或者色卡被反光/阴影盖住了，重拍一次试试",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            Log.d(TAG, "色卡解算(手动四角): ${result.summary()}")
            showCcmResult(result, autoDetected = false)
        }
    }

    /**
     * 按格取样。四点定出的四边形做透视映射，每格取中心 50% 的区域、逐通道取中位数。
     * 取样结果先过一遍当前的白平衡校准（因为 CCM 是接在 WB 之后用的）。
     */
    private fun sampleChartPatches(bitmap: Bitmap, corners: List<PointF>): Array<FloatArray>? {
        if (corners.size != 4) return null

        val matrix = Matrix()
        val src = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val dst = floatArrayOf(
            corners[0].x, corners[0].y,
            corners[1].x, corners[1].y,
            corners[2].x, corners[2].y,
            corners[3].x, corners[3].y
        )
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return null

        val luts = activeCalibration?.takeIf { !it.isIdentity }?.buildChannelLuts()
        val out = Array(ColorChecker.COUNT) { FloatArray(3) }

        for (row in 0 until ColorChecker.ROWS) {
            for (col in 0 until ColorChecker.COLS) {
                val u = (col + 0.5f) / ColorChecker.COLS
                val v = (row + 0.5f) / ColorChecker.ROWS
                val halfU = 0.5f / ColorChecker.COLS * 0.5f
                val halfV = 0.5f / ColorChecker.ROWS * 0.5f

                val quad = floatArrayOf(
                    u - halfU, v - halfV,
                    u + halfU, v - halfV,
                    u + halfU, v + halfV,
                    u - halfU, v + halfV
                )
                matrix.mapPoints(quad)

                var minX = quad[0]; var maxX = quad[0]
                var minY = quad[1]; var maxY = quad[1]
                var k = 2
                while (k < 8) {
                    if (quad[k] < minX) minX = quad[k]
                    if (quad[k] > maxX) maxX = quad[k]
                    if (quad[k + 1] < minY) minY = quad[k + 1]
                    if (quad[k + 1] > maxY) maxY = quad[k + 1]
                    k += 2
                }

                val x0 = minX.toInt().coerceIn(0, bitmap.width - 1)
                val x1 = maxX.toInt().coerceIn(0, bitmap.width - 1)
                val y0 = minY.toInt().coerceIn(0, bitmap.height - 1)
                val y1 = maxY.toInt().coerceIn(0, bitmap.height - 1)
                if (x1 - x0 < 2 || y1 - y0 < 2) return null

                val histR = IntArray(256)
                val histG = IntArray(256)
                val histB = IntArray(256)
                var count = 0
                for (y in y0..y1) {
                    for (x in x0..x1) {
                        val p = bitmap.getPixel(x, y)
                        histR[(p shr 16) and 0xFF]++
                        histG[(p shr 8) and 0xFF]++
                        histB[p and 0xFF]++
                        count++
                    }
                }
                if (count < 16) return null

                var r = medianOf(histR, count)
                var g = medianOf(histG, count)
                var b = medianOf(histB, count)
                if (luts != null) {
                    r = luts[0][r.toInt().coerceIn(0, 255)].toFloat()
                    g = luts[1][g.toInt().coerceIn(0, 255)].toFloat()
                    b = luts[2][b.toInt().coerceIn(0, 255)].toFloat()
                }
                out[row * ColorChecker.COLS + col] = floatArrayOf(r, g, b)
            }
        }
        return out
    }

    private fun showCcmResult(result: ColorChecker.Result, autoDetected: Boolean) {
        val worst = result.perPatchAfter.indices.maxByOrNull { result.perPatchAfter[it] } ?: 0

        // 质量判定。一个正常的 24 色卡取样，校正前应该在 3~12（相机 + 屏幕的误差），
        // 校正后 1~3。校正后还大于 8，说明"实测"和"参考"根本不是同一批颜色——
        // 几乎都是四个角点歪了（点到黑边、点到卡片外沿），这种情况下矩阵会把画面压暗压黄。
        val verdict = when {
            result.meanAfter <= 2.5f -> "很好"
            result.meanAfter <= 4f -> "可用"
            result.meanAfter <= 8f -> "合格"
            result.meanAfter <= 12f -> "勉强"
            else -> "失败"
        }
        val usable = result.meanAfter <= 12f

        val message = buildString {
            if (autoDetected) append("自动识别到 24 格（缺失的格子由格点推算补上）\n\n")
            append("平均 ΔE76：%.1f → %.1f\n".format(result.meanBefore, result.meanAfter))
            append("最差色块：%.1f → %.1f（%s）\n".format(
                result.maxBefore, result.maxAfter, ColorChecker.PATCH_NAMES[worst]
            ))
            if (abs(result.exposure - 1f) > 0.02f) {
                append("曝光对齐 ×%.2f（矩阵只改颜色，不改亮度）\n".format(result.exposure))
            }
            append("\n")

            when {
                result.meanAfter > 12f ->
                    append("⚠ 残差太大，这张不适合做标定。\n")
                        .append("常见原因：反光/摩尔纹、色块被裁掉、光源偏色太重。\n\n")

                result.meanBefore > 20f ->
                    append("说明：校正前偏差主要来自照片本身的色偏（白平衡/光源），")
                        .append("不是格子没对上——24 格已经落在色块上。矩阵只能补回颜色关系，")
                        .append("补不回已经削顶的高光。\n\n")

                result.meanBefore < 2f ->
                    append("⚠ 实测值和参考值几乎一样，不像拍到了色卡。\n\n")

                else -> {}
            }

            append("矩阵（线性光，行优先）：\n")
            for (r in 0 until 3) {
                append("%+.3f  %+.3f  %+.3f\n".format(
                    result.matrix[r * 3], result.matrix[r * 3 + 1], result.matrix[r * 3 + 2]
                ))
            }
            val sums = result.rowSums()
            append("\n三行之和 %.2f / %.2f / %.2f。\n".format(sums[0], sums[1], sums[2]))
            append("彼此接近 = 只改颜色；差得多 = 顺带把白平衡的残留一并纠正了。")
        }

        val builder = AlertDialog.Builder(requireContext())
            .setTitle("色卡标定 · $verdict")
            .setMessage(message)
            .setNeutralButton("重拍") { _, _ -> startCcmCalibration() }
            .setNegativeButton("放弃", null)

        if (usable) {
            builder.setPositiveButton("保存并启用") { _, _ ->
                CcmStore.save(requireContext(), result.matrix, result.meanAfter)
                reloadCalibration()
                applyPreviewCalibration()
                Toast.makeText(context, "色卡矩阵已启用", Toast.LENGTH_SHORT).show()
            }
        } else {
            // 不合格的矩阵不让保存：它会把整张照片压暗压黄，比不校更糟
            Toast.makeText(
                context,
                "残差太大，不能保存。请重新拍：色卡铺满画面、四角贴在彩色方块外角",
                Toast.LENGTH_LONG
            ).show()
        }

        builder.show()
    }

    private fun restoreAwbAfterFailedCalibration() {
        awbBeforeCalibration?.let {
            awbMode = it
            awbBeforeCalibration = null
            updateWhiteBalanceLabel()
            startCamera()
        }
    }

    /** 取画面中心 40% 区域的平均 RGB，0..255 */
    private fun measureCenterMean(file: File): FloatArray? {
        return try {
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            try {
                val cw = (bitmap.width * 0.4f).toInt().coerceAtLeast(1)
                val ch = (bitmap.height * 0.4f).toInt().coerceAtLeast(1)
                val x0 = (bitmap.width - cw) / 2
                val y0 = (bitmap.height - ch) / 2

                val pixels = IntArray(cw * ch)
                bitmap.getPixels(pixels, 0, cw, x0, y0, cw, ch)

                var sr = 0L
                var sg = 0L
                var sb = 0L
                for (p in pixels) {
                    sr += (p shr 16) and 0xFF
                    sg += (p shr 8) and 0xFF
                    sb += p and 0xFF
                }
                val n = pixels.size.toFloat()
                floatArrayOf(sr / n, sg / n, sb / n)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "校准采样失败", e)
            null
        }
    }

    private fun applyCalibrationSample(step: CalibrationStep, result: SurfaceSample.Ok) {
        val sample = result.rgb
        val existing = CalibrationStore.load(requireContext()) ?: ColorCalibration(null, null)

        val updated = when (step) {
            CalibrationStep.DARK -> existing.copy(dark = sample)
            CalibrationStep.WHITE -> existing.copy(white = sample)
        }

        CalibrationStore.save(requireContext(), updated)

        if (step == CalibrationStep.WHITE) {
            // 把测量时的固定档位一起存下来，下次启动自动恢复，保证"测量管线 = 使用管线"
            CalibrationStore.saveAwbMode(requireContext(), awbMode)
            awbBeforeCalibration = null
        }

        reloadCalibration()
        applyPreviewCalibration()
        Log.d(TAG, "校准已保存: ${updated.summary()}")

        val tip = when (step) {
            CalibrationStep.DARK -> "暗场已记录：黑电平 %.1f / %.1f / %.1f".format(
                sample[0], sample[1], sample[2]
            )

            CalibrationStep.WHITE -> {
                val g = updated.gains()
                "中性面 %d/%d/%d（亮度 %.0f）\n增益 %.2f / %.2f / %.2f，整体亮度不变".format(
                    sample[0].toInt(), sample[1].toInt(), sample[2].toInt(),
                    result.luma, g[0], g[1], g[2]
                )
            }
        }
        Toast.makeText(context, tip, Toast.LENGTH_LONG).show()

        if (step == CalibrationStep.WHITE) {
            askToLockExposure()
        }
    }

        private fun updateViewAspectRatio() {
        val ratio = when (ratioSpinner.selectedItemPosition) {
            0 -> if (isLandscape) "4:3" else "3:4"
            1 -> if (isLandscape) "16:9" else "9:16"
            2 -> "1:1"
            else -> "3:4"
        }

        val layoutParams = viewFinder.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            ?: return
        layoutParams.dimensionRatio = ratio
        viewFinder.layoutParams = layoutParams
    }

    private fun takePhoto() {
        if (pendingCalibration != null) return
        if (rawMode) {
            captureRaw()
            return
        }

        // 先落 .tmp，处理完再改名：图库里不会出现"写了一半的图片"，
        // 也能让快门回调一结束就提示"已拍摄"，不用等后处理跑完
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val finalFile = File(outputDirectory, "$stamp.jpg")
        val tempFile = File(outputDirectory, "$stamp.jpg.tmp")

        captureTo(tempFile) { saved ->
            Toast.makeText(context, "已拍摄", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        postProcessPhoto(saved)
                        if (!saved.renameTo(finalFile)) {
                            saved.copyTo(finalFile, overwrite = true)
                            saved.delete()
                        }
                        saveToGallery(finalFile)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "保存照片失败", e)
                        false
                    }
                }
                Toast.makeText(
                    context,
                    if (ok) "照片已保存" else "保存照片失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun captureTo(photoFile: File, onSaved: (File) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            Toast.makeText(context, "相机尚未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "拍照失败", exc)
                    Toast.makeText(context, "拍照失败", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onSaved(photoFile)
                }
            }
        )
    }

    /**
     * 后处理顺序：色彩校准(减黑电平 + 白点增益) -> 暗部中性化 -> 滤镜 -> 重新编码。
     *
     * 校准和暗部中性化合成一次遍历（PhotoProcessor），省掉一次 48MB 的像素数组往返；
     * 两者都没改动、也没有滤镜时直接保留相机直出的 JPEG，不重新编码，速度和画质都是最优。
     */
    private suspend fun postProcessPhoto(photoFile: File) {
        val calibration = if (isBackCamera) activeCalibration else null
        val filter = currentFilter

        // 内置滤镜是纯 3x3 矩阵，直接并进 PhotoProcessor 的那一遍遍历，不用再复制一张 12MP 位图
        val builtinMatrix = if (filter?.type == FilterType.BUILTIN) {
            FilterManager.builtinMatrix(filter.name)
        } else {
            null
        }
        val useBuiltinMatrix = builtinMatrix != null
        val needGlFilter = filter != null && !useBuiltinMatrix &&
            !(filter.type == FilterType.BUILTIN && filter.name == "Original")

        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            // 重编码会把 EXIF 全丢掉，先把它读出来，写回时再用
            val srcTags = JpegMeta.captureTags(photoFile)
            var bitmap = decodeBitmapWithExifRotation(photoFile.absolutePath)
            try {
                val changed = PhotoProcessor.applyInPlace(
                    bitmap,
                    calibration?.takeIf { !it.isIdentity },
                    if (useBuiltinMatrix) builtinMatrix else null,
                    if (isBackCamera) activeCcm else null
                )

                if (needGlFilter && filter != null) {
                    val cubeData = filter.cubeData
                    val processed: Bitmap = if (
                        filter.type == FilterType.CUSTOM_CUBE &&
                        cubeData != null &&
                        gpuProcessor?.isUsable() == true
                    ) {
                        val processor = gpuProcessor!!
                        processor.setStrength(lutStrength)
                        processor.setLUT(cubeData)
                        processor.processBitmap(bitmap) ?: bitmap
                    } else {
                        filterManager?.applyFilter(bitmap, filter) ?: bitmap
                    }
                    if (processed !== bitmap) {
                        bitmap.recycle()
                        bitmap = processed
                    }
                }

                // 什么都没改就不用重新编码，直接留着相机那张；
                // 但色彩空间标记还是要补——相机直出的 JPEG 未必带
                if (!changed && !needGlFilter) {
                    JpegMeta.ensureSrgb(photoFile, requireContext(), srcTags)
                    Log.d(TAG, "后处理跳过（无改动），耗时 ${System.currentTimeMillis() - startedAt}ms")
                    return@withContext
                }

                FileOutputStream(photoFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)
                }
                // 补 sRGB 标记：APP1 的 ColorSpace + APP2 的 sRGB v2 ICC
                JpegMeta.ensureSrgb(photoFile, requireContext(), srcTags)
                Log.d(
                    TAG,
                    "后处理完成 ${System.currentTimeMillis() - startedAt}ms" +
                        " (${bitmap.width}x${bitmap.height}, cal=$changed, filter=${filter?.name})"
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun decodeBitmapWithExifRotation(path: String): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sampleSize = calculateInSampleSize(bounds)

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        val bitmap = BitmapFactory.decodeFile(path, options)
            ?: throw IllegalStateException("无法解码图片")

        val orientation = try {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options): Int {
        if (options.outWidth <= 0 || options.outHeight <= 0) return 1
        var sample = 1
        while (max(options.outWidth, options.outHeight) / sample > MAX_FILTER_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    private fun saveToGallery(photoFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, photoFile.name)
                    put(
                        MediaStore.Images.Media.MIME_TYPE,
                        // 必须和扩展名一致：MediaStore 发现名字和 MIME 对不上时，
                        // 会自作主张再补一个后缀（出现过 xxx.webp.jpg 这种）
                        when {
                            photoFile.name.endsWith(".png", true) -> "image/png"
                            photoFile.name.endsWith(".webp", true) -> "image/webp"
                            photoFile.name.endsWith(".heic", true) -> "image/heic"
                            else -> "image/jpeg"
                        }
                    )
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraApp")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = requireContext().contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    Log.e(TAG, "无法创建媒体库条目")
                    return
                }
                resolver.openOutputStream(uri)?.use { output ->
                    photoFile.inputStream().use { input -> input.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val picturesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "CameraApp"
                ).apply { mkdirs() }
                val destFile = File(picturesDir, photoFile.name)
                photoFile.inputStream().use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                MediaScannerConnection.scanFile(
                    requireContext(),
                    arrayOf(destFile.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存到相册失败", e)
        }
    }

    private fun openGallery() {
        val files = outputDirectory.listFiles { file ->
            file.isFile && file.extension.equals("jpg", true)
        }?.sortedByDescending { it.lastModified() }.orEmpty()

        if (files.isEmpty()) {
            Toast.makeText(context, "还没有照片", Toast.LENGTH_SHORT).show()
            return
        }

        val recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = GalleryAdapter(files.map { Uri.fromFile(it) }) { uri -> showImage(uri) }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.6f).toInt()
            )
        }

        BottomSheetDialog(requireContext()).apply {
            setContentView(recyclerView)
            show()
        }
    }

    /**
     * 全屏看图。
     * 原来是塞进 AlertDialog 里，ImageView 既没有 LayoutParams 也没有尺寸，
     * 对话框在 Glide 异步加载完成之前就按 0x0 量好了，所以只剩一个空弹窗。
     * 这里换成全屏 Dialog，ImageView 是 match_parent，加载前后都有确定的尺寸。
     */
    private fun showImage(uri: Uri) {
        val ctx = context ?: return
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val imageView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        val hint = TextView(ctx).apply {
            text = "轻触任意处关闭"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 13f
        }

        val root = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(
                imageView,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                hint,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = (48 * resources.displayMetrics.density).toInt() }
            )
            setOnClickListener { dialog.dismiss() }
        }
        imageView.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(root)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        Glide.with(this)
            .load(uri)
            .error(android.R.drawable.ic_menu_report_image)
            .into(imageView)

        dialog.show()
    }

    /**
     * 滤镜缩略图用的样张：优先截当前取景画面（所见即所得），
     * 取不到时退回到一张程序画出来的内置样图。
     */
    private fun buildFilterSample(): Bitmap {
        val size = 240
        val fromPreview = try {
            viewFinder.bitmap
        } catch (e: Exception) {
            null
        }

        if (fromPreview != null && fromPreview.width > 0 && fromPreview.height > 0) {
            try {
                val side = kotlin.math.min(fromPreview.width, fromPreview.height)
                val x = (fromPreview.width - side) / 2
                val y = (fromPreview.height - side) / 2
                val cropped = Bitmap.createBitmap(fromPreview, x, y, side, side)
                val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
                cropped.recycle()
                if (fromPreview !== cropped) fromPreview.recycle()
                return scaled
            } catch (e: Exception) {
                Log.w(TAG, "截取取景画面失败，改用内置样图", e)
            }
        }
        return drawBuiltinSample(size)
    }

    /** 内置样图：天空渐变 + 肤色 + 绿/红 + 白/灰参考块，滤镜的色调变化一眼可见 */
    private fun drawBuiltinSample(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = size.toFloat()

        paint.shader = LinearGradient(
            0f, 0f, 0f, s * 0.62f,
            intArrayOf(0xFF4A90D9.toInt(), 0xFFC6DCEE.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, s, s * 0.62f, paint)
        paint.shader = null

        paint.color = 0xFF6E5844.toInt()
        canvas.drawRect(0f, s * 0.62f, s, s, paint)

        paint.color = 0xFFFFF1B8.toInt()
        canvas.drawCircle(s * 0.74f, s * 0.20f, s * 0.09f, paint)

        paint.color = 0xFFE0B08A.toInt()
        canvas.drawRoundRect(
            RectF(s * 0.08f, s * 0.38f, s * 0.30f, s * 0.58f),
            s * 0.03f, s * 0.03f, paint
        )

        paint.color = 0xFF3E7D3A.toInt()
        canvas.drawCircle(s * 0.42f, s * 0.53f, s * 0.10f, paint)

        paint.color = 0xFFC0392B.toInt()
        canvas.drawRect(s * 0.60f, s * 0.42f, s * 0.78f, s * 0.60f, paint)

        // 白色 / 中灰参考块，白平衡和滤镜偏色靠它们最容易看出来
        paint.color = 0xFFF2F2F2.toInt()
        canvas.drawRect(s * 0.10f, s * 0.68f, s * 0.40f, s * 0.86f, paint)
        paint.color = 0xFF7F7F7F.toInt()
        canvas.drawRect(s * 0.44f, s * 0.68f, s * 0.74f, s * 0.86f, paint)

        return bitmap
    }

    private fun showFilterDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val dialogView = layoutInflater.inflate(R.layout.dialog_filter_select, null)
        dialog.setContentView(dialogView)

        val filterRecyclerView = dialogView.findViewById<RecyclerView>(R.id.filterRecyclerView)
        val importFilterButton = dialogView.findViewById<Button>(R.id.importFilterButton)
        val noFilterButton = dialogView.findViewById<Button>(R.id.noFilterButton)

        filterRecyclerView.layoutManager = GridLayoutManager(requireContext(), 3)

        val filters = filterManager?.getAvailableFilters() ?: emptyList()

        val sample = buildFilterSample()
        filterRecyclerView.adapter = FilterAdapter(filters, sample) { selectedFilter ->
            currentFilter = selectedFilter
            Log.d(TAG, "选择滤镜: ${selectedFilter.name} (${selectedFilter.type})")
            applyPreviewCalibration()
            dialog.dismiss()
            Toast.makeText(context, "滤镜: ${selectedFilter.name}", Toast.LENGTH_SHORT).show()
        }

        importFilterButton.setOnClickListener {
            importCustomFilter()
            dialog.dismiss()
        }
        noFilterButton.setOnClickListener {
            currentFilter = null
            applyPreviewCalibration()
            dialog.dismiss()
            Toast.makeText(context, "已移除滤镜", Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    private val importFilterLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { handleImportedFilter(it) } }

    private fun importCustomFilter() {
        importFilterLauncher.launch("*/*")
    }

    private fun handleImportedFilter(uri: Uri) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) { importFilter(uri) }
            Toast.makeText(
                context,
                if (success) "滤镜已导入" else "导入失败，仅支持 .cube 文件",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private suspend fun importFilter(uri: Uri): Boolean {
        return try {
            val context = context ?: return false
            val fileName = context.contentResolver.query(uri, null, null, null, null)
                ?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        cursor.getString(nameIndex)
                    } else {
                        null
                    }
                } ?: "unknown"

            if (!fileName.endsWith(".cube", ignoreCase = true)) {
                return false
            }
            filterManager?.importCUBEFilter(uri, fileName) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "导入滤镜失败", e)
            false
        }
    }

    private val isBackCamera: Boolean
        get() = cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA

    private fun switchCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        reloadCalibration()
        applyPreviewCalibration()
        startCamera()
    }

    override fun onDestroyView() {
        // 视图销毁时把兜底检测摘掉，并把忙标志复位——否则下次进来快门还是死的
        uiHandler.removeCallbacks(rawBusyGuard)
        rawBusy = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera2Control = null
        imageCapture = null
        analysisUseCase?.clearAnalyzer()
        analysisUseCase = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        previewPipeline?.release()
        previewPipeline = null
        statsAnalyzer = null
        gpuProcessor?.release()
        gpuProcessor = null
        DebugBridge.detach(debugHooks)
        DebugBridge.stop()
        super.onDestroyView()
    }

    inner class FilterAdapter(
        private val filters: List<ImageFilter>,
        private val sample: Bitmap,
        private val onFilterSelect: (ImageFilter) -> Unit
    ) : RecyclerView.Adapter<FilterAdapter.ViewHolder>() {

        private val previews = HashMap<String, Bitmap>()
        private val pending = HashSet<String>()

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imageView: ImageView = itemView.findViewById(R.id.filterPreviewImage)
            val textView: TextView = itemView.findViewById(R.id.filterNameText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_filter, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val filter = filters[position]
            holder.textView.text = filter.name

            val cached = previews[filter.id]
            if (cached != null) {
                holder.imageView.setImageBitmap(cached)
            } else {
                // 先显示原样，滤镜效果算完再替换，避免列表卡顿
                holder.imageView.setImageBitmap(sample)
                renderPreview(holder, filter, position)
            }

            holder.itemView.setOnClickListener { onFilterSelect(filter) }
        }

        override fun getItemCount() = filters.size

        private fun renderPreview(holder: ViewHolder, filter: ImageFilter, position: Int) {
            val manager = filterManager ?: return
            if (!pending.add(filter.id)) return

            lifecycleScope.launch {
                val preview = try {
                    manager.applyFilter(sample, filter)
                } catch (e: Exception) {
                    Log.w(TAG, "生成滤镜预览失败: ${filter.name}", e)
                    sample
                }
                previews[filter.id] = preview
                if (holder.bindingAdapterPosition == position) {
                    holder.imageView.setImageBitmap(preview)
                }
            }
        }
    }
}
