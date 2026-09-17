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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

        const val CHANNEL_ID = "embedded_server"
        const val NOTIFICATION_ID = 42

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
    }

    // reviewer P2-3：主线程写、看护协程（Dispatchers.Default）读——@Volatile 保可见性，
    // onServerDied 的身份守卫（!== 比较）依赖读到最新引用。
    @Volatile
    private var serverProcess: Process? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundCompat()
                ensureServerRunning()
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
        super.onDestroy()
    }

    /** 拉起服务端子进程（已运行则跳过——ensure 语义，重复 START intent 无副作用） */
    private fun ensureServerRunning() {
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
            startWatchdog()
        }.onFailure { failure ->
            postStoppedNotification("拉起服务端失败：${failure.message}（详见 ${EmbeddedServerConfig.LOG_FILE_NAME}）")
            stopSelf()
        }
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, buildNotification("本机服务端已停止", text, ongoing = false))
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
