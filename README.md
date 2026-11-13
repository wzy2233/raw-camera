# Camera — 一个自己写 RAW 管线的 Android 相机

> 不用 CameraX 的 Imaging，也不依赖厂商 ISP。从 Bayer RAW 开始，自己做对齐、合并、去马赛克、色调、编码，一直到落盘。
> A from-scratch RAW camera pipeline for Android. Bayer in, JPEG/HEIF/PNG out — align, merge, demosaic, tone-map and encode all written by hand in Kotlin + C++.

**关键词 / Keywords**：`android` `camera2` `raw` `dng` `hdr` `burst-photography` `computational-photography` `image-processing` `demosaic` `super-resolution` `exposure-fusion` `tone-mapping` `jpeg-encoder` `ndk` `cpp` `kotlin` `no-ml` `handheld`

---

## 效果

| 夜景（手持，全像素 RAW 管线输出） | 100% 裁切：木条与树皮 |
|---|---|
| ![](docs/screenshots/night-full.jpg) | ![](docs/screenshots/night-crop.jpg) |

> 这一张是刻意选的**难场景**：夜间路灯下，画面均亮度只有 34/255。木条的边缘、树皮的纹理、地面的落叶都还在——这是多帧合并 + 全程 10-bit 的直接结果。

---

## 为什么写这个

手机原厂相机的成像基本被厂商 ISP 黑箱化：你能拿到 JPEG，但拿不到它怎么来的。这个项目把中间每一步都摊开——**每一行都是可读、可调、可验证的**，而不是"调个参数看看效果"。

代价是性能。所以这个项目里另一个同等重要的主题是：**把每个慢的地方量出来，再决定怎么优化**。README 里所有性能数字都是实测的，来源在注释里。

## 功能

**拍摄**
- 全像素 RAW 连拍（3~8 帧，按 ISO / 曝光时长 / 是否超分自动选择）
- 单帧 RAW 与多帧 HDR 两种模式
- 输出 DNG（含厂商 ForwardMatrix）+ 自行解码的 JPEG

**输出格式**
- JPEG 4:4:4（**自研编码器**，非 libjpeg）
- HEIF 10-bit
- PNG 16-bit

**成像处理**
- 坏点修复（同色邻居中值判据）
- 镜头阴影校正（用 ISP 的 shading map + 多项式拟合）
- 白平衡（DNG AsShotNeutral + 可选 log-chroma 重估）
- 去马赛克：RCD（Ratio Corrected Demosaicing）或双线性
- 多帧对齐：由粗到细的金字塔块匹配 + L1 精对齐
- 多帧合并：噪声归一化的鲁棒权重（剔除运动 / 鬼影）
- 多帧超分（handheld super-resolution，亚像素相位重建）
- 色度降噪（HDR+ 风格，含"幅值守卫"防色块注入）
- 动态范围压缩：**灰度双合成曝光 + 拉普拉斯金字塔融合**
- 去雾（按 0.1% 分位定黑点，只压 7% 白点以下）
- 局部影调 + 多尺度锐化 + **halo 抑制**（Polyblur 闭式解）
- 色差校正、色相微调
- 全程 10-bit，**只在最后量化一次**；量化用蓝噪声抖动

**标定**
- 色卡自动检测与 CCM 求解
- 三层颜色链：GLSL 实时预览 / 3D LUT / 矩阵近似（按 API 与性能降级）
- 暗部中性化、滤镜系统

## 特色

**1. 全程 10-bit，只量化一次**

大多数管线在每个阶段结束都回落到 8-bit。这个项目把 10-bit 一直保到最后的量化步骤。实测依据：一条 6..255 的渐变，本该用 250 级，若中途量化只剩 145 级，最大跳变 3 码。

**2. HDR 的动态范围压缩用曝光融合，不是增益图乘法**

按 HDR+ 论文 §6 第 7 步：从线性图派生两个合成曝光，在**灰度**上做拉普拉斯金字塔融合（权重是亮度 LUT），再用**色度比值**回填颜色。这样"提亮暗部"和"保住局部对比"是同一件事。

**3. 自研 JPEG 4:4:4 编码器（native）**

标准 libjpeg 默认 4:2:0 会丢一半色度。为了保住 4:4:4 又不想慢，自己写了编码器：分离式 DCT + Annex K 霍夫曼表 + RST 分界，12MP q95 **118 ms**（Kotlin 版是 1083 ms）。

**4. 端到端 4.7 秒（12MP / 8 帧 / q95）**

| 阶段 | 耗时 |
|---|---|
| 打开相机 + AE/AF 收敛 | 1.30 s |
| 8 帧 RAW 采集 | 0.24 s |
| 对齐 + 合并 | 0.80 s |
| 渲染（去马赛克 → DRC → 色调） | 0.60 s |
| 收尾（色度降噪/锐化/抖动/量化） | 0.99 s |
| JPEG 编码 | 0.11 s |

## 技术架构

```
Camera2 ──► RAW ImageReader (4096×3072, 10/12-bit)
             │
             ├─ 坏点修复          raw_defect.cpp
             ├─ 阴影校正 / 白平衡  OpticsCorrector.kt
             ├─ 对齐（金字塔块匹配）hdr_align.cpp
             ├─ 合并（噪声归一权重）hdr_merge.cpp
             │
             ├─ 去马赛克 / 超分     hdr_render.cpp · hdr_superres.cpp
             ├─ 动态范围压缩        hdr_drc.cpp
             ├─ 色调曲线            hdr_common.h
             ├─ 收尾（降噪/锐化/抖动）hdr_finish.cpp
             │
             └─► JPEG 4:4:4  jpeg444.cpp
                 HEIF 10-bit heif_encode.cpp
                 PNG 16-bit  png16_encode.cpp
```

- **Kotlin**（~13k 行，32 个文件）：相机控制、UI、标定、文件与元数据
- **C++ / NDK**（~6k 行，11 个模块）：所有逐像素运算
- 两者之间只有一个 JNI 边界（`NativeHdr`），整数 / 浮点数组进出，无对象

## 构建

```bash
# 需要 NDK（脚本里写死了本机路径，请按需修改）
bash app/src/main/cpp/build_native.sh

# 或用 Gradle（注意：本仓库的 native 构建走上面的脚本，产物在 src/main/jniLibs）
./gradlew assembleDebug
```

**环境**：compileSdk 34 / minSdk 28 / arm64-v8a + armeabi-v7a

依赖只有 CameraX、Kotlin 协程、Glide。**没有 TensorFlow / NNAPI / 任何 ML 运行时**——所有算法都是手写的。

## 已知限制

诚实地列出来，因为它们是真实存在且已知原因的：

- **主要在荣耀 Magic6 Pro 上验证**。其他机型的 HAL 行为（尤其连拍流、3A 状态上报）可能不同。
- **HDR 的暗部细节目前不如单帧 RAW**。实测：整幅高频比单帧高 1.4~2.2 倍，但暗部 3~10px 尺度只有 0.5 倍。原因已排除融合权重与 DRC 分辨率，怀疑在色调管线，待继续定位。
- **"相机常开"未完成**：实测该 HAL 在长曝光连拍后会停止输出，换 session 也不行，需要重建 device。相关代码在 `RawCaptureController` 的 `persistent` 分支，默认关闭。
- 户外高对比场景 `drcK` 曾到 4.0 导致整体过亮，已加亮度上限约束，待更多样张验证。
- 没有防抖、没有人像模式、没有视频。

## 参考

- Hasinoff et al., *Burst photography for high dynamic range and low-light imaging on mobile cameras*, SIGGRAPH Asia 2016
- Wronski et al., *Handheld Multi-Frame Super-Resolution*, SIGGRAPH 2019
- Liba et al., *Handheld Mobile Photography in Very Low Light*, SIGGRAPH Asia 2019
- Barron & Tsai, *Fast Fourier Color Constancy*, CVPR 2017
- Delbracio et al., *Polyblur: Removing mild blur by polynomial reblurring*, 2021

## License

MIT（见 `LICENSE`）。
