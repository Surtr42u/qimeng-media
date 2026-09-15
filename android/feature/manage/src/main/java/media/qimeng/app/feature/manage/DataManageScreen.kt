package media.qimeng.app.feature.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

/** 页面文案（U10-6 拍板：hub 标题「数据管理」；五行分类入口=上传文件/库管理/作者 TXT 导入/
 *  备份导入导出/缩略图缓存——前两行副文案概括各自子页承担的事；中间两行 U10-6b 追加，副文案
 *  自拟对齐 Web 文件管理页 TxtAuthorImportCard/BackupCard 语义。2026-09-15 用户反馈「作者txt
 *  和备份的介绍太长没和其他的视觉对齐」：后两行副文案压缩回 ≤15 字单行节奏，细节由子页承载。
 *  2026-09-16 用户反馈：末行追加「缩略图缓存」入口，进生成进度与缓存上限合并子页） */
private const val HUB_TITLE = "数据管理"
private const val HUB_ROW_UPLOAD = "上传文件"
private const val HUB_ROW_UPLOAD_SUBTITLE = "选择本地图片和视频上传到媒体库"
private const val HUB_ROW_LIBRARY = "库管理"
private const val HUB_ROW_LIBRARY_SUBTITLE = "注册媒体目录，重扫、启停与删除媒体库"
private const val HUB_ROW_AUTHOR_TXT = "作者 TXT 导入"
private const val HUB_ROW_AUTHOR_TXT_SUBTITLE = "导入旧项目作者清单并重建关联"
private const val HUB_ROW_BACKUP = "备份导入导出"
private const val HUB_ROW_BACKUP_SUBTITLE = "备份导入恢复与浏览数据同步"
private const val HUB_ROW_THUMB_CACHE = "缩略图缓存"
private const val HUB_ROW_THUMB_CACHE_SUBTITLE = "生成进度与缓存上限"

/** 16dp：hub 内容水平内边距（对齐上传子页 16dp 档；区别于我的页 20dp 档） */
private val HubContentPadding = 16.dp

/**
 * 数据管理 hub（U10-6）：「我的」页合并入口的二级分类页。合并范围 = 服务端内容管理类
 * 功能：上传文件（复用既有 Routes.UPLOAD 页，本页只做入口跳转不搬路由）+ 库管理
 * （注册媒体目录/重扫/启停/删除）+ 作者 TXT 导入/备份导入导出（U10-6b 追加两行，
 * 各进独立子页）+ 缩略图缓存（2026-09-16 用户反馈追加一行）。
 * 收藏/点赞/浏览历史/作者总览为个人数据域，U10-6 拍板明确不合并。
 * 信息架构基准 = Web 文件管理页（LibraryManagePage.tsx），
 * 视觉/交互基准 = App 上传子页（QimengTopBar + 16dp 滚动列）。
 */
@Composable
fun DataManageScreen(
    onBack: () -> Unit,
    onOpenUpload: () -> Unit,
    onOpenLibraryManage: () -> Unit,
    onOpenAuthorTxt: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenThumbCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        QimengTopBar(title = HUB_TITLE, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HubContentPadding),
            verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceL),
        ) {
            HubEntryRow(
                label = HUB_ROW_UPLOAD,
                subtitle = HUB_ROW_UPLOAD_SUBTITLE,
                onClick = onOpenUpload,
                modifier = Modifier.padding(top = QimengDimens.SpaceL),
            )
            HubEntryRow(
                label = HUB_ROW_LIBRARY,
                subtitle = HUB_ROW_LIBRARY_SUBTITLE,
                onClick = onOpenLibraryManage,
            )
            HubEntryRow(
                label = HUB_ROW_AUTHOR_TXT,
                subtitle = HUB_ROW_AUTHOR_TXT_SUBTITLE,
                onClick = onOpenAuthorTxt,
            )
            HubEntryRow(
                label = HUB_ROW_BACKUP,
                subtitle = HUB_ROW_BACKUP_SUBTITLE,
                onClick = onOpenBackup,
            )
            HubEntryRow(
                label = HUB_ROW_THUMB_CACHE,
                subtitle = HUB_ROW_THUMB_CACHE_SUBTITLE,
                onClick = onOpenThumbCache,
            )
        }
    }
}

/**
 * hub 入口行（模块私有件）：规格复刻 feature/settings SettingsScreen.EntryRow
 * （16dp 圆角 surface 卡、72dp 行高、18dp 水平 padding、标题+副文案单块两行 15sp 主文字色）。
 * 该组件为 settings 模块 private、core:ui 无对应件，跨模块复用只能复制规格（非业务逻辑复制，
 * U10-6 记档）；后续出现第三处消费方时应上提 core:ui 收单源，禁止三处平行实现。
 */
@Composable
private fun HubEntryRow(
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
                .padding(horizontal = QimengDimens.ProfileRowHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 单块两行文本（同 settings EntryRow：标题+副标题同字号同色，旧版单 TextView 语义）
            Text(
                text = "$label\n$subtitle",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
