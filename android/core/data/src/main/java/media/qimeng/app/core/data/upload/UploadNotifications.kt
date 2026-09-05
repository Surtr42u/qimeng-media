package media.qimeng.app.core.data.upload

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import media.qimeng.app.core.data.R

/**
 * 上传通知装配（M4-5）：前台进度通知 + 完成通知。
 * 通知权限（POST_NOTIFICATIONS，API 33+ 运行时申请）只影响"用户看不看得到"，
 * 未授权时静默跳过 notify——上传本身不被权限阻塞（官方文档口径）。
 */
object UploadNotifications {

    /** 通知渠道 ID（上传进度独立渠道：用户可单独静音上传提醒） */
    const val CHANNEL_ID = "qm_upload"

    /**
     * 前台服务通知 ID。串行队列（并发=1）下同一时刻至多一个前台 worker，
     * 恒定单 ID 即可（每次 setForeground 覆盖）；完成通知另用独立 ID 段避免互踩。
     */
    const val FOREGROUND_NOTIF_ID = 41001

    /** 完成通知 ID 段基址（+localId 哈希；掩码保证落在合法 int 正区间） */
    private const val DONE_NOTIF_ID_BASE = 42001

    /** 哈希掩码：去符号位，避免 ID 溢出为负与前台 ID 段重叠 */
    private const val HASH_MASK = 0x7FFFFFFF

    /** 渠道名（用户可见，设置页里显示） */
    private const val CHANNEL_NAME = "上传进度"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        )
        // 进度类通知：不打扰（无声、不弹出），重要性低即可
        manager.createNotificationChannel(channel)
    }

    /** 进度通知（前台服务通知；percent<0 显示不定进度） */
    fun progress(context: Context, title: String, percent: Int): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.qm_ic_stat_upload)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (percent in 0..100) {
            builder
                .setProgress(100, percent, false)
                .setContentText("上传中 $percent%")
        } else {
            builder.setProgress(0, 0, true).setContentText("上传中…")
        }
        return builder.build()
    }

    /** 完成通知（成功/失败共用样式：文字即终局，非 ongoing，用户可划掉） */
    fun done(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.qm_ic_stat_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .build()

    /** 完成通知 ID（按任务隔离：串行完成的多个任务各自保留一条可划通知） */
    fun doneNotifId(localId: String): Int =
        DONE_NOTIF_ID_BASE + (localId.hashCode() and HASH_MASK) % 100000

    /** 是否已获通知权限（API 33+ 检查运行时权限；低版本常开） */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
