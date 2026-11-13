package com.camera.app

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 相机设置页。
 *
 * 条目顺序严格按实际处理链从上到下排，方便对照：
 *   采集 -> 解码（每一步一个开关）-> 输出
 *
 * 每个开关下面都写了"关掉会怎样"，因为这个页面的用途就是让人看清楚
 * 每一步到底在改什么，而不是给一堆看不懂的名词。
 */
class SettingsActivity : AppCompatActivity() {

    private val bg = 0xFF121212.toInt()
    private val cardBg = 0xFF1E1E1E.toInt()
    private val titleColor = 0xFFF2F2F2.toInt()
    private val descColor = 0xFF8A8A8A.toInt()
    private val accent = 0xFFE8A33D.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RawSettings.attach(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "RAW 处理链"
            textSize = 20f
            setTextColor(titleColor)
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "按实际处理顺序排列。全部关掉 = 只做减黑电平、归一化和 sRGB 编码，最接近传感器原始数据。"
            textSize = 12f
            setTextColor(descColor)
            setLineSpacing(0f, 1.4f)
            setPadding(0, 0, 0, dp(20))
        })

        section(root, "采集")
        info(root, "RAW_SENSOR 直读", "跳过 ISP 的去马赛克、降噪、锐化、色调曲线。DNG 与 JPG 是同一帧的两份产物。")
        switchItem(
            root, "HDR 多帧合成（HDR+）",
            "等曝光连拍 5 帧，四级金字塔分块对齐后在频域做 Wiener 融合（Hasinoff 2016）。" +
                "同曝光重复的帧能平均掉噪声，所以比单帧干净约 √5 倍；亮部靠欠曝换来的余量。" +
                "DNG 保存第一帧。",
            { RawSettings.hdr }, { RawSettings.hdr = it }
        )
        optionRow(
            root, "高光余量",
            listOf(
                0 to "自动 · 按场景决定（推荐）",
                1 to "不欠曝 · 最干净",
                2 to "欠 1 档",
                3 to "欠 1.5 档",
                4 to "欠 2 档 · 光比极大时"
            ),
            { RawSettings.hdrUnderexpose }, { RawSettings.hdrUnderexpose = it }
        )
        switchItem(
            root, "多帧超分（替代去马赛克）",
            "用连拍里手抖造成的**亚像素**位移，把 R/G/B 三个通道各自欠采样丢掉的信息补回来，" +
                "直接重建全 RGB，不再做去马赛克。整像素平均只能降噪、不提分辨率；" +
                "超分才真的把分辨率提上去（实测梯度能量 +29%，离线 PSNR +3.8 dB）。" +
                "代价：多花约 1.6 秒。",
            { RawSettings.superRes }, { RawSettings.superRes = it }
        )

        section(root, "解码")

        switchItem(
            root, "减黑电平",
            "传感器有个固定的底噪（这台机器 64 DN），不减的话暗部整体发灰、黑色不黑。",
            { RawSettings.blackLevel }, { RawSettings.blackLevel = it }
        )
        switchItem(
            root, "坏点修正",
            "CMOS 总有几百个像素比邻居亮，暗场能看见零星亮点。关掉能让画面保持绝对原始，代价是那些点还在。",
            { RawSettings.defectFix }, { RawSettings.defectFix = it }
        )
        switchItem(
            root, "暗角补偿",
            "这颗镜头四角比中心暗约 1.2 档（增益要补 2.4 倍）。关掉四角会明显压暗。",
            { RawSettings.shading }, { RawSettings.shading = it }
        )
        switchItem(
            root, "RCD 去马赛克",
            "开 = 方向自适应（RCD），边缘不出伪彩。关 = 双线性，更快但紫边绿边会变明显。",
            { RawSettings.rcd }, { RawSettings.rcd = it }
        )
        switchItem(
            root, "白平衡",
            "相机对光源色温的判断。关掉会保留传感器原生色偏：暖光下整体偏黄、日光下偏青。",
            { RawSettings.whiteBalance }, { RawSettings.whiteBalance = it }
        )
        switchItem(
            root, "3×3 色彩矩阵",
            "把传感器三通道映射到 sRGB。关掉画面会非常怪异（偏青绿、颜色错位）——因为传感器的响应和人眼差别很大。只建议用来对比诊断。",
            { RawSettings.colorMatrix }, { RawSettings.colorMatrix = it }
        )
        switchItem(
            root, "高光重建",
            "某个通道先到满量程时，白色高光会被白平衡增益染成粉紫或蓝。开 = 把这类像素恢复成中性白。",
            { RawSettings.highlightRecon }, { RawSettings.highlightRecon = it }
        )
        switchItem(
            root, "高光肩部",
            "超过线性 0.88 的部分平滑压向 1。关掉 = 超过满量程直接切成 255，高光一片死白、没有层次。",
            { RawSettings.shoulder }, { RawSettings.shoulder = it }
        )

        section(root, "HDR 收尾")
        info(
            root, "只在 HDR 开启时生效",
            "三帧合并后数据范围是单帧的 4 倍，不压缩放不进 8bit。这两步决定压完之后" +
                "「像不像 HDR」——单帧出片那条链不受影响（它跟 PS 的曲线对齐不能被改）。"
        )
        switchItem(
            root, "局部影调",
            "把画面拆成「大范围基底 + 局部细节」：只抬暗区的基底、细节全留。" +
                "关掉 = 全局硬压，暗部整段变灰、没有层次（之前那版就是这样）。",
            { RawSettings.hdrLocalContrast }, { RawSettings.hdrLocalContrast = it }
        )
        switchItem(
            root, "色度降噪",
            "只平滑 Cb/Cr，亮度逐位不动。HDR 提亮暗部会把 R/B 的噪声一起放大" +
                "（实测暗部色度 σ 是量化步长的 6~7 倍），不处理就是一片彩色颗粒。",
            { RawSettings.hdrChromaNr }, { RawSettings.hdrChromaNr = it }
        )
        strengthPicker(root)

        section(root, "色彩")
        switchItem(
            root, "色差校正",
            "横向色差的表现是亮度边缘冒出一条纯色镶边。这里不建镜头模型，" +
                "只在亮度梯度大的位置用 2 像素外的邻居色度中位数替换，平坦区一个像素都不碰。",
            { RawSettings.caFix }, { RawSettings.caFix = it }
        )
        switchItem(
            root, "色相微调（蓝天 / 绿植）",
            "把偏青偏紫的蓝推向透亮的蓝，并给蓝、绿加一点饱和度。" +
                "蓝天的问题通常不是不够蓝，而是蓝里混了品红。肤色和红色不在扇区里，不会被误伤。",
            { RawSettings.hueTune }, { RawSettings.hueTune = it }
        )

        section(root, "输出")
        formatPicker(root)
        qualitySlider(root)

        root.addView(TextView(this).apply {
            text = "恢复默认（全部开启，质量 95）"
            textSize = 14f
            setTextColor(accent)
            setPadding(0, dp(28), 0, dp(8))
            setOnClickListener {
                RawSettings.resetAll(this@SettingsActivity)
                recreate()
                Toast.makeText(this@SettingsActivity, "已恢复默认", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ---------------- 构建辅助 ----------------

    private fun section(parent: LinearLayout, name: String) {
        parent.addView(TextView(this).apply {
            text = name
            textSize = 12f
            setTextColor(accent)
            letterSpacing = 0.08f
            setPadding(0, dp(18), 0, dp(10))
        })
    }

    private fun info(parent: LinearLayout, title: String, desc: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cardBg)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(titleColor)
        })
        box.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(descColor)
            setLineSpacing(0f, 1.45f)
            setPadding(0, dp(6), 0, 0)
        })
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        parent.addView(box, lp)
    }

    private fun switchItem(
        parent: LinearLayout,
        title: String,
        desc: String,
        getter: () -> Boolean,
        setter: (Boolean) -> Unit
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cardBg)
            setPadding(dp(16), dp(14), dp(12), dp(14))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(titleColor)
        })
        texts.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(descColor)
            setLineSpacing(0f, 1.45f)
            setPadding(0, dp(6), dp(12), 0)
        })
        box.addView(texts)

        val sw = Switch(this).apply {
            isChecked = getter()
            setOnCheckedChangeListener { _, checked -> setter(checked) }
        }
        box.addView(sw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_VERTICAL })

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        parent.addView(box, lp)
    }

    /** 局部影调强度的三档选择（数值写进 RawSettings.hdrStrength，单位 %） */
    private fun strengthPicker(parent: LinearLayout) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cardBg)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        box.addView(TextView(this).apply {
            text = "局部影调强度"
            textSize = 14f
            setTextColor(titleColor)
        })
        box.addView(TextView(this).apply {
            text = "暗部拉开多少。自然档只把阴影提一点、保留当时的亮度关系；" +
                "强档暗部细节最多，但会比现场更亮，看得多、也更不像肉眼所见。"
            textSize = 12f
            setTextColor(descColor)
            setLineSpacing(0f, 1.45f)
            setPadding(0, dp(6), 0, dp(8))
        })
        val options = listOf(65 to "自然", 100 to "标准（推荐）", 135 to "强")
        for ((value, label) in options) {
            box.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(if (RawSettings.hdrStrength == value) accent else titleColor)
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener {
                    RawSettings.hdrStrength = value
                    recreate()
                }
            })
        }
        parent.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
    }

    /** 通用单选行：一组 (值, 名称)，点一下存进去并重建页面。 */
    private fun optionRow(
        parent: LinearLayout,
        title: String,
        options: List<Pair<Int, String>>,
        getter: () -> Int,
        setter: (Int) -> Unit
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cardBg)
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(titleColor)
        })
        val cur = getter()
        for ((value, label) in options) {
            box.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(if (cur == value) accent else titleColor)
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener {
                    setter(value)
                    recreate()
                }
            })
        }
        parent.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
    }

    private fun formatPicker(parent: LinearLayout) {        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cardBg)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        box.addView(TextView(this).apply {
            text = "输出格式"
            textSize = 14f
            setTextColor(titleColor)
        })
        box.addView(TextView(this).apply {
            text = "JPEG 4:2:0 的系统编码器把色度砍掉一半分辨率，彩色边缘会糊。4:4:4 是全分辨率色度，用自写编码器。PNG 无损但体积大。"
            textSize = 12f
            setTextColor(descColor)
            setLineSpacing(0f, 1.45f)
            setPadding(0, dp(6), 0, dp(10))
        })

        val options = listOf(
            RawSettings.FORMAT_JPEG444 to "JPEG 4:4:4 · 色度全分辨率（推荐）",
            RawSettings.FORMAT_JPEG420 to "JPEG 4:2:0 · 系统编码器，最快",
            RawSettings.FORMAT_PNG to "PNG · 无损 4:4:4，体积约 3 倍",
            RawSettings.FORMAT_WEBP to "WebP 无损 · 4:4:4，约 PNG 的 70%"
        )
        for ((value, label) in options) {
            box.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(if (RawSettings.format == value) accent else titleColor)
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener {
                    RawSettings.format = value
                    recreate()
                }
                tag = value
            })
        }

        if (RawSettings.format == RawSettings.FORMAT_PNG) {
            box.addView(TextView(this).apply {
                text = "PNG 是无损的，质量参数对它无效。"
                textSize = 11f
                setTextColor(descColor)
                setPadding(0, dp(8), 0, 0)
            })
        }
        if (RawSettings.format == RawSettings.FORMAT_HEIF) {
            // 旧版本存过 3 的机器会落到这里；读取时已经归一成 JPEG 4:4:4，这里只做提示
            box.addView(TextView(this).apply {
                text = "HEIF 已移除：系统编码器只给 4:2:0，且部分机型（含本机）根本不含 HeifWriter 类。已切回 4:4:4。"
                textSize = 11f
                setTextColor(descColor)
                setLineSpacing(0f, 1.4f)
                setPadding(0, dp(8), 0, 0)
            })
        }

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        parent.addView(box, lp)
    }

    private fun qualitySlider(parent: LinearLayout) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cardBg)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val label = TextView(this).apply {
            text = "JPEG 质量  ${RawSettings.jpegQuality}"
            textSize = 14f
            setTextColor(titleColor)
        }
        box.addView(label)
        box.addView(SeekBar(this).apply {
            max = 99
            progress = RawSettings.jpegQuality - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    label.text = "JPEG 质量  ${p + 1}"
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    RawSettings.jpegQuality = (sb?.progress ?: 94) + 1
                }
            })
        })
        box.addView(TextView(this).apply {
            text = "90 以上基本看不出差别，100 反而可能让文件变大且出现块状。"
            textSize = 11f
            setTextColor(descColor)
            setPadding(0, dp(6), 0, 0)
        })
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        parent.addView(box, lp)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
