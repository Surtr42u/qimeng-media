package media.qimeng.app.core.ui.component

import android.content.Context
import android.util.Log
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * U7 相册触摸死诊断桩（QM_TOUCH）——临时诊断代码，根因定位后整组撤除。
 *
 * ## 用途
 * 定位「相册 Tab 内容区触摸全死」（芯片点击/网格滚动零响应、截图像素级 0 diff、无涟漪；
 * 底部导航正常、首页/数据 tab 正常，U6/U7 包一致复现）在
 * **窗口 → Compose 根 → NavHost 壳 → 常驻层 → 相册根** 之间被吞的具体层。
 * 各层日志到齐与否 + consumed 标志即可二分断点。
 *
 * ## 声明
 * - [qmTouchProbe] **只观察不消费**：绝不 consume 任何 PointerInputChange，不改变事件
 *   分发语义（不拦截、不吞、不下毒）；
 * - 命中测试侧效应（如实声明）：pointerInput 节点具备命中测试能力。布点全部选在
 *   「既有命中链的祖先」或「本就命中可测的同节点链」上（如壳层 NAVHOST_BOX 包的是
 *   NavHost+常驻层公共祖先而非 NavHost 单体——包单体会在常驻层之上新增全尺寸命中
 *   参与者、遮蔽真实 Tab 屏=改变归属），不新增遮蔽兄弟的独立命中参与者。
 *
 * ## 撤除方式
 * `grep -r QM_TOUCH` 命中的整组代码全部删除：本文件 + 各布点处
 * `// U7 触摸诊断桩 QM_TOUCH（根因定位后撤除）` … `// U7 诊断桩结束` 注释对及其间语句。
 *
 * ## 日志
 * `adb logcat -s QM_TOUCH`，同时落盘 `/sdcard/Android/data/media.qimeng.app/files/qm_touch.log`
 * （`adb pull` 取，2MB 滚动）。层名一览：
 * MAIN_TOUCH / APP_LIFECYCLE / COMPOSE_ROOT / NAVHOST_BOX / RESIDENT_BOX /
 * CURTAIN / CURTAIN_TOUCH / SHELL / ALBUM_ROOT / HOME_ROOT / STATS_ROOT /
 * SETTINGS_ROOT / ALBUM_GRID_AREA / ALBUM_CHIP / ALBUM_STATE / ALBUM_VM / PILL_CLICK。
 */
object QmTouchProbe {

    /** logcat 过滤 tag：`adb logcat -s QM_TOUCH` */
    const val TAG = "QM_TOUCH"

    /**
     * 落盘文件（U7 诊断桩：间歇性故障复现时不一定连着 USB，logcat 缓冲会滚动丢日志——
     * 同步写一份到应用外部专属目录，事后 `adb pull` 即可）：
     * `/sdcard/Android/data/media.qimeng.app/files/qm_touch.log`（滚动档 .1，单文件 2MB 上限）。
     */
    @Volatile private var logFile: File? = null
    private val fileLock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** MainActivity.onCreate 调用一次；拿不到目录则静默降级为纯 logcat 模式 */
    fun initFileLog(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, "qm_touch.log").let {
                logFile = it
                log("INIT", "file-log ready: ${it.absolutePath}")
            }
        } catch (_: Throwable) {
            logFile = null
        }
    }

    /** 绝不抛：logcat 未就绪、JVM 单测（android.util.Log 未 mock 抛 RuntimeException）等一律吞掉 */
    fun log(layer: String, msg: String) {
        val line = "${timeFormat.format(Date())} [$layer] $msg"
        try {
            Log.i(TAG, line)
        } catch (_: Throwable) {
        }
        appendFile(line)
    }

    private fun appendFile(line: String) {
        val file = logFile ?: return
        try {
            synchronized(fileLock) {
                if (file.length() > FILE_ROTATE_BYTES) {
                    val older = File(file.parentFile, "qm_touch.1.log")
                    older.delete()
                    file.renameTo(older)
                }
                file.appendText(line + "\n")
            }
        } catch (_: Throwable) {
        }
    }

    private const val FILE_ROTATE_BYTES = 2_000_000L
}

/**
 * 观察型触摸探针：逐事件（按下/移动/抬起/滚动）记录类型、坐标、指针数、按压数与
 * 消费态（consumed=true=已被更深/更早的节点消费），只打日志不 consume。
 *
 * 事件循环=awaitPointerEventScope 内裸 while(true)+awaitPointerEvent（与 foundation
 * awaitEachEvent 逐事件等价；探针只观察无手势收尾状态需清理，取消随 pointerInput
 * 协程天然终止）。
 */
fun Modifier.qmTouchProbe(layer: String): Modifier = this.pointerInput(layer) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            QmTouchProbe.log(layer, describePointerEvent(event))
        }
    }
}

/** 事件描述串：纯读取观察字段，不改事件（描述函数单测无关，临时诊断代码从简） */
private fun describePointerEvent(event: PointerEvent): String {
    val changes = event.changes
    val first = changes.firstOrNull()
    val pos = first?.position
    return "type=${eventTypeName(event.type)}" +
        " pos=(${pos?.x}, ${pos?.y})" +
        " pointers=${changes.size}" +
        " pressed=${changes.count { it.pressed }}" +
        " consumed=${changes.any { it.isConsumed }}"
}

private fun eventTypeName(type: PointerEventType): String = when (type) {
    PointerEventType.Press -> "Press"
    PointerEventType.Release -> "Release"
    PointerEventType.Move -> "Move"
    PointerEventType.Scroll -> "Scroll"
    else -> "Other"
}
