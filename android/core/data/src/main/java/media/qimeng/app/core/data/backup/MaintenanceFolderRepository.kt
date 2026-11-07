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
import media.qimeng.sdk.models.AuthorMirrorConfig
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject

interface MaintenanceBackupWriter {
    suspend fun writeIfManaged(dirUri: String, kind: BackupFileKind): Long?
    suspend fun readKind(dirUri: String, kind: BackupFileKind): BackupFileKind = kind

    /**
     * 读取备份槽（2026-11-07 task-13）：维护目录内历史份跨目录解析——两代布局「上次」在
     * `上次/备份/影库备份.json`，旧 flat3 历史份是同目录旧名文件；含摘要前置核对。
     * null = 该 dirUri 不由维护目录托管（SAF 旧目录由 [BackupDirAccess] 直读）。
     */
    suspend fun readManagedBackup(dirUri: String, kind: BackupFileKind): ManagedBackupFile? = null

    /** 备份槽元数据（状态行/存在性判断），不读整份文件；null 语义同 [readManagedBackup]。 */
    suspend fun managedBackupStatus(dirUri: String, kind: BackupFileKind): BackupFileStatus? = null
}

/**
 * One device-local folder owns the existing backup and upload-archive paths.
 *
 * 2026-11-07 R7 两代布局：维护根下改为「当前 / 上次」两个独立文件夹
 * （当前/作者资料、当前/备份、上次/作者资料、上次/备份），上传归档仍留在维护根；
 * 轮换、摘要与原子写在纯 JVM 的 [writeGenerationFiles] 等实现里，本类负责 Android 侧的
 * 权限/共享存储路径校验、服务端主表镜像刷新、DataStore 偏好与目录编排。
 * 旧三代同目录文件保留在原处，只由 [layoutReport]/[migrateLegacyLayout] 报告与复制，
 * 绝不自动删除（docs/AI_MAINTENANCE_P0.md §4.4、docs/AI_PLATFORM_TASKBOOK.md §9.4）。
 */
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
            requireAllFilesAccess()
            val folder = checkedSharedFolder(path)
            require(folder != Environment.getExternalStorageDirectory().canonicalFile) { "请选择内部存储中的子文件夹" }
            val layout = ensureMaintenanceLayout(folder)
            // A phone path is only sent to its own embedded service. Remote
            // connections keep their server mirror location unchanged.
            val target = factory.currentBaseUrl()
            val api = if (ServerAddress.isLocalModePreset(target)) factory.forServer(target).create() else null
            val previousMirror = api?.apiV1AuthorsMirrorGet()
            if (previousMirror != null) {
                api?.apiV1AuthorsMirrorPut(masterMirrorConfig(File(layout.currentAuthors, AUTHOR_MASTER_FILE).path))
            }
            try {
                // Consumers keep their existing keys. Selection commits all three together.
                prefs.edit {
                    it[ROOT] = folder.path
                    it[BACKUP_URI] = layout.currentBackupUri
                    it[ARCHIVE_PATH] = layout.archive.path
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
        val layout = withContext(Dispatchers.IO) { maintenanceLayout(checkedSharedFolder(path)) }
        // 兼容升级前保存的旧备份目录路径：命中后仍由本仓库写两代布局，并顺带升级偏好。
        if (dirUri != layout.currentBackupUri && dirUri != layout.legacyBackupUri) return@withLock null
        exportLocked(path, kind)
    }

    override suspend fun readKind(dirUri: String, kind: BackupFileKind): BackupFileKind {
        val path = root.first() ?: return kind
        val layout = maintenanceLayout(File(path))
        val managed = dirUri == layout.currentBackupUri || dirUri == layout.legacyBackupUri
        return if (managed && kind in listOf(BackupFileKind.MANUAL, BackupFileKind.AUTOMATIC)) {
            BackupFileKind.CURRENT
        } else {
            kind
        }
    }

    /**
     * 历史/当前备份槽读取（2026-11-07 task-13）：两代布局的「上次」在独立目录、旧 flat3
     * 历史份在旧目录旧名文件，必须按槽解析真实文件，不能由调用方用「当前目录 + 历史固定名」
     * 拼路径。只读：不创建目录、不移动/删除文件；摘要不符或超限按读取失败返回 null。
     */
    override suspend fun readManagedBackup(dirUri: String, kind: BackupFileKind): ManagedBackupFile? {
        val path = root.first() ?: return null
        return withContext(Dispatchers.IO) {
            val layout = maintenanceLayout(File(path))
            // 旧 flat3 目录（升级前保存的偏好）内固定名文件由 dirAccess 直读，这里不重复解析。
            if (dirUri != layout.currentBackupUri) return@withContext null
            val slot = resolveMaintenanceBackupSlot(layout.root, kind) ?: return@withContext null
            val bytes = readMaintenanceBackupSlot(slot) ?: return@withContext null
            ManagedBackupFile(slot.name, bytes)
        }
    }

    /** 历史备份槽元数据：语义同 [readManagedBackup]，用于状态行/「有没有此份」判断。 */
    override suspend fun managedBackupStatus(dirUri: String, kind: BackupFileKind): BackupFileStatus? {
        val path = root.first() ?: return null
        return withContext(Dispatchers.IO) {
            val layout = maintenanceLayout(File(path))
            if (dirUri != layout.currentBackupUri) return@withContext null
            val slot = resolveMaintenanceBackupSlot(layout.root, kind)?.takeIf { it.isFile } ?: return@withContext null
            BackupFileStatus(slot.length(), slot.lastModified())
        }
    }

    /**
     * 只读核对：当前/上次代文件摘要状态、旧三代遗留文件、可迁移与可清理清单。
     * 不创建目录、不改动文件、不发网络请求；供维护页后续接入迁移入口。
     */
    suspend fun layoutReport(): MaintenanceLayoutReport = mutex.withLock {
        withContext(Dispatchers.IO) {
            val folder = checkedSharedFolder(root.first() ?: error("请先选择维护根目录"))
            inspectMaintenanceLayout(folder)
        }
    }

    /**
     * 旧布局迁移：只读核对后把旧最新文件复制为「当前」、旧「（上次）」复制为「上次」，
     * 旧「（上上次）」只列入可清理清单。只复制不删除、不覆盖已有不同内容；
     * UI 尚未接线，维护页可后续直接调用本方法（见 android/README.md 维护目录章节）。
     */
    suspend fun migrateLegacyLayout(): MaintenanceMigrationResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireAllFilesAccess()
            val folder = checkedSharedFolder(root.first() ?: error("请先选择维护根目录"))
            val result = copyLegacyToGenerations(folder)
            val layout = ensureMaintenanceLayout(folder)
            upgradeBackupDirPreference(layout)
            result
        }
    }

    private suspend fun exportLocked(path: String, kind: BackupFileKind): Long {
        // 刷新服务端主表镜像前先快照「当前」作者代：镜像换代表只写主表与摘要，
        // 快照用于在服务端换代表后补齐「上次」里的作者资料.md / 作者片段索引.json。
        val layout = withContext(Dispatchers.IO) {
            val folder = checkedSharedFolder(path)
            ensureMaintenanceLayout(folder)
        }
        val authorSnapshot = withContext(Dispatchers.IO) {
            readGenerationSnapshot(layout.currentAuthors, AUTHOR_GENERATION_FILES)
        }
        val serverOwnsMaster = refreshManagedMaster(layout, kind)
        val json = backups.exportJson()
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size <= BackupValidator.BACKUP_MAX_BYTES) { "备份超过64MiB" }
        return withContext(Dispatchers.IO) {
            // 数据段与作者片段名先校验，再落任何文件：失败时上一份完整两代布局保持原样。
            val data = JSONObject(json).getJSONObject("data")
            val documents = if (kind == BackupFileKind.PRE_IMPORT) emptyList() else authorFragmentDocuments(data)
            writeBackupGeneration(layout, json, kind)
            upgradeBackupDirPreference(layout)
            if (kind == BackupFileKind.PRE_IMPORT) return@withContext bytes.size.toLong()
            val master = data.getString("authorMasterTxt")
            writeAuthorGeneration(layout, data, master, documents, serverOwnsMaster, authorSnapshot)
            prefs.edit { it[LAST_RUN] = System.currentTimeMillis() }
            bytes.size.toLong()
        }
    }

    /** 备份代：当前/备份/影库备份.json 与上次/备份/影库备份.json 独立摘要。 */
    private fun writeBackupGeneration(layout: MaintenanceLayout, json: String, kind: BackupFileKind) {
        val name = BackupFileKind.CURRENT.fileName
        writeGenerationFiles(
            currentDir = layout.currentBackups,
            previousDir = layout.previousBackups,
            files = listOf(MaintenanceGenerationFile(name, json)),
            primaryName = name,
            sameData = { previous, next ->
                val previousData = maintenanceBackupDataSignature(previous)
                // 解析失败的旧文件按「数据已变化」处理，宁可多留一代也不当相同数据覆盖。
                previousData != null && previousData == maintenanceBackupDataSignature(next)
            },
            protectExternal = kind == BackupFileKind.AUTOMATIC,
        )
    }

    /**
     * 作者资料代。本机内嵌服务端拥有主表镜像时不重复写主表（docs/domain/migration.md：
     * 避免慢导出快照覆盖服务端刚写入的较新关联）；服务端换代表后由客户端补伴随资料。
     */
    private fun writeAuthorGeneration(
        layout: MaintenanceLayout,
        data: JSONObject,
        master: String,
        documents: List<Pair<String, String>>,
        serverOwnsMaster: Boolean,
        snapshot: Map<String, ByteArray>,
    ) {
        val report = MaintenanceGenerationFile(AUTHOR_REPORT_FILE, maintenanceAuthorReport(data))
        val index = MaintenanceGenerationFile(AUTHOR_INDEX_FILE, org.json.JSONArray(listOf(AUTHOR_MASTER_FILE)).toString())
        if (serverOwnsMaster) {
            // 服务端镜像只写主表与摘要；刷新前快照与本次导出主表不同说明刚完成一次换代，
            // 把快照整代提交为「上次」以保留作者资料.md / 索引。整代提交校验上次目标，
            // 来历不明时抛错保留现场（导出失败，不会留下半代）。
            val previousMaster = snapshot[AUTHOR_MASTER_FILE]?.let { String(it, Charsets.UTF_8) }
            if (previousMaster != null && previousMaster != master) {
                commitGenerationSnapshot(layout.previousAuthors, snapshot)
            }
            // 镜像刷新失败会把主表留在旧内容或缺失：只补缺失的主表，绝不覆盖服务端已写的新主表。
            if (!File(layout.currentAuthors, AUTHOR_MASTER_FILE).isFile) {
                commitGenerationSnapshot(
                    layout.currentAuthors,
                    mapOf(AUTHOR_MASTER_FILE to master.toByteArray(Charsets.UTF_8)),
                )
            }
            writeGenerationFileSet(layout.currentAuthors, listOf(report, index))
            return
        }
        val oldMaster = File(layout.currentAuthors, AUTHOR_MASTER_FILE)
        require(oldMaster.canonicalFile.parentFile == layout.currentAuthors)
        if (oldMaster.isFile) {
            require(oldMaster.length() <= BackupValidator.BACKUP_MAX_BYTES) { "原作者主表超过64MiB，请先移走保留" }
            val old = oldMaster.readText(Charsets.UTF_8)
            val checksum = File(layout.currentAuthors, "$AUTHOR_MASTER_FILE.sha256")
            require(checksum.canonicalFile.parentFile == layout.currentAuthors && checksum.length() <= 1024)
            val imported = documents.any { it.second == old }
            check(!checksum.isFile || old == master || imported || checksum.readText(Charsets.UTF_8).trim() == maintenanceSha256(old.toByteArray(Charsets.UTF_8))) {
                "作者主表已被外部编辑，请先导入主表后再写回"
            }
        }
        writeGenerationFiles(
            currentDir = layout.currentAuthors,
            previousDir = layout.previousAuthors,
            files = listOf(MaintenanceGenerationFile(AUTHOR_MASTER_FILE, master), report, index),
            primaryName = AUTHOR_MASTER_FILE,
        )
    }

    /** 校验并返回 v2 导出里的作者片段（文件名验证先于任何文件写入）。 */
    private fun authorFragmentDocuments(data: JSONObject): List<Pair<String, String>> {
        val fragments = data.optJSONArray("txtFragments")
        val names = mutableSetOf<String>()
        return buildList {
            if (fragments != null) for (index in 0 until fragments.length()) {
                val fragment = fragments.getJSONObject(index)
                val name = fragment.optString("filename").ifBlank { "作者清单.txt" }
                require(safeMaintenanceName(name) && name.endsWith(".txt", true)) { "作者片段文件名不安全" }
                require(names.add(name.lowercase())) { "作者片段文件名重复" }
                add(name to fragment.getString("content"))
            }
        }
    }

    /**
     * 刷新本机内嵌服务端的作者主表镜像，返回服务端是否拥有主表写权。
     * 镜像路径升级为两代布局的「当前/作者资料/作者总表.txt」；旧布局路径在此一次性迁移配置，
     * 旧目录里的文件按兼容约定保留（服务端镜像只改配置，不搬文件）。
     */
    private suspend fun refreshManagedMaster(layout: MaintenanceLayout, kind: BackupFileKind): Boolean {
        val target = factory.currentBaseUrl()
        if (!ServerAddress.isLocalModePreset(target)) return false
        val api = factory.forServer(target).create()
        val mirror = api.apiV1AuthorsMirrorGet()
        if (mirror.mode != AuthorMirrorConfig.Mode.master) return false
        val expectedPath = File(layout.currentAuthors, AUTHOR_MASTER_FILE).canonicalPath
        val legacyPath = File(File(layout.root, AUTHORS_DIR), AUTHOR_MASTER_FILE).canonicalPath
        if (mirror.path != expectedPath && mirror.path != legacyPath) return false
        if (kind != BackupFileKind.PRE_IMPORT) {
            api.apiV1AuthorsMirrorPut(masterMirrorConfig(expectedPath))
        }
        return true
    }

    suspend fun authorDocuments(): List<AuthorTxtDocument> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val folder = checkedSharedFolder(root.first() ?: error("请先选择维护根目录"))
            val layout = ensureMaintenanceLayout(folder)
            val documents = readAuthorDocuments(layout.currentAuthors)
            if (documents.isNotEmpty()) return@withContext documents
            // 升级后尚未迁移旧布局时，继续从旧「作者资料」读取，避免作者TXT导入入口空掉。
            val legacy = File(folder, AUTHORS_DIR)
            if (legacy.isDirectory) readAuthorDocuments(legacy) else emptyList()
        }
    }

    private suspend fun readAuthorDocuments(directory: File): List<AuthorTxtDocument> {
        val indexFile = File(directory, AUTHOR_INDEX_FILE)
        require(indexFile.canonicalFile.parentFile == directory.canonicalFile)
        val files = if (indexFile.isFile) {
            require(indexFile.length() <= 1024 * 1024) { "作者片段索引过大" }
            val index = org.json.JSONArray(indexFile.readText(Charsets.UTF_8))
            (0 until index.length()).map { entry ->
                val name = index.getString(entry)
                require(safeMaintenanceName(name) && name.endsWith(".txt", true)) { "作者片段索引文件名不安全" }
                File(directory, name)
            }
        } else (directory.listFiles() ?: error("读取作者资料目录失败"))
            .filter {
                it.isFile && it.name.endsWith(".txt", true) &&
                    !it.name.endsWith("（上次）.txt") && !it.name.endsWith("（上上次）.txt")
            }
            .sortedBy { it.name }
        return files.map { file ->
            require(file.canonicalFile.parentFile == directory) { "作者文件超出目录" }
            readAuthorTxtDocument(file.name, file.length()) { file.inputStream() }
        }
    }

    /** 维护根布局：创建缺失的两代目录/上传归档、写入目录说明、升级旧备份目录偏好。 */
    private suspend fun ensureMaintenanceLayout(folder: File): MaintenanceLayout {
        childDirectory(folder, ARCHIVE_DIR)
        val currentRoot = childDirectory(folder, CURRENT_GENERATION_DIR)
        val previousRoot = childDirectory(folder, PREVIOUS_GENERATION_DIR)
        val layout = MaintenanceLayout(
            root = folder,
            currentAuthors = childDirectory(currentRoot, AUTHORS_DIR),
            previousAuthors = childDirectory(previousRoot, AUTHORS_DIR),
            currentBackups = childDirectory(currentRoot, BACKUP_DIR),
            previousBackups = childDirectory(previousRoot, BACKUP_DIR),
            archive = File(folder, ARCHIVE_DIR),
        )
        val readme = File(folder, MAINTENANCE_README_FILE)
        require(readme.canonicalFile.parentFile == folder.canonicalFile) { "目录说明超出维护目录" }
        val currentReadme = if (readme.isFile && readme.length() <= README_MAX_BYTES) {
            runCatching { readme.readText(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        }
        if (currentReadme != MAINTENANCE_README) {
            writeMaintenanceFile(folder, MAINTENANCE_README_FILE, MAINTENANCE_README)
        }
        return layout
    }

    /**
     * 只把本应用此前写入的旧维护备份目录升级到当前代目录；用户若改选了 SAF 备份目录
     * （AutoBackupRunner 的独立槽）则不抢回，保持既有「明确选择才切换」语义。
     *
     * 只有当前代已有备份文件时才改指：升级后首次导出前继续按旧偏好读取旧目录最后一份
     * 备份，避免「偏好指向空目录导致导入找不到旧备份」；旧文件本身仍原样保留。
     */
    private suspend fun upgradeBackupDirPreference(layout: MaintenanceLayout) {
        val saved = prefs.data.map { it[BACKUP_URI] }.first()
        if (saved != layout.legacyBackupUri) return
        if (!File(layout.currentBackups, BackupFileKind.CURRENT.fileName).isFile) return
        prefs.edit { it[BACKUP_URI] = layout.currentBackupUri }
    }

    private fun requireAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            check(Environment.isExternalStorageManager()) { "请先授予所有文件访问权限" }
        }
    }

    private fun masterMirrorConfig(path: String): AuthorMirrorConfig = AuthorMirrorConfig(
        path = path,
        fragmentFilename = AUTHOR_MASTER_FILE,
        mode = AuthorMirrorConfig.Mode.master,
    )

    private companion object {
        val ROOT = stringPreferencesKey("maintenance_root_path")
        val BACKUP_URI = stringPreferencesKey("backup_auto_dir_uri")
        val ARCHIVE_PATH = stringPreferencesKey("upload_archive_path")
        val LAST_RUN = longPreferencesKey("backup_auto_last_run_ms")
        const val README_MAX_BYTES = 64L * 1024
    }
}

/** 维护根下的两代布局路径（只做路径推导；创建/权限校验分别由调用方负责）。 */
private data class MaintenanceLayout(
    val root: File,
    val currentAuthors: File,
    val previousAuthors: File,
    val currentBackups: File,
    val previousBackups: File,
    val archive: File,
) {
    val currentBackupUri: String get() = Uri.fromFile(currentBackups).toString()
    val legacyBackupUri: String get() = Uri.fromFile(File(root, BACKUP_DIR)).toString()
}

/** 纯路径推导，不创建目录（只读核对/偏好比较用）。 */
private fun maintenanceLayout(folder: File): MaintenanceLayout = MaintenanceLayout(
    root = folder,
    currentAuthors = File(File(folder, CURRENT_GENERATION_DIR), AUTHORS_DIR),
    previousAuthors = File(File(folder, PREVIOUS_GENERATION_DIR), AUTHORS_DIR),
    currentBackups = File(File(folder, CURRENT_GENERATION_DIR), BACKUP_DIR),
    previousBackups = File(File(folder, PREVIOUS_GENERATION_DIR), BACKUP_DIR),
    archive = File(folder, ARCHIVE_DIR),
)

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

/**
 * 维护目录说明（写入维护根，随布局更新；旧三代文件保留说明在末尾）。
 * 面向用户阅读，勿写实现细节或本机路径；与 android/README.md 的维护目录章节同口径。
 */
internal const val MAINTENANCE_README = """# 影库资料维护

这里统一保存作者资料、数据备份和上传归档。换设备时，先同步媒体，再导入完整备份。

## 文件夹一览

| 文件夹 | 你会用到的内容 | 日常操作 |
| --- | --- | --- |
| 当前/作者资料 | 作者总表.txt、作者资料.md | TXT维护作者与作品关联，Markdown阅读简介、来源和作品清单 |
| 当前/备份 | 影库备份.json | 迁移作者、头像、标签、浏览记录和推荐偏好 |
| 上次 | 上一代作者资料和完整备份 | 需要回退时按需复制，程序不会自动清理 |
| 上传归档 | 按媒体库和目标目录保存的原件副本 | 查看已经上传成功的文件 |

## 当前与上次两代

| 内容 | 当前代 | 上次代 |
| --- | --- | --- |
| 作者主表 | 当前/作者资料/作者总表.txt | 上次/作者资料/作者总表.txt |
| 完整备份 | 当前/备份/影库备份.json | 上次/备份/影库备份.json |

- 手动导出、自动备份、导入前保存共用这两代：内容变化时先把整个「当前」提交为「上次」，再写新的「当前」；内容没变时只刷新当前，不挤掉上次。
- 每份TXT和备份都有同名.sha256校验文件，复制时一起带走，无需手动编辑；摘要不符或文件不可读时程序拒绝覆盖，并保留现场。
- 作者资料.md是当前阅读版，作者片段索引.json供程序定位主表。原始TXT保存在完整备份内部，目录不额外堆积片段。
- 自动保存发现备份被外部替换会暂停，请先在界面导入待恢复文件，再更新备份。
- 确认导入前保存本机现状，导入完成后将合并结果导出为最新当前份。恢复仍按v2合并规则执行。
- 完整备份不含媒体原件。上传归档留在维护根，不参与两代轮换，沿用原有按库归档和同名保护规则。
- 旧版本的同目录三代文件（如作者总表（上次）.txt）保留在原处，程序不会自动删除；需要时请先核对摘要，再手工复制到「当前」「上次」。
- Web在页面打开且目录授权有效时自动写回，关闭后将在下次打开补齐。
"""
