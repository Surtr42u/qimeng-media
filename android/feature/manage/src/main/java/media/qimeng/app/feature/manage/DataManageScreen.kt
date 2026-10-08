package media.qimeng.app.feature.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.icon.ArchiveBoxIcon
import media.qimeng.app.core.ui.icon.BackupRestoreIcon
import media.qimeng.app.core.ui.icon.ChevronRightIcon
import media.qimeng.app.core.ui.icon.CloudUploadIcon
import media.qimeng.app.core.ui.icon.FolderManagedIcon
import media.qimeng.app.core.ui.icon.StorageCacheIcon
import media.qimeng.app.core.ui.icon.TxtImportFileIcon
import media.qimeng.app.core.ui.icon.VocabularyBookIcon
import media.qimeng.app.core.ui.theme.QimengDimens

/** 页面文案（信息架构重组：三级语义分组，告别扁平堆积） */
private const val HUB_TITLE = "数据管理"

private const val SECTION_CONTENT = "媒体与内容"
private const val HUB_ROW_UPLOAD = "上传文件"
private const val HUB_ROW_UPLOAD_SUBTITLE = "选择本地图片和视频上传到媒体库"
private const val HUB_ROW_LIBRARY = "库管理"
private const val HUB_ROW_LIBRARY_SUBTITLE = "注册媒体目录，重扫、启停与删除媒体库"

private const val SECTION_RULES = "规则与同步"
private const val HUB_ROW_VOCAB_EDIT = "词表维护"
private const val HUB_ROW_VOCAB_EDIT_SUBTITLE = "编辑出处组、角色与停用词"
private const val HUB_ROW_BACKUP = "备份导入导出"
private const val HUB_ROW_BACKUP_SUBTITLE = "备份导入恢复与词表合并同步"

private const val SECTION_STORAGE = "本地存储与工具"
private const val HUB_ROW_THUMB_CACHE = "缩略图缓存"
private const val HUB_ROW_THUMB_CACHE_SUBTITLE = "本地生成进度与缓存上限占用"
private const val HUB_ROW_INBOX = "上传归档文件夹"
private const val HUB_ROW_INBOX_SUBTITLE = "上传成功后源文件的移动归档目录"
private const val HUB_ROW_AUTHOR_TXT = "作者 TXT 导入"
private const val HUB_ROW_AUTHOR_TXT_SUBTITLE = "导入旧项目作者清单并重建关联"

/** 16dp：hub 内容水平内边距 */
private val HubContentPadding = 16.dp

/** 24dp：滚动列尾部垫高 */
private val ScreenBottomSpacing = 24.dp

/**
 * 数据管理 hub：「我的」页合并入口的二级分类页。
 * 按照「媒体与内容流转 / 规则与数据同步 / 本地存储与工具」三个心智维度分组，
 * 解决随功能增长产生的扁平堆叠与交互认知负荷。
 */
@Composable
fun DataManageScreen(
    onBack: () -> Unit,
    onOpenUpload: () -> Unit,
    onOpenLibraryManage: () -> Unit,
    onOpenAuthorTxt: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenThumbCache: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenVocabularyEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        QimengTopBar(title = HUB_TITLE, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HubContentPadding),
            verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        ) {
            Spacer(modifier = Modifier.height(QimengDimens.SpaceS))

            // —— 1. 媒体与内容流转 ——
            ManageSectionHeader(title = SECTION_CONTENT)
            HubEntryRow(
                icon = CloudUploadIcon,
                label = HUB_ROW_UPLOAD,
                subtitle = HUB_ROW_UPLOAD_SUBTITLE,
                onClick = onOpenUpload,
            )
            HubEntryRow(
                icon = FolderManagedIcon,
                label = HUB_ROW_LIBRARY,
                subtitle = HUB_ROW_LIBRARY_SUBTITLE,
                onClick = onOpenLibraryManage,
            )

            // —— 2. 规则与数据同步 ——
            ManageSectionHeader(title = SECTION_RULES)
            HubEntryRow(
                icon = VocabularyBookIcon,
                label = HUB_ROW_VOCAB_EDIT,
                subtitle = HUB_ROW_VOCAB_EDIT_SUBTITLE,
                onClick = onOpenVocabularyEdit,
            )
            HubEntryRow(
                icon = BackupRestoreIcon,
                label = HUB_ROW_BACKUP,
                subtitle = HUB_ROW_BACKUP_SUBTITLE,
                onClick = onOpenBackup,
            )

            // —— 3. 本地存储与工具 ——
            ManageSectionHeader(title = SECTION_STORAGE)
            HubEntryRow(
                icon = StorageCacheIcon,
                label = HUB_ROW_THUMB_CACHE,
                subtitle = HUB_ROW_THUMB_CACHE_SUBTITLE,
                onClick = onOpenThumbCache,
            )
            HubEntryRow(
                icon = ArchiveBoxIcon,
                label = HUB_ROW_INBOX,
                subtitle = HUB_ROW_INBOX_SUBTITLE,
                onClick = onOpenInbox,
            )
            HubEntryRow(
                icon = TxtImportFileIcon,
                label = HUB_ROW_AUTHOR_TXT,
                subtitle = HUB_ROW_AUTHOR_TXT_SUBTITLE,
                onClick = onOpenAuthorTxt,
            )

            Spacer(modifier = Modifier.height(ScreenBottomSpacing))
        }
    }
}

/** 分组小标题 */
@Composable
private fun ManageSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = 4.dp,
            top = QimengDimens.SpaceM,
            bottom = 2.dp,
        ),
    )
}

/**
 * hub 入口行：
 * 采用「前缀圆角图标容器 + 主副标题两行 + 尾部轻箭头」的标准移动端设置中枢规格，
 * 视觉呼吸感强，分类清晰。
 */
@Composable
private fun HubEntryRow(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = QimengDimens.ProfileRowHeight)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标容器：轻度 primaryContainer 底色
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // 主标题与副标题两行
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 右侧导向箭头
            Icon(
                imageVector = ChevronRightIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
