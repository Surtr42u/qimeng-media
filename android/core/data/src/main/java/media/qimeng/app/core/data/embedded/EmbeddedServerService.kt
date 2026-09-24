package media.qimeng.app.core.data.embedded

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.qimeng.app.core.network.ServerAddress

/**
 * 内嵌服务端前台 Service（任务T T6 / 任务U11 批次D，ADR-0015 形态 B；Kotlin 全新
 * 编写，未搬旧项目任何实现）。
 *
 * 职责=三件事：常驻前台通知、拉起并看护服务端子进程、销毁善后。业务编排零参与——
 * 服务端是独立 Go 进程，本类只做进程生命周期。
 *
 * 红线与取舍（reviewer 对抗重点，批次E）：
 * - **W^X**：只 exec nativeLibraryDir 里的成品，绝不解压/落盘 filesDir 再 exec
 *   （targetSdk≥29 SELinux 拦截 + 安全红线双因素）。
 * - **监听回环**：QIMENG_LISTEN 固定 127.0.0.1:18430（端口单值互指，见
 *   [EmbeddedServerConfig.LISTEN_ADDRESS]），不暴露局域网。
 * - **重启语义（V1 定案）**：子进程异常退出不自动重拉——更新通知为「已停止」并
 *   stopSelf。端口被占（真机 Termux 形态 A 还在跑）表现为秒退，读 server.log 可
 *   定位；用户重新点本机模式即重启。避免崩溃循环重拉掩盖根因。
 * - **进程组善后**：onDestroy 先 SIGTERM（Go 侧优雅收池）等待 [DESTROY_GRACE_MS]
 *   再 SIGKILL（后台线程执行）；ffmpeg 是服务端子进程，父进程死后残余 ffmpeg 为
 *   短命单命令 CLI（父进程生时的 frameTimeout 闸已无人执行，残余只能跑完当次
 *   编码自然退出——reviewer P3-8 勘误原「自带超时」表述），不级联追杀。
 * - **FGS 类型 specialUse**：本地媒体服务端不属于任何标准类型；dataSync 已被
 *   上传链路占用且语义不符。API34+ 必填子类型属性在 manifest 声明。
 */
class EmbeddedServerService : Service() {

    companion object {
        const val ACTION_START = "media.qimeng.app.core.data.embedded.action.START"
        const val ACTION_STOP = "media.qimeng.app.core.data.embedded.action.STOP"

        /** 前台回归健康检查（2026-09-25 冻结事故自愈，见 [verifyChildHealthAndHeal]） */
        const val ACTION_HEALTH_CHECK = "media.qimeng.app.core.data.embedded.action.HEALTH_CHECK"

        const val CHANNEL_ID = "embedded_server"
        const val NOTIFICATION_ID = 42

        /** 健康探测路径：根路径 /healthz 免鉴权探针别名（协议面 /api/v1/healthz 的运维别名） */
        private const val HEALTH_PATH = "/healthz"

        /** 健康探测超时：回环毫秒级返回，3s 不回=子进程调度已停摆（2026-09-25 实测冻结形态） */
        private const val HEALTH_PROBE_TIMEOUT_MS = 3_000

        /** 首探失败后的复核间隔：给「整组解冻竞态」留窗口——冻结刚解时子进程可能只是
         *  还没轮到调度，立刻杀会误伤一个即将自愈的进程；两次都探不响应才动手 */
        private const val HEALTH_RECHECK_DELAY_MS = 2_000L

        /** 残留子进程 SIGKILL 后等端口释放的上限（内核随进程死亡立即释放监听端口，
         *  正常毫秒级；上限只是防御性兜底） */
        private const val STALE_KILL_WAIT_MS = 2_000L

        /** 看护轮询周期：进程存活检查不需要秒级响应 */
        const val WATCHDOG_INTERVAL_MS = 5_000L

        /** onDestroy 优雅期：给 Go 服务端 SIGTERM 后的收池时间，超时强杀（后台线程执行，见 onDestroy） */
        const val DESTROY_GRACE_MS = 3_000L

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, EmbeddedServerService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, EmbeddedServerService::class.java).setAction(ACTION_STOP),
            )
        }

        fun requestHealthCheck(context: Context) {
            context.startForegroundService(
                Intent(context, EmbeddedServerService::class.java).setAction(ACTION_HEALTH_CHECK),
            )
        }
    }

    // reviewer P2-3：主线程写、看护协程（Dispatchers.Default）读——@Volatile 保可见性，
    // onServerDied 的身份守卫（!== 比较）依赖读到最新引用。
    @Volatile
    private var serverProcess: Process? = null

    /** 子进程 pid（[findChildPid] 定位）：android.jar 的 java.lang.Process 桩无 pid()，
     *  回收比对与 pid 落盘都从这里取值 */
    @Volatile
    private var serverPid: Int? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null

    /** ensureServerRunning 互斥（2026-09-25 异步化引入）：启动序列挪出主线程后，连续
     *  START intent 的两条协程会在「serverProcess 判空→赋值」窗口交错，可能拉起双子
     *  进程（输家 bind 失败秒退）。原同步版靠主线程串行天然免锁，异步化后必须显式补上 */
    private val startMutex = Mutex()

    /** 健康检查单飞闸：连续 onNewIntent/前台回归可能连发 HEALTH_CHECK，探测+复核最长
     *  ~8s，放行并发检查会重复探测甚至重复重拉 */
    private val healthCheckInFlight = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HEALTH_CHECK -> {
                // FGS 契约：startForegroundService 拉起必须先 startForeground（服务已在
                // 运行时重发通知无害，内容不变）；探测与重拉全异步，不阻塞主线程
                startForegroundCompat()
                verifyChildHealthAndHeal()
            }
            else -> {
                startForegroundCompat()
                scope.launch { ensureServerRunning() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // reviewer P2-2：善后不能阻塞主线程（waitFor 最多 [DESTROY_GRACE_MS]）——
        // SIGTERM→宽限→SIGKILL 全程移入短命后台线程；Service 销毁后进程若仍存活
        // 也会随 App 进程死亡被系统回收（FGS 优先级已摘），此线程只是加速收尾。
        serverProcess?.let { process ->
            Thread {
                process.destroy() // Linux 系 SIGTERM，Go 侧 signal 处理收池退出
                try {
                    process.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (process.isAlive) process.destroyForcibly()
            }.start()
        }
        serverProcess = null
        serverPid = null
        super.onDestroy()
    }

    /**
     * 拉起服务端子进程（已运行则跳过——ensure 语义，重复 START intent 无副作用）。
     *
     * 2026-09-25 起在协程内执行（startMutex 串行化）：残留子进程回收要在 SIGKILL 后
     * 等端口释放，不能搬回主线程；pid 文件在启动成功后落盘，供跨 Service 生命周期
     * 的回收（见 [reclaimStaleChild]）。
     */
    private suspend fun ensureServerRunning(): Unit = startMutex.withLock {
        if (serverProcess?.isAlive == true) return

        val binary = EmbeddedServerConfig.serverBinary(applicationInfo.nativeLibraryDir)
        if (!binary.canExecute()) {
            postStoppedNotification("内嵌服务端未随包打包（缺 ${binary.name}）——请用 make app-embedded 出完整包")
            stopSelf()
            return
        }
        val dataDir = EmbeddedServerConfig.dataDir(filesDir.absolutePath)
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            postStoppedNotification("无法创建数据目录 ${dataDir.absolutePath}")
            stopSelf()
            return
        }
        reclaimStaleChild(dataDir)

        runCatching {
            val processBuilder = ProcessBuilder(binary.absolutePath).apply {
                environment().putAll(
                    EmbeddedServerConfig.environment(dataDir.absolutePath, applicationInfo.nativeLibraryDir),
                )
                redirectErrorStream(true)
                // 每次启动截断：日志只服务当次运行排障，无限制追加会撑爆存储
                redirectOutput(File(dataDir, EmbeddedServerConfig.LOG_FILE_NAME))
            }
            val process = processBuilder.start()
            serverProcess = process
            serverPid = findChildPid()
            writePidFile(dataDir)
            startWatchdog()
        }.onFailure { failure ->
            postStoppedNotification("拉起服务端失败：${failure.message}（详见 ${EmbeddedServerConfig.LOG_FILE_NAME}）")
            stopSelf()
        }
    }

    /**
     * 回收失去句柄的孤儿子进程（2026-09-25「后端占用」形态）：Service 重建/壳进程被
     * 杀后，旧子进程可能仍占着 18430——新子进程 bind 失败秒退，本机模式反复「已退出」。
     * pid 文件是跨 Service 生命周期的唯一线索；误杀三重防线：pid 合法正数（[EmbeddedServerConfig.parseRecordedPid]）、
     * /proc cmdline 仍含本服务端二进制名（pid 已被复用给他进程绝不碰）、记录 pid 等于
     * 当前子进程时跳过。SIGKILL 后轮询等退出（监听端口随进程死亡由内核释放）。
     */
    private fun reclaimStaleChild(dataDir: File) {
        val recorded = EmbeddedServerConfig.parseRecordedPid(
            runCatching { File(dataDir, EmbeddedServerConfig.PID_FILE_NAME).readText() }.getOrNull(),
        ) ?: return
        if (serverPid == recorded) return
        val cmdline = runCatching {
            File("/proc/$recorded/cmdline").readBytes().toString(Charsets.UTF_8)
        }.getOrNull()
        if (cmdline?.contains(EmbeddedServerConfig.SERVER_BINARY) != true) return
        android.os.Process.killProcess(recorded)
        val deadline = System.currentTimeMillis() + STALE_KILL_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val gone = runCatching { !File("/proc/$recorded").exists() }.getOrDefault(true)
            if (gone) return
            Thread.sleep(100)
        }
    }

    /**
     * 定位本壳进程拉起的服务端子进程 pid：扫 /proc 找 cmdline 含本服务端二进制、且
     * 父进程是自己的条目。为什么不直接 java.lang.Process#pid()：官方 API 24+ 才有，
     * 但 compileSdk 平台桩的 java/lang/Process.class 缺该方法（Kotlin 编译期不可见，
     * 2026-09-25 实测）；同 uid 进程 /proc 可读，扫描是可靠替代（千级条目、毫秒级）。
     * @return 找不到（子进程已秒退等）返回 null
     */
    private fun findChildPid(): Int? {
        val myPid = android.os.Process.myPid()
        val candidates = File("/proc").listFiles { f -> f.name.toIntOrNull() != null } ?: return null
        for (entry in candidates) {
            if (entry.name.toInt() == myPid) continue
            val cmdline = runCatching {
                File(entry, "cmdline").readBytes().toString(Charsets.UTF_8)
            }.getOrDefault("")
            if (!cmdline.contains(EmbeddedServerConfig.SERVER_BINARY)) continue
            // /proc/<pid>/stat 形如 "pid (comm) state ppid ..."，comm 可含空格括号，
            // 以 ") " 切开后第 2 字段才是 ppid
            val statFields = runCatching {
                File(entry, "stat").readText().substringAfterLast(") ").trim().split(" ")
            }.getOrNull() ?: continue
            if (statFields.getOrNull(1)?.toIntOrNull() == myPid) return entry.name.toInt()
        }
        return null
    }

    /** 子进程 pid 落盘（失败/拿不到 pid 均可容忍：回收只是拿不到线索退化为 no-op） */
    private fun writePidFile(dataDir: File) {
        val pid = serverPid ?: return
        runCatching {
            File(dataDir, EmbeddedServerConfig.PID_FILE_NAME).writeText(pid.toString())
        }
    }

    /**
     * 前台回归健康检查 + 自愈（2026-09-25 冻结事故）：本机模式下 App 退后台时，子进程
     * 与壳进程同 cgroup 一起被系统冻结/压制，回前台后子进程可能停留在「进程活着但
     * 调度停摆」形态（实测冻结：端口在听、/healthz 不回、isAlive=true）——isAlive
     * 探不出，只有真实 HTTP 探测能发现。为什么不在看护协程里后台探测：冻结时 Service
     * 与子进程一起被冻，后台探测既跑不动也救不了；探测放在前台回归（壳层 onStart，
     * 用户可见时刻）才有意义，且此时刻壳进程必被解冻，动作一定可执行。
     *
     * 判定口径：探两次（间隔 [HEALTH_RECHECK_DELAY_MS]）都不响应才动手——单次失败可
     * 能是「整组刚解冻、子进程还没轮到调度」的竞态；误杀的代价只是一次干净重启，
     * 但能换来「详情页加载失败需要手动重启 App」降级为「回到 App 数秒自愈」。
     */
    private fun verifyChildHealthAndHeal() {
        if (!healthCheckInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val child = serverProcess
                // 无子进程/已死亡：死亡归看护收尾、启动归 ensure，健康检查不越界
                if (child == null || !child.isAlive) return@launch
                if (probeHealthOnce()) return@launch
                delay(HEALTH_RECHECK_DELAY_MS)
                if (probeHealthOnce()) return@launch
                restartUnhealthyChild(child)
            } finally {
                healthCheckInFlight.set(false)
            }
        }
    }

    /** 单次 /healthz 探测（免鉴权、不碰数据库，响应即代表调度在转）。任何异常都算不健康 */
    private fun probeHealthOnce(): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:${ServerAddress.LOCAL_MODE_PORT}$HEALTH_PATH")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = HEALTH_PROBE_TIMEOUT_MS
            connection.readTimeout = HEALTH_PROBE_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    /**
     * 杀掉无响应子进程并原位重拉。顺序敏感：先摘看护再动手——否则杀掉的子进程会被
     * 看护判死、误发「进程已退出」并 stopSelf，自愈变成自毁；身份守卫（!==）与
     * [onServerDied] 同源，防检查期间子进程已被新一轮拉起取代的双杀。
     * 用 SIGKILL（destroyForcibly）：调度停摆的进程不会执行 SIGTERM 处理器。
     */
    private fun restartUnhealthyChild(child: Process) {
        watchdogJob?.cancel()
        if (serverProcess !== child) return
        serverProcess = null
        serverPid = null
        runCatching {
            child.destroyForcibly()
            child.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
        }
        scope.launch { ensureServerRunning() }
        postInfoNotification("本机服务端已自动恢复", "服务无响应已重拉（冻结自愈）；数据无影响")
    }

    /** 看护：子进程退出即收尾（首个检查点在 [WATCHDOG_INTERVAL_MS] 后，秒退的子进程
     *  必然已被检出——reviewer P3-1 原启动宽限是死逻辑已撤）。 */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        val process = serverProcess ?: return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!process.isAlive) {
                    onServerDied(process)
                    break
                }
            }
        }
    }

    private fun onServerDied(process: Process) {
        if (serverProcess !== process) return // 已被新一轮拉起取代
        serverProcess = null
        serverPid = null
        postStoppedNotification(
            "本机服务端进程已退出（常见原因：端口 ${ServerAddress.LOCAL_MODE_PORT} 被占用——Termux 形态 A 在跑请先停；" +
                "详情见 ${EmbeddedServerConfig.DATA_DIR_NAME}/${EmbeddedServerConfig.LOG_FILE_NAME}）",
        )
        stopSelf()
    }

    // ---------- 通知 ----------

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "本机服务端", NotificationManager.IMPORTANCE_LOW).apply {
                description = "内嵌媒体服务端运行状态（ADR-0015 单机形态）"
            },
        )
        val notification = buildNotification("本机服务端运行中", "127.0.0.1:${ServerAddress.LOCAL_MODE_PORT} · 数据存本机")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 终态通知（服务即将 stopSelf，前台通知会随之消失，用普通通知留痕告知用户）。
     *  reviewer P2-1：终态通知必须可滑掉——不设 ongoing（与 FGS 运行中通知的关键差异，
     *  复用 buildNotification 时显式传 false）。 */
    private fun postStoppedNotification(text: String) {
        postInfoNotification("本机服务端已停止", text)
    }

    /** 非常驻事件通知（终态/自愈等一次性事件）：与终态共用可滑掉槽位（NOTIFICATION_ID+1，
     *  与 FGS 常驻通知分 id），IMPORTANCE_LOW 不响铃 */
    private fun postInfoNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, buildNotification(title, text, ongoing = false))
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean = true): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setContentIntent(contentIntent)
            .build()
    }
}
