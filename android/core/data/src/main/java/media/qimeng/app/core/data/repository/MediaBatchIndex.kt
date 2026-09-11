package media.qimeng.app.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 列表页 → 详情页传「已加载资产 ID 列表」的内存单点（拍板④：Web 端同款内存传递；
 * 不进 SavedStateHandle 防超大列表触发 TransactionTooLarge）。
 *
 * 语义 = 「已加载 = 当前显示清单」：列表页（首页各 tab/收藏/历史等）进详情前写入当前
 * 显示项，详情页兄弟滑动换件沿同一清单取邻位（任务W W3 起「接下来播放」跳转换批链
 * 退役，清单只写不改）。
 * 无批次上下文（如冷启动直进详情）时 ids 为空表，详情页序号区不显示。
 *
 * 为什么不是 Repository 接口：纯进程内状态（无出网/无持久化），单例可变状态即全部职责；
 * 构造注入无需 @Binds。
 */
@Singleton
class MediaBatchIndex @Inject constructor() {

    /** 当前批次清单（快照式整体替换；volatile 保证跨线程读写可见） */
    @Volatile
    var ids: List<String> = emptyList()

    /** 资产在当前批次中的序号（0 基；不在批次内返回 -1，调用方据此隐藏序号） */
    fun indexOf(id: String): Int = ids.indexOf(id)

    /** 当前批次大小（序号展示「i / N」的 N；空批次 = 0） */
    fun size(): Int = ids.size

    /**
     * 批次内邻位解析（拍板③：详情页左右滑切换相邻资产——3b 手势接线的数据基座，本批只供逻辑层）。
     * @param index 当前资产序号（[indexOf] 的结果，0 基）
     * @param delta 偏移量（+1 下一张 / -1 上一张，可任意幅度）
     * @return 目标资产 id；index 不在批次内（<0）或目标越界（滑过首/尾）返回 null，
     *         调用方据此不动（Web 端滑到头不环绕的同语义），空批次恒 null。
     */
    fun assetIdAt(index: Int, delta: Int): String? {
        if (index < 0 || index >= ids.size) return null
        val target = index + delta
        if (target < 0 || target >= ids.size) return null
        return ids[target]
    }
}
