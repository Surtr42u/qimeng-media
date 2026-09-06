package media.qimeng.app.feature.detail

/**
 * 详情页图片预载窗口策略（拍板③，纯函数零 IO——JVM 单测锁定行为，见 DetailPreloadPolicyTest）。
 *
 * 口径来源 = 旧版 QimengMedia MediaDetailFragment.preloadAround（2026-09-07 冻结简化）：
 * - 窗口 = 前 1 后 2（不含当前）；批次外越界自然为空，不环绕；
 * - **越界加载策略**：只对已加载批次内 id 预取（批次来源=列表页内存传递，拍板④），
 *   不主动拉取批次外资产——列表没加载到的资产不产生隐式详情请求；
 * - 已知尺寸长边 > 4096 的「超大图」窗口内最多预载 1 张（距当前最近优先）——8192² 原图
 *   解码后 ≈150MB/张，多张同存易 OOM；
 * - heapUsedRatio ≥ 0.6 视为内存紧张：跳过全部超大图（普通图照预）；
 * - 宽高未知（该资产尚未拉过详情）按普通图处理。
 */
object DetailPreloadPolicy {

    /** 窗口向前张数（旧版 preloadAround 基础档 backCount=1） */
    const val PRELOAD_BACK = 1

    /** 窗口向后张数（旧版 preloadAround 基础档 forwardCount=2） */
    const val PRELOAD_FORWARD = 2

    /**
     * 超大图判定阈值：长边 > 4096px（旧版 HUGE_IMAGE_THRESHOLD 同值；与 ZoomImageView 的
     * HARDWARE_RENDER_SAFE_SIZE=4096 数值对齐但职责独立——那边管渲染层选择，这边管预载限量）。
     */
    const val HUGE_IMAGE_LONG_SIDE = 4096

    /** 窗口内超大图最多预载张数（旧版 MAX_PRELOAD_HUGE=1） */
    const val MAX_HUGE_PRELOAD = 1

    /** 堆占用比例达到此值视为内存紧张：跳过全部超大图预载（旧版 isMemoryComfortable 收紧口径） */
    const val HEAP_PRESSURE_RATIO = 0.6f

    /**
     * 计算应预载的资产 id 列表（按距当前距离升序：+1、-1、+2）。
     *
     * @param currentIndex 当前资产在 [ids] 中的序号（0 基；不在批次内返回空表）
     * @param ids 已加载批次清单（拍板④内存传递）
     * @param sizeOf 已知图片尺寸查询（宽高未知返回 null → 按普通图处理）
     * @param heapUsedRatio 当前堆占用比例（0~1；≥[HEAP_PRESSURE_RATIO] 跳过全部超大图）
     * @return 预载目标 id 列表（不含当前资产；不含批次外 id；不环绕）
     */
    fun computePreload(
        currentIndex: Int,
        ids: List<String>,
        sizeOf: (id: String) -> ImageDims?,
        heapUsedRatio: Float,
    ): List<String> {
        if (currentIndex < 0 || currentIndex >= ids.size) return emptyList()
        // 距当前最近优先的遍历序：+1、-1、然后更远的向前档（窗口 1前2下 冻结为 [1,-1,2]）
        val orderedDeltas = listOf(1, -1) + (2..PRELOAD_FORWARD).map { it }
        val result = ArrayList<String>(orderedDeltas.size)
        var hugeEnqueued = 0
        for (delta in orderedDeltas) {
            val index = currentIndex + delta
            if (index < 0 || index >= ids.size) continue // 越界窗口自然为空，不环绕
            val id = ids[index]
            if (id == ids[currentIndex]) continue // 防御：批次含重复 id 时跳过自身
            val dims = sizeOf(id)
            val isHuge = dims != null && maxOf(dims.width, dims.height) > HUGE_IMAGE_LONG_SIDE
            if (isHuge) {
                if (heapUsedRatio >= HEAP_PRESSURE_RATIO) continue // 内存紧张：超大图全跳过
                if (hugeEnqueued >= MAX_HUGE_PRELOAD) continue     // 窗口内超大图限量 1 张
                hugeEnqueued++
            }
            result += id
        }
        return result
    }
}

/** 已知图片尺寸（像素）；预载策略与尺寸缓存共享的最小结构 */
data class ImageDims(val width: Int, val height: Int)
