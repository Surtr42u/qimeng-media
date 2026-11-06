package media.qimeng.app.core.data.backup

import android.net.Uri
import android.os.Environment
import android.os.Build
import android.util.AtomicFile
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.File
import java.time.Instant
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.authorimport.AuthorTxtDocument
import media.qimeng.app.core.data.authorimport.readAuthorTxtDocument
import media.qimeng.app.core.data.di.ClientPrefsDataStore
import media.qimeng.app.core.data.repository.BackupRepository
import media.qimeng.app.core.data.repository.BusinessApiFactory
import media.qimeng.app.core.network.ServerAddress
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject

interface MaintenanceBackupWriter {
    suspend fun writeIfManaged(dirUri: String, kind: BackupFileKind): Long?
    suspend fun readKind(dirUri: String, kind: BackupFileKind): BackupFileKind = kind
}

/** One device-local folder owns the existing backup and upload-archive paths. */
@Singleton
class MaintenanceFolderRepository @Inject constructor(
    @ClientPrefsDataStore private val prefs: DataStore<Preferences>,
    private val backups: BackupRepository,
    private val factory: BusinessApiFactory,
) : MaintenanceBackupWriter {
    val root = prefs.data.map { it[ROOT] }
    private val mutex = Mutex()

    suspend fun select(path: String): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                check(Environment.isExternalStorageManager()) { "请先授予所有文件访问权限" }
            }
            val folder = checkedSharedFolder(path)
            require(folder != Environment.getExternalStorageDirectory().canonicalFile) { "请选择内部存储中的子文件夹" }
            listOf(AUTHORS, BACKUPS, ARCHIVE).forEach { childDirectory(folder, it) }
            // A phone path is only sent to its own embedded service. Remote
            // connections keep their server mirror location unchanged.
            val target = factory.currentBaseUrl()
            val api = if (ServerAddress.isLocalModePreset(target)) factory.forServer(target).create() else null
            val previousMirror = api?.apiV1AuthorsMirrorGet()
            if (previousMirror != null) {
                api?.apiV1AuthorsMirrorPut(media.qimeng.sdk.models.AuthorMirrorConfig(
                    path = File(childDirectory(folder, AUTHORS), "作者总表.txt").path,
                    fragmentFilename = "作者总表.txt",
                    mode = media.qimeng.sdk.models.AuthorMirrorConfig.Mode.master,
                ))
            }
            try {
                // Consumers retain their existing keys. Selection commits all three together.
                prefs.edit {
                    it[ROOT] = folder.path
                    it[BACKUP_URI] = Uri.fromFile(childDirectory(folder, BACKUPS)).toString()
                    it[ARCHIVE_PATH] = childDirectory(folder, ARCHIVE).path
                }
            } catch (failure: Throwable) {
                if (previousMirror != null) withContext(NonCancellable) {
                    try { api?.apiV1AuthorsMirrorPut(previousMirror) }
                    catch (restoreFailure: Exception) { failure.addSuppressed(restoreFailure) }
                }
                throw failure
            }
            Unit
        }
    }

    suspend fun export(automatic: Boolean = false): Long = mutex.withLock {
        val path = root.first() ?: error("请先选择维护根目录")
        exportLocked(path, if (automatic) BackupFileKind.AUTOMATIC else BackupFileKind.MANUAL)
    }

    override suspend fun writeIfManaged(dirUri: String, kind: BackupFileKind): Long? = mutex.withLock {
        val path = root.first() ?: return@withLock null
        if (Uri.fromFile(File(path, BACKUPS)).toString() != dirUri) return@withLock null
        exportLocked(path, kind)
    }

    override suspend fun readKind(dirUri: String, kind: BackupFileKind): BackupFileKind {
        val path = root.first() ?: return kind
        return if (Uri.fromFile(File(path, BACKUPS)).toString() == dirUri &&
            kind in listOf(BackupFileKind.MANUAL, BackupFileKind.AUTOMATIC)) BackupFileKind.CURRENT else kind
    }

    private suspend fun exportLocked(path: String, kind: BackupFileKind): Long {
        val serverOwnsMaster = withContext(Dispatchers.IO) {
            val target = factory.currentBaseUrl()
            val mirror = if (ServerAddress.isLocalModePreset(target)) factory.forServer(target).create().apiV1AuthorsMirrorGet() else null
            val expectedMaster = File(File(path, AUTHORS), "作者总表.txt").canonicalPath
            val ownsMaster = mirror?.mode == media.qimeng.sdk.models.AuthorMirrorConfig.Mode.master && mirror.path == expectedMaster
            if (ownsMaster && kind != BackupFileKind.PRE_IMPORT) {
                factory.forServer(target).create().apiV1AuthorsMirrorPut(requireNotNull(mirror))
            }
            ownsMaster
        }
        val json = backups.exportJson()
        return withContext(Dispatchers.IO) {
            val folder = checkedSharedFolder(path)
            val data = JSONObject(json).getJSONObject("data")
            val bytes = json.toByteArray(Charsets.UTF_8)
            require(bytes.size <= BackupValidator.BACKUP_MAX_BYTES) { "备份超过64MiB" }
            val backupFolder = childDirectory(folder, BACKUPS)
            writeMaintenanceGeneration(backupFolder, BackupFileKind.CURRENT.fileName, json,
                protectExternal = kind == BackupFileKind.AUTOMATIC, backup = true)
            if (kind == BackupFileKind.PRE_IMPORT) return@withContext bytes.size.toLong()
            val authors = childDirectory(folder, AUTHORS)
            val master = data.getString("authorMasterTxt")
            val fragments = data.optJSONArray("txtFragments")
            val names = mutableSetOf<String>()
            // Validate every output name before touching existing files.
            val documents = buildList {
                if (fragments != null) for (index in 0 until fragments.length()) {
                    val fragment = fragments.getJSONObject(index)
                    val name = fragment.optString("filename").ifBlank { "作者清单.txt" }
                    require(safeMaintenanceName(name) && name.endsWith(".txt", true)) { "作者片段文件名不安全" }
                    require(names.add(name.lowercase())) { "作者片段文件名重复" }
                    add(name to fragment.getString("content"))
                }
            }
            if (!serverOwnsMaster) {
                val oldMaster = File(authors, "作者总表.txt")
                require(oldMaster.canonicalFile.parentFile == authors)
                if (oldMaster.isFile) {
                    require(oldMaster.length() <= BackupValidator.BACKUP_MAX_BYTES) { "原作者主表超过64MiB，请先移走保留" }
                    val old = oldMaster.readText(Charsets.UTF_8)
                    val checksum = File(authors, "作者总表.txt.sha256")
                    require(checksum.canonicalFile.parentFile == authors && checksum.length() <= 1024)
                    val imported = documents.any { it.second == old }
                    check(!checksum.isFile || old == master || imported || checksum.readText(Charsets.UTF_8).trim() == maintenanceSha256(old.toByteArray(Charsets.UTF_8))) {
                        "作者主表已被外部编辑，请先导入主表后再写回"
                    }
                }
                writeMaintenanceGeneration(authors, "作者总表.txt", master)
            }
            writeMaintenanceFile(authors, "作者资料.md", maintenanceAuthorReport(data))
            writeMaintenanceFile(authors, "作者片段索引.json", org.json.JSONArray(listOf("作者总表.txt")).toString())
            writeMaintenanceFile(folder, "目录说明.md", MAINTENANCE_README)
            prefs.edit { it[LAST_RUN] = System.currentTimeMillis() }
            json.toByteArray(Charsets.UTF_8).size.toLong()
        }
    }

    suspend fun authorDocuments(): List<AuthorTxtDocument> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val folder = checkedSharedFolder(root.first() ?: error("请先选择维护根目录"))
            val directory = childDirectory(folder, AUTHORS)
            val indexFile = File(directory, "作者片段索引.json")
            require(indexFile.canonicalFile.parentFile == directory)
            val files = if (indexFile.isFile) {
                require(indexFile.length() <= 1024 * 1024) { "作者片段索引过大" }
                val index = org.json.JSONArray(indexFile.readText(Charsets.UTF_8))
                (0 until index.length()).map { entry ->
                    val name = index.getString(entry)
                    require(safeMaintenanceName(name) && name.endsWith(".txt", true)) { "作者片段索引文件名不安全" }
                    File(directory, name)
                }
            } else (directory.listFiles() ?: error("读取作者资料目录失败"))
                .filter { it.isFile && it.name.endsWith(".txt", true) && !it.name.endsWith("（上次）.txt") && !it.name.endsWith("（上上次）.txt") }.sortedBy { it.name }
            files.map { file ->
                require(file.canonicalFile.parentFile == directory) { "作者文件超出目录" }
                readAuthorTxtDocument(file.name, file.length()) { file.inputStream() }
            }
        }
    }

    private companion object {
        val ROOT = stringPreferencesKey("maintenance_root_path")
        val BACKUP_URI = stringPreferencesKey("backup_auto_dir_uri")
        val ARCHIVE_PATH = stringPreferencesKey("upload_archive_path")
        val LAST_RUN = longPreferencesKey("backup_auto_last_run_ms")
        const val AUTHORS = "作者资料"
        const val BACKUPS = "备份"
        const val ARCHIVE = "上传归档"
    }
}

internal fun checkedSharedFolder(path: String): File {
    val storage = Environment.getExternalStorageDirectory().canonicalFile
    val folder = File(path).canonicalFile
    require(folder.path.startsWith(storage.path + File.separator) || folder == storage) { "请选择内部共享存储中的目录" }
    require(folder.isDirectory) { "目录不存在或无法访问" }
    return folder
}

internal fun childDirectory(root: File, name: String): File {
    require(safeMaintenanceName(name))
    val child = File(root, name)
    require(child.canonicalFile.parentFile == root.canonicalFile) { "子目录超出维护目录" }
    check(child.isDirectory || child.mkdir()) { "无法创建维护子目录" }
    return child.canonicalFile
}

internal fun safeMaintenanceName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it == ':' || it.code < 32 }

internal fun maintenanceSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Rotate only changed data. Retrying after an interrupted write keeps the same history. */
internal fun writeMaintenanceGeneration(folder: File, name: String, content: String, protectExternal: Boolean = false, backup: Boolean = false) {
    val current = File(folder, name)
    require(current.canonicalFile.parentFile == folder.canonicalFile)
    val old = if (current.isFile) {
        require(current.length() <= BackupValidator.BACKUP_MAX_BYTES) { "原文件超过64MiB，请先移走保留" }
        current.readText(Charsets.UTF_8)
    } else null
    val checksum = File(folder, "$name.sha256")
    require(checksum.canonicalFile.parentFile == folder.canonicalFile && checksum.length() <= 1024)
    if (protectExternal && old != null) {
        check(checksum.isFile && checksum.readText(Charsets.UTF_8).trim() == maintenanceSha256(old.toByteArray(Charsets.UTF_8))) {
            "备份已被外部替换，请先导入该文件，再更新备份"
        }
    }
    val changed = old != null && if (backup) JSONObject(old).getJSONObject("data").toString() != JSONObject(content).getJSONObject("data").toString() else old != content
    if (changed) {
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.')
        val previousName = "$stem（上次）.$extension"
        val previous = File(folder, previousName)
        require(previous.canonicalFile.parentFile == folder.canonicalFile)
        if (previous.isFile) {
            require(previous.length() <= BackupValidator.BACKUP_MAX_BYTES) { "上次文件超过64MiB" }
            val last = previous.readText(Charsets.UTF_8)
            if (last != old) writeCheckedMaintenanceFile(folder, "$stem（上上次）.$extension", last)
        }
        writeCheckedMaintenanceFile(folder, previousName, requireNotNull(old))
    }
    writeCheckedMaintenanceFile(folder, name, content)
}

private fun writeCheckedMaintenanceFile(folder: File, name: String, content: String) {
    writeMaintenanceFile(folder, name, content)
    writeMaintenanceFile(folder, "$name.sha256", maintenanceSha256(content.toByteArray(Charsets.UTF_8)))
}

@Synchronized
internal fun writeMaintenanceFile(folder: File, name: String, content: String) {
    require(safeMaintenanceName(name))
    val file = File(folder, name)
    require(file.canonicalFile.parentFile == folder.canonicalFile) { "文件超出维护目录" }
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
        stream.write(content.toByteArray(Charsets.UTF_8))
        atomic.finishWrite(stream)
    } catch (failure: Throwable) {
        atomic.failWrite(stream)
        throw failure
    }
}

/** Human-readable companion to the current portable backup. */
internal fun maintenanceAuthorReport(data: JSONObject): String = buildString {
    val authors = data.optJSONArray("authors")
    val refs = data.optJSONArray("authorMediaRefs")
    appendLine("# 作者资料总表")
    appendLine()
    appendLine("生成时间 ${Instant.now()}")
    appendLine("作者 ${authors?.length() ?: 0} 位。历史入库时间仅代表记录创建，原始加入日期可能未记录。")
    appendLine("TXT用于常规作者关联，完整资料、头像、COS及标签保存在v2备份。")
    if (authors != null) {
        appendLine(); appendLine("## 作者索引"); appendLine()
        appendLine("| 作者 | 内部ID | 关联文件 |")
        appendLine("| --- | --- | --- |")
        for (index in 0 until authors.length()) {
            val author = authors.getJSONObject(index)
            val id = author.getString("authorId")
            val name = author.getString("displayName").replace("|", "\\|").replace("\n", " ")
            var count = 0
            if (refs != null) for (item in 0 until refs.length()) {
                if (refs.getJSONObject(item).getString("authorId") == id) count++
            }
            appendLine("| $name | $id | $count |")
        }
    }
    if (authors != null) for (index in 0 until authors.length()) {
        val author = authors.getJSONObject(index)
        val id = author.getString("authorId")
        appendLine()
        appendLine("## ${author.getString("displayName")}")
        appendLine()
        appendLine("内部ID `$id`")
        if (author.has("createdAtMillis")) appendLine("入库记录 ${Instant.ofEpochMilli(author.getLong("createdAtMillis"))}")
        val raw = author.optString("profileJson")
        if (raw.isNotBlank()) {
            val profile = JSONObject(raw)
            appendLine("核验状态 ${profile.optString("status", "未记录")}")
            listOf("biography", "homepageUrl").forEach { key ->
                profile.optString(key).takeIf { it.isNotBlank() }?.let { appendLine(); appendLine(it) }
            }
            profile.optJSONObject("xProfile")?.let { x ->
                appendLine(); appendLine("### X资料")
                listOf("url", "biography", "biographyZh").forEach { key ->
                    x.optString(key).takeIf { it.isNotBlank() }?.let { appendLine(); appendLine(it) }
                }
            }
            profile.optJSONArray("evidenceUrls")?.let { evidence ->
                appendLine(); appendLine("### 来源证据"); appendLine()
                for (item in 0 until evidence.length()) appendLine("- ${evidence.getString(item)}")
            }
        }
        appendLine(); appendLine("### 已关联文件"); appendLine()
        if (refs != null) for (item in 0 until refs.length()) {
            val ref = refs.getJSONObject(item)
            if (ref.getString("authorId") == id) appendLine("- ${ref.getString("fileName")}")
        }
    }
}

internal const val MAINTENANCE_README = """# 绮梦影库维护目录

- 作者资料保存作者总表.txt、作者总表（上次）.txt、作者总表（上上次）.txt，作者资料.md供阅读。
- 备份保存完整v2元数据、作者资料和离线头像，媒体原件需要另外同步。
- 备份只保留影库备份.json、影库备份（上次）.json、影库备份（上上次）.json，手动与自动共用三代。
- 导入前的本机状态纳入三代轮换，导入完成后刷新最新一份。每份数据文件附带.sha256校验文件，请一起复制。
- 原始TXT片段保存在完整备份内部，目录不再额外输出原始片段和历史副本。
- 上传归档按目标库归档上传成功的原件，沿用上传页的既有操作。
- 导入前先同步媒体并扫描，再核对确认备份或TXT的导入范围。
"""
