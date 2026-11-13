package com.camera.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.concurrent.CopyOnWriteArrayList

data class ImageFilter(
    val id: String,
    val name: String,
    val type: FilterType,
    val cubeData: CUBEData? = null
)

enum class FilterType {
    BUILTIN,
    CUSTOM_CUBE
}

data class CUBEData(
    val title: String,
    val size: Int,
    val data: FloatArray
) {
    // 重写 equals 和 hashCode，以便比较（基于内容）
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CUBEData) return false
        return title == other.title && size == other.size && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + size
        result = 31 * result + data.contentHashCode()
        return result
    }
}

class FilterManager(private val context: Context) {

    companion object {
        /**
         * 内置滤镜等价的 3x3 矩阵（作用在 0..1 的 sRGB 编码值上）。
         * 预览着色器和成片后处理共用这一份系数，避免两边算法漂移。
         */
        fun builtinMatrix(name: String): FloatArray? = when (name) {
            "Sepia" -> floatArrayOf(
                0.393f, 0.769f, 0.189f,
                0.349f, 0.686f, 0.168f,
                0.272f, 0.534f, 0.131f
            )

            "Mono" -> floatArrayOf(
                0.299f, 0.587f, 0.114f,
                0.299f, 0.587f, 0.114f,
                0.299f, 0.587f, 0.114f
            )

            "Cool" -> floatArrayOf(
                0.9f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1.2f
            )

            "Warm" -> floatArrayOf(
                1.2f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 0.8f
            )

            "Vintage" -> floatArrayOf(
                0.9f, 0.1f, 0f,
                0f, 0.8f, 0.1f,
                0f, 0f, 0.7f
            )

            else -> null
        }
    }

    private val builtinFilters = listOf(
        ImageFilter("1", "Original", FilterType.BUILTIN),
        ImageFilter("2", "Sepia", FilterType.BUILTIN),
        ImageFilter("3", "Mono", FilterType.BUILTIN),
        ImageFilter("4", "Cool", FilterType.BUILTIN),
        ImageFilter("5", "Warm", FilterType.BUILTIN),
        ImageFilter("6", "Vintage", FilterType.BUILTIN)
    )

    private val customFilters = CopyOnWriteArrayList<ImageFilter>()
    private val filtersDir = File(context.filesDir, "filters").also { it.mkdirs() }

    init {
        loadCustomFilters()
    }

    fun getAvailableFilters(): List<ImageFilter> {
        return builtinFilters + customFilters
    }

    suspend fun applyFilter(bitmap: Bitmap, filter: ImageFilter): Bitmap = withContext(Dispatchers.Default) {
        return@withContext when (filter.type) {
            FilterType.BUILTIN -> applyBuiltinFilter(bitmap, filter)
            FilterType.CUSTOM_CUBE -> {
                if (filter.cubeData != null) {
                    applyCUBEFilter(bitmap, filter.cubeData)
                } else {
                    bitmap
                }
            }
        }
    }

    private fun applyBuiltinFilter(bitmap: Bitmap, filter: ImageFilter): Bitmap {
        if (filter.name == "Original") return bitmap

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        when (filter.name) {
            "Sepia" -> {
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val tr = (0.393 * r + 0.769 * g + 0.189 * b).toInt().coerceIn(0, 255)
                    val tg = (0.349 * r + 0.686 * g + 0.168 * b).toInt().coerceIn(0, 255)
                    val tb = (0.272 * r + 0.534 * g + 0.131 * b).toInt().coerceIn(0, 255)
                    pixels[i] = Color.rgb(tr, tg, tb)
                }
            }
            "Mono" -> {
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val gray = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()
                    pixels[i] = Color.rgb(gray, gray, gray)
                }
            }
            "Cool" -> {
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val tb = (b * 1.2).toInt().coerceIn(0, 255)
                    val tr = (r * 0.9).toInt().coerceIn(0, 255)
                    pixels[i] = Color.rgb(tr, g, tb)
                }
            }
            "Warm" -> {
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val tr = (r * 1.2).toInt().coerceIn(0, 255)
                    val tb = (b * 0.8).toInt().coerceIn(0, 255)
                    pixels[i] = Color.rgb(tr, g, tb)
                }
            }
            "Vintage" -> {
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val tr = (r * 0.9 + g * 0.1).toInt().coerceIn(0, 255)
                    val tg = (g * 0.8 + b * 0.1).toInt().coerceIn(0, 255)
                    val tb = (b * 0.7).toInt().coerceIn(0, 255)
                    pixels[i] = Color.rgb(tr, tg, tb)
                }
            }
        }

        val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return resultBitmap
    }

    private suspend fun applyCUBEFilter(bitmap: Bitmap, cubeData: CUBEData): Bitmap {
        val lutProcessor = LUTProcessor()
        return lutProcessor.applyLUT(bitmap, cubeData)
    }

    suspend fun importCUBEFilter(uri: Uri, fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext false
            val lines = inputStream.bufferedReader().use { it.readLines() }
            val cubeData = parseCUBEFile(lines) ?: return@withContext false

            val filterId = "custom_${System.currentTimeMillis()}"
            val filterName = fileName.substringBeforeLast('.').ifBlank { cubeData.title }
            val filter = ImageFilter(filterId, filterName, FilterType.CUSTOM_CUBE, cubeData)
            customFilters.add(filter)
            saveFilter(filter)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 持久化保存一个滤镜到内部存储
     */
    private fun saveFilter(filter: ImageFilter) {
        if (filter.type != FilterType.CUSTOM_CUBE || filter.cubeData == null) return
        val file = File(filtersDir, "${filter.id}.filter")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { dos ->
                // 写入标题
                dos.writeUTF(filter.cubeData.title)
                // 写入大小
                dos.writeInt(filter.cubeData.size)
                // 写入浮点数组长度
                val data = filter.cubeData.data
                dos.writeInt(data.size)
                // 逐个写入浮点数
                for (f in data) {
                    dos.writeFloat(f)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 从文件加载所有已保存的滤镜
     */
    private fun loadCustomFilters() {
        val files = filtersDir.listFiles { file -> file.extension == "filter" } ?: return
        for (file in files) {
            try {
                DataInputStream(BufferedInputStream(FileInputStream(file))).use { dis ->
                    val title = dis.readUTF()
                    val size = dis.readInt()
                    val dataSize = dis.readInt()
                    val data = FloatArray(dataSize)
                    for (i in 0 until dataSize) {
                        data[i] = dis.readFloat()
                    }
                    val cubeData = CUBEData(title, size, data)
                    val filterId = file.nameWithoutExtension
                    val filterName = title.ifBlank { "Imported LUT" }
                    val filter = ImageFilter(filterId, filterName, FilterType.CUSTOM_CUBE, cubeData)
                    customFilters.add(filter)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // 损坏的文件可以删除
                file.delete()
            }
        }
    }

    /**
     * 删除自定义滤镜（可选，供将来扩展）
     */
    fun removeCustomFilter(filterId: String) {
        customFilters.removeAll { it.id == filterId }
        val file = File(filtersDir, "$filterId.filter")
        if (file.exists()) file.delete()
    }

    private fun parseCUBEFile(lines: List<String>): CUBEData? {
        var title = "Unknown"
        var size = 33
        val dataList = mutableListOf<Float>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (trimmed.startsWith("TITLE")) {
                title = trimmed.substringAfter("TITLE").trim().replace("\"", "")
                continue
            }
            if (trimmed.startsWith("LUT_3D_SIZE")) {
                size = trimmed.substringAfter("LUT_3D_SIZE").trim().toIntOrNull() ?: 33
                continue
            }
            if (trimmed.startsWith("DOMAIN")) continue
            if (trimmed.isNotEmpty() && (trimmed[0].isDigit() || trimmed[0] == '-' || trimmed[0] == '.')) {
                val values = trimmed.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                for (value in values) {
                    value.toFloatOrNull()?.let { dataList.add(it) }
                }
            }
        }

        if (dataList.isEmpty() || size < 2 || size > 128) return null

        val expectedCount = size * size * size * 3
        if (dataList.size > expectedCount) {
            return CUBEData(title, size, dataList.subList(0, expectedCount).toFloatArray())
        }
        while (dataList.size < expectedCount) {
            dataList.add(0f)
        }

        return CUBEData(title, size, dataList.toFloatArray())
    }
}