package com.camera.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 按用户选的格式把 Bitmap 写盘。
 *
 * 为什么需要这一层：Android 的 `Bitmap.compress(JPEG)` 色度固定 4:2:0 且不可调，
 * 想要 4:4:4 必须走自写编码器。这里把几种输出收敛成一个入口，
 * 调用方不用关心扩展名、编码器、ICC 注入的差别。
 */
object ImageSaver {

    private const val TAG = "ImageSaver"

    /** 当前格式对应的扩展名（换格式时文件名跟着变） */
    fun extension(): String = when (RawSettings.format) {
        RawSettings.FORMAT_PNG -> "png"
        RawSettings.FORMAT_HEIF -> "heic"
        RawSettings.FORMAT_WEBP -> "webp"
        else -> "jpg"
    }

    /**
     * @return 实际写入的字节数
     */
    fun save(bitmap: Bitmap, file: File, context: Context?): Long {
        val t0 = System.currentTimeMillis()
        when (RawSettings.format) {
            RawSettings.FORMAT_PNG -> {
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }

            RawSettings.FORMAT_WEBP -> {
                // 无损 WebP：RGB 逐通道无损，天然 4:4:4，没有色度下采样。
                // WEBP_LOSSLESS 需要 API 30；低版本退回有损 WEBP（会变成 4:2:0，
                // 但那时用户本来就拿不到更好的选择）。
                val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                else
                    Bitmap.CompressFormat.WEBP
                FileOutputStream(file).use { bitmap.compress(fmt, 100, it) }
            }


            RawSettings.FORMAT_JPEG444 -> {
                val te = System.currentTimeMillis()
                val bytes = Jpeg444Encoder.encodeBitmap(bitmap, RawSettings.jpegQuality)
                val tEnc = System.currentTimeMillis() - te
                val tw = System.currentTimeMillis()
                FileOutputStream(file).use { it.write(bytes) }
                val tWrite = System.currentTimeMillis() - tw
                val tm = System.currentTimeMillis()
                JpegMeta.ensureSrgb(file, context)
                Log.d(TAG, "  JPEG444 分段：编码 ${tEnc}ms（${bytes.size / 1024}KB）"
                        + " 写盘 ${tWrite}ms 元数据 ${System.currentTimeMillis() - tm}ms")
            }

            else -> {
                FileOutputStream(file).use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, RawSettings.jpegQuality, it)
                }
                JpegMeta.ensureSrgb(file, context)
            }
        }
        Log.d(
            TAG,
            "写入 %s %dKB 耗时 %dms（格式=%d 质量=%d）".format(
                file.name, file.length() / 1024, System.currentTimeMillis() - t0,
                RawSettings.format, RawSettings.jpegQuality
            )
        )
        return file.length()
    }

}
