package media.qimeng.app.feature.detail

import java.util.concurrent.ConcurrentHashMap
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
 * 线程安全：读发生在主线程（schedulePreload），写可能来自并发详情请求，统一 ConcurrentHashMap。
 */
@Singleton
class DetailImageDimCache @Inject constructor() {

    private val dims = ConcurrentHashMap<String, ImageDims>()

    /** 查询已知尺寸（未拉过详情返回 null → 策略按普通图处理） */
    fun dimsOf(id: String): ImageDims? = dims[id]

    /** 详情拉取成功后登记宽高（宽高非法即不记，保持「未知」语义） */
    fun put(id: String, value: ImageDims) {
        dims[id] = value
    }
}
