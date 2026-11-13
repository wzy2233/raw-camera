# 技术渊源与文献映射

这份文档回答一个问题：**这个项目里的每一段算法，是从哪来的、改了什么、为什么这么改。**

比"参考了以下文献"这种列表有用得多——后者谁都会写，但读者无法判断你到底用了多少。

---

## 一、采用并实现的

### 1. HDR+：Burst photography for HDR and low-light imaging on mobile cameras
Hasinoff et al., SIGGRAPH Asia 2016 · ACM TOG 35(6), 论文编号 145

这是整个项目的骨架。论文的 13 步管线我逐步对照实现过，代码分布如下：

| 论文步骤 | 本项目实现 | 说明 |
|---|---|---|
| 1–3 黑电平 / 阴影校正 / 白平衡 | `OpticsCorrector.kt` | 阴影用 ISP 的 shading map + 多项式拟合 |
| 4 去马赛克 | `hdr_render.cpp`（RCD）、`RcdDemosaic.kt` | 用 RCD 而非论文的 Gunturk 组合法 |
| 5 色度降噪 | `hdr_finish.cpp` | 3×3 双边两遍，含"幅值守卫" |
| 6 色彩矩阵 | `hdr_render.cpp` | 3×3，来自 DNG 的 ForwardMatrix 或色卡标定 |
| **7 动态范围压缩** | `hdr_drc.cpp` | **灰度双合成曝光 + 拉普拉斯金字塔融合**，见下文 |
| 8 去雾 | `hdr_render.cpp` | 0.1% 分位定黑点，只压 7% 白点以下 |
| 9 全局色调 | `hdr_common.h`（`psCurve`） | S 曲线 ⊗ sRGB gamma |
| 10 色差校正 | `hdr_finish.cpp` | 沿强边缘替换色度 |
| 11 锐化 | `hdr_finish.cpp` | 4 层拉普拉斯金字塔，比论文的 3 层多一层 |
| 12 色相微调 | `hdr_finish.cpp` | 四阶傅里叶级数塑造色相曲线 |
| 13 抖动 | `hdr_finish.cpp` | 蓝噪声（IGN），非论文的预计算表 |

**第 7 步是我们最在意的一步**。论文原话是：

> we instead boost shadows, **preserving local contrast** while judiciously sacrificing global contrast

做法：从 HDR 中间图派生两个合成曝光（短/长），**只在灰度上**做拉普拉斯金字塔融合，权重是一条固定的亮度 LUT，融合完用**色度比值**回填颜色。这样"提亮暗部"和"保住局部对比"是同一件事，而不是先提亮再想办法补回来。

**先进性在哪**：绝大多数手机管线用"低分辨率增益图乘法"来做动态范围压缩。乘性增益只改变亮度标量，暗部细节完全依赖后面的曲线和锐化去捞。金字塔融合在每一层都挑"曝光最好的那张"的细节，局部对比是被**显式保住**的。

**我们踩过的坑**：一度把融合结果压到 1/8 分辨率再当增益图用（注释里还写着"局部影调的增益本来就是低频的"）。实测 A/B：分辨率从 1/8 提到 1/2，暗部高频占比只从 0.0304 变成 0.0307（比值 1.01），渲染却从 529ms 涨到 946ms——**证明这个方向不是瓶颈**，也说明当初那个"低频"的假设虽然结论没错，理由是想当然了。

### 2. HDR+ 补充材料：Brute-force L1 alignment
Hasinoff et al., 2016 Supplement

金字塔块匹配的最细层用暴力 L1 搜索。我们的实现：粗层用块匹配估计位移场，细层用 L1 在 ±2px 内精修。全部在 3MP 灰度层做，`hdr_align.cpp`。

### 3. Handheld Multi-Frame Super-Resolution
Wronski et al., SIGGRAPH 2019 · ACM TOG 38(4)

实现于 `hdr_superres.cpp`。核心思想：**不要先合并再插值，而是把多帧的亚像素相位直接当作采样点来重建**，从而绕过去马赛克，直接得到全分辨率 RGB。

我们取的是它的"三角核加权 + 逐通道相位覆盖"部分，没有做论文里的鲁棒性核（Kernel）回归。可用性开关是 `RawSettings.superRes`。

### 4. Night Sight：Handheld Mobile Photography in Very Low Light
Liba et al., SIGGRAPH Asia 2019 · ACM TOG 38(6)

对我们影响最大的不是某个算法，而是**失效模式清单**。论文明确列出的几条，我都在代码注释里对应标了：

- AWB 在极低光下失效 → 我们优先用 DNG 的 `AsShotNeutral`，不再二次估计（`wbRefine` 默认关闭，见下）
- 局部影调会把暗部噪声一起放大 → 这是我们加 `darkBoost` 限幅与"幅值守卫"的原因
- 帧数应按信噪比需求而非固定值 → `chooseBurstCount` 按 ISO 分档（4/6/8 帧），并用"总采集时长 ≤ 0.9s"做上限，因为手持抖动在 1 秒量级开始累积

### 5. Polyblur：Removing mild blur by polynomial reblurring
Delbracio et al., 2021 · [arXiv:2012.09322](https://arxiv.org/abs/2012.09322)

**只采用了它的 halo 抑制部分**（`haloGuard`，在 `hdr_finish.cpp` 锐化之后）。公式是论文式 15–17 的闭式解：

```
M(x) = −∇v(x)·∇v̄(x)            // 梯度反转量
z(x) = M / (|∇v|² + M)          // 退回原图的权重
out  = z·v + (1−z)·v̄
```

逐像素、无搜索、无迭代，实测 12MP 上 **26~30ms**。

**为什么单独挑这一段**：其余部分（多项式反卷积）依赖一个**全局**的各向同性高斯模糊模型。我们的模糊是空间可变的（暗部比亮部重），而且它用"全图最大梯度"估 σ，在户外会被天空边缘带偏——照搬会变成"用亮部的模糊程度去校全幅"。

**先进性在哪**：它给出了"过锐"的数学判据，而不是靠阈值或启发式。只要梯度方向反转，就按比例退回；没有反转的地方完全不动。这比"降低锐化强度"精确得多。

### 6. FFCC：Fast Fourier Color Constancy
Barron & Tsai, CVPR 2017（本项目 `wbRefine` 的实现依据）

曾实现白平衡重估（`wbRefine`），**默认关闭**。原因写在代码注释里：白纸 A/B 显示 B 通道改善 2.5 个百分点，但 R 通道被改反了（真实需要 −2%，算法给了 −9.7%，大了 4.6 倍）。ISP 本身只偏 2~3%，已在肉眼不可辨范围，不值得用一个不可靠的估计去动它。

保留代码、默认关闭，等根因定位。

---

## 二、参考了思路但没照搬的

### HDRnet：Deep Bilateral Learning for Real-Time Image Enhancement
Gharbi et al., SIGGRAPH 2017

**没有采用**（没有 ML 运行时），但它的**分层降级**思路直接影响了我门预览颜色链的设计：GLSL 着色器（API 31+）→ 3D LUT → 颜色矩阵，按设备能力逐级降级，而不是"要么全效果、要么没效果"。

`PreviewPipeline.kt` / `LUTProcessor.kt` / `TetrahedralLUTFilter.kt` 三个文件对应这三档。

### Unprocessing Images for Learned Raw Denoising
Brooks et al., CVPR 2019

**没有采用**。它最有用的是把 ISP 逆过程形式化（去马赛克逆、去 gamma、去色调），这对**造训练数据**是必需的。我们不训练网络，所以用不上；但它"每个 ISP 步骤都可逆"的视角帮我理清了管线里哪些操作破坏了线性性（也是我们坚持 10-bit 到最后一刻的原因之一）。

---

## 三、明确不采用的

| 论文 | 不用的原因 |
|---|---|
| KPN 2018（Burst Denoising with Kernel Prediction Networks） | 需训练与推理框架 |
| [BIPNet 2021](https://arxiv.org/abs/2110.03680)（Burst Image Restoration） | 同上 |
| [MUSIQ 2021](https://arxiv.org/abs/2108.05997)（无参考画质评分） | 同上；但我们的"土指标"确实不如它，见下 |
| [Portrait Shadow Manipulation 2020](https://arxiv.org/abs/2005.08925) | 需训练。但它的输出形式 `I_out = I_in ∘ A + B` 提醒了一件事：我们只有 A（乘性），B 恒为 0，所以暗部提亮必然等比放噪 |
| [Learning to Autofocus 2020](https://arxiv.org/abs/2004.12260) | 需训练。但它对"何时不该信 AF"的分析帮助我定位了一个真 bug：把 `PASSIVE_UNFOCUSED`（AF 主动放弃）当成了对焦就绪 |
| [Dual-Pixel Depth 2019](https://arxiv.org/abs/1904.05822) / [Defocus Deblur 2020](https://arxiv.org/abs/2012.03255) | 需要 dual-pixel 数据，Camera2 的 RAW 输出拿不到 |
| [RAISR 2016](https://arxiv.org/abs/1606.01299)（单图超分，本仓库 `1606.01299v3.pdf`） | 我们走多帧超分，方向不同 |
| [Long Exposure 2023](https://arxiv.org/abs/2308.01379) | 不在当前目标内 |
| Portrait Relighting 2019 | 不在当前目标内 |

---

## 四、关于"为什么用谷歌的算法"

三个具体理由，不是因为它有名：

**1. 它们是少数把商业系统完整写出来的文献。**
学术界大量论文只写算法、不写系统；相机行业则相反，系统在但算法是黑箱。HDR+ 那篇把 13 步逐步列出并给了参数，这在领域里是罕见的。

**2. 它们关心的是同一组约束。**
手机、手持、有限算力、要出 JPEG。不是"在数据集上刷分"，而是"在一台 4W 的手机上 1 秒内出片"。这个约束组合与我们的处境一致。

**3. 它们会写失败模式。**
Night Sight 专门有一节讲"什么时候会失效"，HDR+ 的补充材料里有"我们的结果里差的那几张"。对我们这种要跑真实场景的项目，失效清单比正面结果有用。

## 五、延伸阅读

- **HDR+ 数据集**：<https://hdrplusdata.org/> —— Google 公开的 HDR+ 连拍原始数据，含"未处理"与"参考输出"配对，适合验证自己的合并算法。
- **本仓库的文献索引依据**：下列 12 篇的 arXiv 编号直接取自文件名，可直接替换成 `https://arxiv.org/abs/<编号>` 访问：
  `1507.00410`（Convolutional Color Constancy）、`1606.01299`（RAISR）、`1904.05822`（Dual-Pixel Depth）、`1905.00824`（Portrait Relighting）、`2004.12260`（Learning to Autofocus）、`2005.08925`（Portrait Shadow）、`2012.03255`（DP Defocus Deblur）、`2012.09322`（Polyblur）、`2102.09000`（Mobile Comp. Photo: A Tour）、`2108.05997`（MUSIQ）、`2110.03680`（BIPNet）、`2308.01379`（Long Exposure）。
- 其余几篇（HDR+ / 补充材料 / Night Sight / Super-Res / HDRnet / Portrait Mode / FFCC / KPN / Unprocessing）为 ACM 或 CVPR 正式出版物，按标题检索即可；本仓库不转载原文。

> 说明：本仓库**不收录论文 PDF 与整篇翻译**。一是体积（原始 PDF 约 500MB），二是版权。请通过上面的正式渠道获取。

## 六、我们做得比论文差的地方

诚实记录，不粉饰：

- **没有可比的量化评价**。论文用 PSNR 和人类主观评测；我们只有零散的统计指标（暗部高频占比、梯度能量），而且这些指标会被色调曲线污染。曾用 `std/mean` 做对比，后来发现它把"提亮"也算成了"模糊"，结论一度是错的。
- **没有多机型验证**。只在荣耀 Magic6 Pro 上测过。
- **超分没有做核回归**。论文的鲁棒性来自逐像素核，我们用的是固定三角核，在运动边缘会弱一些。
