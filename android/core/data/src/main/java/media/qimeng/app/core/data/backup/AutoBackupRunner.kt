package media.qimeng.app.core.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.repository.BackupAutoPrefsRepository
import media.qimeng.app.core.data.repository.BackupRepository
import media.qimeng.sdk.infrastructure.Serializer
import media.qimeng.sdk.models.LegacyBackupFile
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动备份执行器（2026-09-15 批）：旧版「备份目录 + 自动写入全量备份」（GUIDE_BACKUP）
 * 的移植。触发口径 = App 冷启动登录态就绪后跑一次判定：开启且距上次成功 ≥24h 才写；
 * 写入目录 = 用户以 SAF 授权的树 URI（授权经 takePersistableUriPermission 持久化）。
 * 为什么不做 WorkManager 后台：导出走服务端 GET /export，App 不在前台/服务端未起时
 * 后台任务必然失败——「打开应用即自动备份」才是本形态的有效口径（旧版同为应用内写）。
 */
@Singleton
class AutoBackupRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val prefs: BackupAutoPrefsRepository,
) {

    /** 判定 + 执行；返回是否实际写入了备份（调用方仅记日志，不打扰用户） */
    suspend fun runIfDue(now: Long = System.currentTimeMillis()): Boolean {
        val st = prefs.state.first()
        if (!st.enabled) return false
        val dirUri = st.dirUri ?: return false
        if (st.lastRunMillis > 0 && now - st.lastRunMillis < MIN_INTERVAL.toMillis()) return false
        val ok = writeToDir(dirUri)
        if (ok) prefs.setLastRunMillis(now)
        return ok
    }

    /** 立即写入（「立即备份」按钮；与自动判定同一写路径），返回是否成功 */
    suspend fun writeNow(): Boolean = withContext(Dispatchers.IO) {
        val dirUri = prefs.state.first().dirUri ?: return@withContext false
        writeToDir(dirUri)
    }.also { ok ->
        if (ok) prefs.setLastRunMillis(System.currentTimeMillis())
    }

    private suspend fun writeToDir(dirUri: String): Boolean = withContext(Dispatchers.IO) {
        val file = backupRepository.export()
        val json = Serializer.moshi
            .adapter(LegacyBackupFile::class.java).toJson(file)
        val tree = Uri.parse(dirUri)
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(tree)
        val treeDoc = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId)
        // 已存在则截断覆写；不存在先在树根创建（旧版「绮梦影库目录写 qimeng_backup.json」同语义）
        val target = resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            var found: Uri? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == BACKUP_FILE_NAME) {
                    found = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    break
                }
            }
            found
        } ?: DocumentsContract.createDocument(resolver, treeDoc, "application/json", BACKUP_FILE_NAME)
            ?: return@withContext false
        resolver.openOutputStream(target, "wt")?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        } ?: return@withContext false
        true
    }

    companion object {
        /** 自动备份最小间隔：24h（每日一次口径，旧版「自动写入全量备份」节奏） */
        val MIN_INTERVAL: Duration = Duration.ofHours(24)

        const val BACKUP_FILE_NAME = "qimeng_backup.json"

        /** SAF 树 URI 授权持久化标志（目录选择后由调用方 takePersistableUriPermission） */
        const val TREE_PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
