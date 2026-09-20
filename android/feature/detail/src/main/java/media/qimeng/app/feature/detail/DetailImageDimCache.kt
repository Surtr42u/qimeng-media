package media.qimeng.app.feature.detail

import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 详情侧「已知图片尺寸」进程级缓存（id → 宽高，拍板③预载窗口策略的 sizeOf 数据源）。
 *
 * 为什么是进程级单例而不是 VM 内 Map：兄弟资产左右滑 = push 新栈（QimengNavHost 冻结口径，
 * 叠栈即浏览历史），每屏一个 DetailViewModel——单 VM 内的 Map 换屏即丢，超大图判定
 * （长边>4096 限量预载）在连续滑动场景会退化为「尺寸永远未知」。列表批次只传 id
 * （拍板④），尺寸要等详情拉取才知道，因此谁拉过详情谁把宽高写进来（当前资产、窗口内
 * 邻位资产都写），后续屏的策略即可基于已知尺寸收紧。
 *
 * 有界性（审计 R13，2026-09-20）：原 ConcurrentHashMap 无上限——进程级单例
 * 随长会话无限增长。改访问序 LRU 上限 [MAX_ENTRIES]（远大于预载窗口与连续
 * 滑动的活跃集，命中语义不变；超出只丢最老尺寸记录，代价是那条资产下次
 * 按未知尺寸走普通图策略，无正确性影响）。不用 android.util.LruCache：
 * JVM 单测（DetailViewModelTest 直构本类）避开 android.jar stub。
 *
 * 线程安全：读发生在主线程（schedulePreload），写可能来自并发详情请求；
 * accessOrder 模式下 get 也改结构，读写统一 synchronized。
 */
@Singleton
class DetailImageDimCache @Inject constructor() {

    private val dims = object : LinkedHashMap<String, ImageDims>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageDims>): Boolean =
            size > MAX_ENTRIES
    }

    /** 查询已知尺寸（未拉过详情返回 null → 策略按普通图处理） */
    fun dimsOf(id: String): ImageDims? = synchronized(dims) { dims[id] }

    /** 详情拉取成功后登记宽高（宽高非法即不记，保持「未知」语义） */
    fun put(id: String, value: ImageDims) {
        synchronized(dims) { dims.put(id, value) }
    }

    private companion object {
        /** 缓存上限：条目体积极小（id + 两 int），512 ≈ 数百屏滑动活跃集的宽裕上界 */
        const val MAX_ENTRIES = 512
    }
}
