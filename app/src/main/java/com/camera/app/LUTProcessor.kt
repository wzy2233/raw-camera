package com.camera.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LUTProcessor {

    suspend fun applyLUT(bitmap: Bitmap, cubeData: CUBEData): Bitmap = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val lutSize = cubeData.size
        val lutData = cubeData.data

        for (i in pixels.indices) {
            val pixel = pixels[i]

            // 归一化到 0-1
            var r = Color.red(pixel) / 255f
            var g = Color.green(pixel) / 255f
            var b = Color.blue(pixel) / 255f

            // clamping
            r = r.coerceIn(0f, 1f)
            g = g.coerceIn(0f, 1f)
            b = b.coerceIn(0f, 1f)

            // 三线性插值
            val newColor = trilinearInterpolation(r, g, b, lutData, lutSize)

            val tr = (newColor[0] * 255).toInt().coerceIn(0, 255)
            val tg = (newColor[1] * 255).toInt().coerceIn(0, 255)
            val tb = (newColor[2] * 255).toInt().coerceIn(0, 255)

            pixels[i] = Color.rgb(tr, tg, tb)
        }

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return@withContext resultBitmap
    }

    private fun trilinearInterpolation(
        r: Float, g: Float, b: Float,
        lutData: FloatArray, lutSize: Int
    ): FloatArray {
        val maxIndex = lutSize - 1

        val rFloat = r * maxIndex
        val gFloat = g * maxIndex
        val bFloat = b * maxIndex

        val r0 = rFloat.toInt().coerceIn(0, maxIndex - 1)
        val r1 = (r0 + 1).coerceAtMost(maxIndex)
        val g0 = gFloat.toInt().coerceIn(0, maxIndex - 1)
        val g1 = (g0 + 1).coerceAtMost(maxIndex)
        val b0 = bFloat.toInt().coerceIn(0, maxIndex - 1)
        val b1 = (b0 + 1).coerceAtMost(maxIndex)

        val rWeight = rFloat - r0
        val gWeight = gFloat - g0
        val bWeight = bFloat - b0

        val c000 = getLUTValue(r0, g0, b0, lutData, lutSize)
        val c100 = getLUTValue(r1, g0, b0, lutData, lutSize)
        val c010 = getLUTValue(r0, g1, b0, lutData, lutSize)
        val c110 = getLUTValue(r1, g1, b0, lutData, lutSize)
        val c001 = getLUTValue(r0, g0, b1, lutData, lutSize)
        val c101 = getLUTValue(r1, g0, b1, lutData, lutSize)
        val c011 = getLUTValue(r0, g1, b1, lutData, lutSize)
        val c111 = getLUTValue(r1, g1, b1, lutData, lutSize)

        val result = FloatArray(3)
        for (i in 0..2) {
            val c00 = c000[i] * (1 - rWeight) + c100[i] * rWeight
            val c01 = c001[i] * (1 - rWeight) + c101[i] * rWeight
            val c10 = c010[i] * (1 - rWeight) + c110[i] * rWeight
            val c11 = c011[i] * (1 - rWeight) + c111[i] * rWeight

            val c0 = c00 * (1 - gWeight) + c10 * gWeight
            val c1 = c01 * (1 - gWeight) + c11 * gWeight

            result[i] = (c0 * (1 - bWeight) + c1 * bWeight).coerceIn(0f, 1f)
        }

        return result
    }

    private fun getLUTValue(r: Int, g: Int, b: Int, data: FloatArray, size: Int): FloatArray {
        val index = ((b * size) + g) * size + r
        val offset = index * 3
        return if (offset + 2 < data.size) {
            floatArrayOf(data[offset], data[offset + 1], data[offset + 2])
        } else {
            floatArrayOf(0f, 0f, 0f)
        }
    }
}