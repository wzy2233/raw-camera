package com.camera.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * 调试控制通道：**用文件当命令行**，不必在屏幕上点控件。
 *
 * 为什么不用广播 / Activity Intent：
 *   · 广播要动态注册 + exported，Android 13 以后每个版本规则都在变；
 *   · Intent 需要 Activity 在前台且能拿到参数，落盘路径又受 scoped storage 影响；
 *   · 文件通道只依赖"应用本来就能读写自己的外部目录"这一条，稳定，而且
 *     能把结果写回去 —— 走 logcat 也能看，但读文件更快、更干净。
 *
 * 用法（不需要点任何按钮）：
 *
 *   adb shell "echo 'raw 1; hdr 1; uexp 2; shoot' > /sdcard/Android/data/com.camera.app/files/CameraApp/.dbg_cmd"
 *   adb shell "cat /sdcard/Android/data/com.camera.app/files/CameraApp/.dbg_out"
 *
 * 一条命令一行，也可以用分号分隔；`#` 开头是注释。支持的命令：
 *
 *   raw 0|1|toggle     RAW 模式（= 屏幕上那个 RAW 按钮；只有它是亮的，快门才走自研 RAW 管线）
 *   shoot              按一次快门（走当前模式）
 *   stat               打印当前所有开关和状态
 *   hdr 0|1            HDR+ 多帧
 *   uexp 1|2|4         高光余量（欠曝倍数）
 *   str 0..200         局部影调强度
 *   fmt 0|1|2|4        输出格式：0=4:4:4 JPEG  1=4:2:0 JPEG  2=PNG  4=WebP
 *   q 1..100           JPEG 质量
 *   pipe 0|1           native 管线开关
 *   tmo / nr / ca / hue 0|1   HDR 局部影调 / 色度降噪 / 色差校正 / 色相微调
 *   huep 0|1          色相保持修正（逐通道曲线的色相随亮度漂移的修正）
 *   rcd / shading / wb / ccm / hlrecon / shoulder 0|1   各处理步骤开关
 *   reset              全部恢复默认
 *   help               这份说明
 *
 * 设计约束：只在 [start] 与 [stop] 之间轮询（跟随 Fragment 的 resume/pause），
 * 避免后台常驻耗电；一切 UI 操作都 post 到主线程，和用户手点完全等价。
 */
object DebugBridge {

    private const val TAG = "DebugBridge"
    private const val CMD_FILE = ".dbg_cmd"
    private const val OUT_FILE = ".dbg_out"
    private const val POLL_MS = 350L

    /** CameraFragment 提供给调试通道的入口。全部在主线程上被调用。 */
    interface Hooks {
        fun isRawMode(): Boolean
        fun setRawMode(on: Boolean)
        fun shoot()
        fun busy(): Boolean
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var hooks: Hooks? = null
    @Volatile private var dir: File? = null
    @Volatile private var running = false
    @Volatile private var lastStamp: Long = -1L
    @Volatile private var lastLen: Long = -1L

    /** 上一次执行过的时间戳，用来在响应里说明"这是哪一次" */
    private var seq = 0

    private val poller = object : Runnable {
        override fun run() {
            if (!running) return
            pollOnce()
            main.postDelayed(this, POLL_MS)
        }
    }

    fun attach(h: Hooks) {
        hooks = h
    }

    fun detach(h: Hooks) {
        if (hooks === h) hooks = null
    }

    /** 开始监听命令文件（Fragment onResume） */
    fun start(d: File) {
        dir = d
        if (!d.exists()) d.mkdirs()
        // 首次启动时把上一轮的命令文件清掉，避免一开机就重放旧命令
        File(d, CMD_FILE).takeIf { it.exists() }?.delete()
        lastStamp = -1
        lastLen = -1
        if (running) return
        running = true
        main.postDelayed(poller, POLL_MS)
        Log.d(TAG, "调试通道已开启：${File(d, CMD_FILE).absolutePath}")
    }

    fun stop() {
        running = false
        main.removeCallbacks(poller)
    }

    private fun pollOnce() {
        val d = dir ?: return
        val f = File(d, CMD_FILE)
        if (!f.exists()) return
        val stamp = f.lastModified()
        val len = f.length()
        if (stamp == lastStamp && len == lastLen) return
        lastStamp = stamp
        lastLen = len
        val text = try {
            f.readText()
        } catch (e: Exception) {
            Log.w(TAG, "读命令文件失败: ${e.message}")
            return
        }
        execute(text)
    }

    private fun execute(text: String) {
        val lines = text.split('\n', ';')
        val out = StringBuilder()
        out.append("seq=").append(++seq)
            .append(" ver=").append(NativeHdr.version)
            .append('\n')
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
            val cmd = parts[0].lowercase()
            val arg = parts.getOrNull(1)?.lowercase()
            val r = try {
                runCommand(cmd, arg)
            } catch (e: Exception) {
                "错误: ${e.message}"
            }
            out.append(cmd).append(if (arg != null) " $arg" else "").append(" -> ").append(r).append('\n')
            Log.d(TAG, "$cmd $arg -> $r")
        }
        out.append(statusText())
        writeOut(out.toString())
    }

    /** 所有命令都在主线程执行，与手点控件走同一条路径 */
    private fun runCommand(cmd: String, arg: String?): String {
        fun on(): Boolean = when (arg) {
            null, "", "toggle", "1", "on", "true" -> true
            else -> false
        }
        fun flag(v: Boolean): String = if (v) "开" else "关"
        return when (cmd) {
            "help" -> "见 DebugBridge 注释"

            "stat" -> "ok"

            "raw" -> {
                val h = hooks ?: return "界面未就绪"
                val target = when (arg) {
                    null, "", "toggle" -> !h.isRawMode()
                    "1", "on", "true", "open" -> true
                    else -> false
                }
                h.setRawMode(target)
                "RAW 模式 = ${flag(h.isRawMode())}"
            }

            "shoot" -> {
                val h = hooks ?: return "界面未就绪"
                if (h.busy()) return "忙，已忽略"
                h.shoot()
                "已触发快门（RAW=${flag(h.isRawMode())} HDR=${flag(RawSettings.hdr)}）"
            }

            "hdr" -> { RawSettings.hdr = on(); flag(RawSettings.hdr) }
            "uexp" -> {
                val v = arg?.toIntOrNull()
                if (v == null) "需要 0(自动)/1/2/3/4" else {
                    RawSettings.hdrUnderexpose = v
                    if (RawSettings.hdrUnderexpose == 0) "自动" else "${RawSettings.hdrUnderexpose}×"
                }
            }
            "str" -> {
                val v = arg?.toIntOrNull()
                if (v == null) "需要 0..200" else { RawSettings.hdrStrength = v; "${RawSettings.hdrStrength}" }
            }
            "sharp" -> {
                val v = arg?.toIntOrNull()
                if (v == null) "需要 0..100" else { RawSettings.sharpenAmount = v; "${RawSettings.sharpenAmount}" }
            }
            "ab" -> { RawSettings.abTest = on(); flag(RawSettings.abTest) }
            "srlanes" -> {
                val v = arg?.toIntOrNull()
                if (v == null) "需要 0..8（0=不限）" else { RawSettings.srLanes = v; "${RawSettings.srLanes}" }
            }
            "sr" -> { RawSettings.superRes = on(); flag(RawSettings.superRes) }
            "absr" -> { RawSettings.abSrTest = on(); flag(RawSettings.abSrTest) }
            "srsig" -> {
                val v = arg?.toFloatOrNull()
                if (v == null) "需要 0.3..1.5" else { RawSettings.srSigma = v; "${RawSettings.srSigma}" }
            }
            "fmt" -> {
                val v = arg?.toIntOrNull()
                if (v == null) "需要 0/1/2/4" else { RawSettings.format = v; "${RawSettings.format}" }
            }
            "q" -> {
                val v = arg?.toIntOrNull()
                if (v == null) "需要 1..100" else { RawSettings.jpegQuality = v; "${RawSettings.jpegQuality}" }
            }
            "pipe" -> { RawSettings.nativePipeline = on(); flag(RawSettings.nativePipeline) }
            "heifcheck" -> {
                // 用系统解码器（BitmapFactory，API 28+ 支持 HEIF）真解一遍。
                // 这是最硬的判据：Photos 用的就是这条解码路径。
                // 支持指定文件名：heifcheck _selftest.heic
                val f = if (arg.isNullOrBlank()) {
                    dir?.listFiles()?.filter { it.name.endsWith(".heic") }
                        ?.maxByOrNull { it.lastModified() }
                } else {
                    java.io.File(dir, arg)
                }
                if (f == null) {
                    "没有 .heic 文件"
                } else {
                    val bm = try {
                        android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                    } catch (e: Throwable) {
                        null
                    }
                    if (bm == null) {
                        "解码失败：${f.name} ${f.length()} 字节"
                    } else {
                        val px = bm.getPixel(bm.width / 2, bm.height / 2)
                        val r = (px shr 16) and 0xFF
                        val gg = (px shr 8) and 0xFF
                        val b = px and 0xFF
                        // 顺手导一张缩略图，用来肉眼确认方向与色彩（P010 的 UV 顺序、
                        // 有限/全范围最容易搞错，而这两项错了数据照样能解码）
                        try {
                            val t = android.graphics.Bitmap.createScaledBitmap(
                                bm, bm.width / 6, bm.height / 6, true
                            )
                            java.io.FileOutputStream(java.io.File(dir, "_heifprev.jpg")).use {
                                t.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it)
                            }
                            t.recycle()
                        } catch (e: Throwable) {
                            Log.w(TAG, "预览导出失败: ${e.message}")
                        }
                        val s2 = "解码成功 ${f.name} ${bm.width}x${bm.height} " +
                                "中心像素 RGB($r,$gg,$b) ${f.length() / 1024}KB"
                        bm.recycle()
                        s2
                    }
                }
            }
            "heifselftest" -> {
                // 参数可选：8 或 10（默认 10）。8bit 走 NV12，用来把"10bit 打包"
                // 和"容器/色彩转换/编码"切开做对照。
                // 8 = NV12, 10 = P010, 88 = 诊断（整块填 0x80 全灰）
                val depth = arg?.toIntOrNull() ?: 10
                val f = File(dir, if (depth == 10) "_selftest.heif" else "_selftest${depth}.heif")
                val rc = if (NativeHdr.available)
                    NativeHdr.nativeHeifSelfTest(2048, 1536, 95, depth, f.absolutePath) else -999
                "heifselftest(${depth}bit)=$rc  -> ${f.name} " +
                        "${if (f.exists()) "${f.length()} 字节" else "无文件"}"
            }
            // ---- 常驻会话（相机常开）验证 ----
            "popen" -> CameraFragment.persistTest?.open() ?: "相机界面未就绪"
            "pshoot" -> CameraFragment.persistTest?.shoot() ?: "相机界面未就绪"
            "pclose" -> CameraFragment.persistTest?.close() ?: "相机界面未就绪"
            "heifprobe" -> {
                val f = File(dir, "_probe.heif")
                val r = if (NativeHdr.available) NativeHdr.nativeHeifProbe(f.absolutePath) else -999
                "heifprobe=$r（0=可用，详见 logcat HdrHeif）"
            }
            "tmo" -> { RawSettings.hdrLocalContrast = on(); flag(RawSettings.hdrLocalContrast) }
            "nr" -> { RawSettings.hdrChromaNr = on(); flag(RawSettings.hdrChromaNr) }
            "ca" -> { RawSettings.caFix = on(); flag(RawSettings.caFix) }
            "hue" -> { RawSettings.hueTune = on(); flag(RawSettings.hueTune) }
            "huep" -> { RawSettings.huePreserve = on(); flag(RawSettings.huePreserve) }
            "rcd" -> { RawSettings.rcd = on(); flag(RawSettings.rcd) }
            "shading" -> { RawSettings.shading = on(); flag(RawSettings.shading) }
            "wb" -> { RawSettings.whiteBalance = on(); flag(RawSettings.whiteBalance) }
            "wb2" -> { RawSettings.wbFromCcGains = on(); flag(RawSettings.wbFromCcGains) }
            "wbr" -> { RawSettings.wbRefine = on(); flag(RawSettings.wbRefine) }
            "abw" -> { RawSettings.abWb = on(); flag(RawSettings.abWb) }
            "ccm" -> { RawSettings.colorMatrix = on(); flag(RawSettings.colorMatrix) }
            "hlrecon" -> { RawSettings.highlightRecon = on(); flag(RawSettings.highlightRecon) }
            "shoulder" -> { RawSettings.shoulder = on(); flag(RawSettings.shoulder) }
            "reset" -> {
                hooks?.setRawMode(false)
                RawSettings.resetAll(appContext ?: return "缺少 context")
                "已恢复默认"
            }
            else -> "未知命令（help 查看）"
        }
    }

    private var appContext: android.content.Context? = null

    fun attachContext(c: android.content.Context) {
        appContext = c.applicationContext
    }

    private fun statusText(): String {
        val h = hooks
        return buildString {
            append("---- 状态 ----\n")
            append("native=").append(NativeHdr.available).append(" ver=").append(NativeHdr.version).append('\n')
            append("rawMode=").append(h?.let { if (it.isRawMode()) 1 else 0 } ?: -1)
            append(" busy=").append(h?.let { if (it.busy()) 1 else 0 } ?: -1).append('\n')
            append("hdr=").append(if (RawSettings.hdr) 1 else 0)
            append(" uexp=").append(RawSettings.hdrUnderexpose)
            append(" str=").append(RawSettings.hdrStrength)
            append(" pipe=").append(if (RawSettings.nativePipeline) 1 else 0).append('\n')
            append("tmo=").append(if (RawSettings.hdrLocalContrast) 1 else 0)
            append(" nr=").append(if (RawSettings.hdrChromaNr) 1 else 0)
            append(" ca=").append(if (RawSettings.caFix) 1 else 0)
            append(" hue=").append(if (RawSettings.hueTune) 1 else 0)
            append(" huep=").append(if (RawSettings.huePreserve) 1 else 0)
            append(" wb2=").append(if (RawSettings.wbFromCcGains) 1 else 0)
            append(" rcd=").append(if (RawSettings.rcd) 1 else 0).append('\n')
            append("fmt=").append(RawSettings.format)
            append(" q=").append(RawSettings.jpegQuality).append('\n')
        }
    }

    private fun writeOut(text: String) {
        val d = dir ?: return
        try {
            val tmp = File(d, "$OUT_FILE.tmp")
            tmp.writeText(text)
            val dst = File(d, OUT_FILE)
            if (!tmp.renameTo(dst)) {
                dst.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "写响应失败: ${e.message}")
        }
    }
}
