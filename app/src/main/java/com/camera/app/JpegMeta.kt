package com.camera.app

import android.content.Context
import android.media.ExifInterface
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 给 JPEG 补上"这些数字属于哪个色彩空间"的说明。
 *
 * 为什么必须补：`Bitmap.compress()` 重编码时会把原图的 EXIF 全部丢掉，
 * 写出来的 JPEG 只有 SOI + JFIF + Skia 那个 456 字节的 ICC v4 精简配置，
 * **没有 APP1/EXIF，也就没有 ColorSpace 标记**。结果：
 *  - 有的看图软件（尤其老版本 Windows、部分安卓相册）不认识 v4 精简配置，直接按 sRGB 猜
 *  - 广色域屏幕上就会出现"颜色艳丽/发灰"的显示错误
 *  - 连拍摄时间、机型都没有，相册排序和归档也会乱
 *
 * 这里补两样东西：
 *  1) **APP1/EXIF**：ColorSpace=1（sRGB）、Orientation=1、DateTime、Make/Model、Software
 *     —— Windows 资源和大多数安卓相册看这个
 *  2) **APP2 ICC**：一份完整的 sRGB v2（IEC61966-2.1）配置，2572 字节
 *     —— macOS / iOS / Photoshop / Chrome 看这个。用 v2 而不是 v4，是因为 v2 的兼容面最广
 *
 * 只做"补"，不动像素。重复调用是安全的（旧的 ICC 段会被替换，不会叠加）。
 */
object JpegMeta {

    private const val TAG = "JpegMeta"

    /** JPEG 里一段 APP2 ICC 最多放 65533-14 字节，超了要分片 */
    private const val ICC_CHUNK = 65519

    /**
     * 在重编码**之前**把原图的拍摄参数读出来（重编码会把它们全丢掉）。
     * 用法：先 captureTags(原文件)，压缩覆盖之后再 ensureSrgb(文件, ctx, tags)。
     */
    fun captureTags(file: File): Map<String, String> {
        val out = HashMap<String, String>()
        if (!file.exists()) return out
        try {
            val exif = ExifInterface(file.absolutePath)
            for (tag in COPY_TAGS + ExifInterface.TAG_DATETIME_ORIGINAL) {
                val v = exif.getAttribute(tag) ?: continue
                if (v.isNotBlank()) out[tag] = v
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取原图 EXIF 失败: ${e.message}")
        }
        return out
    }

    /**
     * @param tags 可选：captureTags() 读出来的拍摄参数
     */
    fun ensureSrgb(file: File, context: Context?, tags: Map<String, String>? = null) {
        if (!file.exists() || file.length() < 128) return

        // 1) 先写 EXIF。ExifInterface 会重写整个文件，所以必须放在 ICC 之前，
        //    否则它可能把后面塞进去的 APP2 丢掉的
        try {
            writeExif(file, tags)
        } catch (e: Exception) {
            Log.w(TAG, "写 EXIF 失败: ${e.message}")
        }

        // 2) 再字节级插入 ICC（这一步只搬字节，不动其它段）
        if (context == null) return
        try {
            val icc = context.resources.openRawResource(R.raw.srgb_v2).use { it.readBytes() }
            if (icc.size > 128) injectIcc(file, icc)
        } catch (e: Exception) {
            Log.w(TAG, "写 ICC 失败: ${e.message}")
        }
    }

    private fun writeExif(file: File, tags: Map<String, String>?) {
        val exif = ExifInterface(file.absolutePath)

        // 像素已经被我们转正了（或本来就是正方向），所以方向标记固定为 1
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
        // 关键：告诉读图软件这是 sRGB。1 = sRGB，2 = Adobe RGB（未标定）
        exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")

        val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
        if (exif.getAttribute(ExifInterface.TAG_DATETIME).isNullOrBlank()) {
            exif.setAttribute(ExifInterface.TAG_DATETIME, now)
        }
        if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrBlank()) {
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
        }
        if (exif.getAttribute(ExifInterface.TAG_MAKE).isNullOrBlank()) {
            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER ?: "unknown")
        }
        if (exif.getAttribute(ExifInterface.TAG_MODEL).isNullOrBlank()) {
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL ?: "unknown")
        }
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "CameraApp")

        // 从原图搬拍摄参数
        if (tags != null) {
            for ((tag, v) in tags) {
                if (exif.getAttribute(tag).isNullOrBlank()) exif.setAttribute(tag, v)
            }
        }
        exif.saveAttributes()
    }

    /**
     * 把 ICC 塞进 APP2。旧的 ICC 段会被跳过（不复制），所以重复调用不会叠加。
     *
     * 位置：紧跟 SOI（若有 APP0/JFIF 则放在它后面），这是各家解码器都认的位置。
     */
    private fun injectIcc(file: File, icc: ByteArray) {
        val data = file.readBytes()
        if (data.size < 4) return
        if (data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) return

        // 先扫一遍头部各段，记下位置；旧 ICC 直接跳过（我们要重写一份）
        class Seg(val marker: Int, val start: Int, val end: Int)

        val segs = ArrayList<Seg>(8)
        var i = 2
        while (i + 4 <= data.size) {
            if (data[i] != 0xFF.toByte()) break
            val marker = data[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            // 只有 APPn 段可以重排；DQT/SOF/DHT 这些必须紧跟在 APPn 之后，
            // 放到它们后面去的话，不少解析器（含 Pillow）就找不到 ICC 了
            if (marker < 0xE0 || marker > 0xEF) break
            val len = segmentLength(data, i) ?: break
            val segEnd = i + 2 + len
            if (segEnd > data.size) break
            val isOldIcc = marker == 0xE2 && len >= 14 &&
                String(data, i + 4, 11, Charsets.US_ASCII) == "ICC_PROFILE"
            if (!isOldIcc) segs.add(Seg(marker, i, segEnd))
            i = segEnd
        }

        val out = ByteArrayOutputStream(data.size + icc.size + 64)
        out.write(data, 0, 2)   // SOI

        // 规范顺序：APP0(JFIF) -> APP1(EXIF) -> APP2(ICC) -> 其余
        // 少数老解析器要求 JFIF 必须是第一个段，所以这里不是"插在 SOI 后面"了事
        segs.filter { it.marker == 0xE0 }.forEach { out.write(data, it.start, it.end - it.start) }
        segs.filter { it.marker != 0xE0 }.forEach { out.write(data, it.start, it.end - it.start) }
        writeIccSegments(out, icc)

        if (i < data.size) out.write(data, i, data.size - i)
        file.writeBytes(out.toByteArray())
    }

    private fun segmentLength(data: ByteArray, at: Int): Int? {
        if (at + 4 > data.size) return null
        val len = ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)
        if (len < 2) return null
        return len
    }

    private fun writeIccSegments(out: ByteArrayOutputStream, icc: ByteArray) {
        val chunks = (icc.size + ICC_CHUNK - 1) / ICC_CHUNK
        for (n in 0 until chunks) {
            val start = n * ICC_CHUNK
            val size = minOf(ICC_CHUNK, icc.size - start)
            val payloadLen = 12 + 2 + size   // "ICC_PROFILE\0" + seq + count + 数据
            out.write(0xFF)
            out.write(0xE2)
            out.write((payloadLen + 2) shr 8 and 0xFF)
            out.write((payloadLen + 2) and 0xFF)
            out.write("ICC_PROFILE".toByteArray(Charsets.US_ASCII))
            out.write(0)
            out.write(n + 1)
            out.write(chunks)
            out.write(icc, start, size)
        }
    }

    private val COPY_TAGS = arrayOf(
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_METERING_MODE
    )
}
