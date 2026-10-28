package media.qimeng.app.feature.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import media.qimeng.app.core.ui.component.QimengPageIntro
import media.qimeng.app.core.ui.component.QimengSettingsGroup
import media.qimeng.app.core.ui.component.QimengSettingsRow
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.icon.ArchiveBoxIcon
import media.qimeng.app.core.ui.icon.BackupRestoreIcon
import media.qimeng.app.core.ui.icon.CloudUploadIcon
import media.qimeng.app.core.ui.icon.FolderManagedIcon
import media.qimeng.app.core.ui.icon.StorageCacheIcon
import media.qimeng.app.core.ui.icon.TxtImportFileIcon
import media.qimeng.app.core.ui.icon.VocabularyBookIcon
import media.qimeng.app.core.ui.theme.QimengDimens
import media.qimeng.app.core.ui.theme.softDaylightBackground

/** 按任务组织已有入口，与 Web 管理工作区共用入库/关联/恢复的层级。 */
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
    Column(modifier = modifier.fillMaxSize().softDaylightBackground()) {
        QimengTopBar(title = "数据管理", onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
                .padding(bottom = QimengDimens.EmptyStateVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        ) {
            QimengPageIntro("从添加作品到整理和备份，照顾好你的媒体库。")
            QimengSettingsGroup("入库与整理") {
                QimengSettingsRow(CloudUploadIcon, "上传文件", "从系统文件中选择图片和视频", onOpenUpload)
                QimengSettingsRow(FolderManagedIcon, "库管理", "注册目录、扫描内容与管理媒体库", onOpenLibraryManage)
                QimengSettingsRow(ArchiveBoxIcon, "上传归档文件夹", "设置归档目录，按库名一键上传", onOpenInbox)
            }
            QimengSettingsGroup("词表与关联") {
                QimengSettingsRow(VocabularyBookIcon, "词表维护", "编辑出处、角色与停用词", onOpenVocabularyEdit)
                QimengSettingsRow(TxtImportFileIcon, "作者 TXT 导入", "导入作者清单并重建关联", onOpenAuthorTxt)
            }
            QimengSettingsGroup("备份与存储") {
                QimengSettingsRow(BackupRestoreIcon, "备份导入导出", "恢复备份、同步浏览记录与词表", onOpenBackup)
                QimengSettingsRow(StorageCacheIcon, "缩略图缓存", "查看生成进度，管理本机缓存空间", onOpenThumbCache)
            }
        }
    }
}
