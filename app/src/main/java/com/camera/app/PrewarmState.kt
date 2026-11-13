package com.camera.app

/**
 * CameraX 预览里已经收敛的曝光/对焦状态，供 RAW 拍照直接复用。
 *
 * ---- 为什么需要它 ----
 * 现在按快门要走这条路：让出 CameraX -> 打开 Camera2 设备(223ms) -> 建会话(187ms)
 * -> **等 AE/AF 收敛(683ms)** -> 连拍。
 *
 * 但那 683ms 是纯白等：CameraX 的预览从进入相机界面起就一直在测光和对焦，
 * 场景曝光早就知道了。我们却在 Camera2 里从零重新跑一遍收敛回路。
 *
 * 用 `Camera2Interop.Extender.setSessionCaptureCallback` 可以旁路拿到 CameraX
 * 预览的 TotalCaptureResult（只读，不影响 CameraX），于是可以在打开 RAW 会话的
 * 第一帧就把曝光/ISO/焦距锁成"预览已经确认过的值"，把 683ms 压到几十毫秒。
 *
 * ---- 安全策略 ----
 * 只在"AE 已收敛 + 数据是 1.2 秒内的 + 曝光/ISO 合理"时才采用，否则完全退回原来的
 * 等收敛逻辑。宁可慢一点，也不能拍出一张曝光错误的照片。
 */
object PrewarmState {

    class Snapshot(
        val exposureNs: Long,
        val iso: Int,
        /** 屈光度；null 表示这次不可锁焦（CONTINUOUS_PICTURE 下没给出距离） */
        val focusDiopters: Float?,
        val atMs: Long
    )

    @Volatile
    var snapshot: Snapshot? = null

    /** 超过这个时间就认为场景可能已经变了 */
    private const val MAX_AGE_MS = 1200L

    fun record(exposureNs: Long, iso: Int, focusDiopters: Float?) {
        if (exposureNs <= 0L || iso <= 0) return
        snapshot = Snapshot(exposureNs, iso, focusDiopters, System.currentTimeMillis())
    }

    /** 取一份可用的快照；不可用返回 null（调用方退回"等收敛"） */
    fun take(): Snapshot? {
        val s = snapshot ?: return null
        if (System.currentTimeMillis() - s.atMs > MAX_AGE_MS) return null
        if (s.exposureNs <= 0L || s.iso <= 0) return null
        return s
    }

    fun clear() {
        snapshot = null
    }
}
