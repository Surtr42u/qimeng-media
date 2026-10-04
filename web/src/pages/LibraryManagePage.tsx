import { useNavigate } from 'react-router'
import { BackupIcon, FolderMonitorIcon, TxtDocIcon, UploadIcon } from '@/components/shell/icons'
import { MAINTENANCE_FILES_VOCAB_EDIT_PATH, MAINTENANCE_FILES_VOCABULARY_PATH } from '@/lib/route-keys'

/**
 * 数据管理 hub（维护页「文件管理」入口卡 → /app/maintenance/files；原单页
 * 「文件管理」改造：五块内容拆为四张入口卡 + 四个子页，2026-09-17 用户拍板
 * 「像手机版那样区分，先归类好，web 不做内容改动」——卡标题/副文案逐字对齐
 * App 数据管理 hub（DataManageScreen.kt 常量），搬移内容零改动只做信息架构
 * 拆分。子页 = upload（上传工作台+目录浏览）/ vocabulary（来源词表+作者镜像，
 * 2026-09-29 自上传页再拆）/ libraries（库表格+注册表单）/ authors-txt（作者
 * TXT 导入卡）/ backup（备份导入导出卡），各子页均挂在 router.tsx
 * maintenance/files 同层。「同步浏览数据」行本批次不做（用户另行拍板中）。
 * 卡片样式复用原型 entry-grid/entry-card--link（维护页入口卡同款）。
 */

/** hub 入口卡（upload 起四张顺序 = App 数据管理行序，标题/副文案逐字抄
 *  DataManageScreen.kt；vocabulary 为 Web 端拆分卡，紧随上传文件放置——
 *  词表/镜像服务上传挂靠场景） */
const HUB_ENTRIES = [
  { title: '上传文件', sub: '选择本地图片和视频上传到媒体库', to: '/app/maintenance/files/upload', Icon: UploadIcon },
  { title: '来源词表', sub: '通用来源建议词与作者总表镜像维护', to: MAINTENANCE_FILES_VOCABULARY_PATH, Icon: TxtDocIcon },
  { title: '词表维护', sub: '编辑出处组与停用词', to: MAINTENANCE_FILES_VOCAB_EDIT_PATH, Icon: TxtDocIcon },
  { title: '库管理', sub: '注册媒体目录，重扫、启停与删除媒体库', to: '/app/maintenance/files/libraries', Icon: FolderMonitorIcon },
  { title: '作者 TXT 导入', sub: '导入旧项目作者清单并重建关联', to: '/app/maintenance/files/authors-txt', Icon: TxtDocIcon },
  { title: '备份导入导出', sub: '备份导入恢复与浏览数据同步', to: '/app/maintenance/files/backup', Icon: BackupIcon },
] as const

export default function LibraryManagePage() {
  const navigate = useNavigate()

  return (
    <div className="page" id="page-maintenance-files">
      <div className="page-head">
        <h2>数据管理</h2>
        <p>上传文件、库管理、来源词表、词表维护、作者 TXT 导入与备份导入导出</p>
      </div>

      <div className="entry-grid">
        {HUB_ENTRIES.map(({ title, sub, to, Icon }) => (
          <div key={to} className="entry-card entry-card--link" onClick={() => navigate(to)}>
            <div className="entry-head">
              <Icon />
              <h3>{title}</h3>
            </div>
            <p>{sub}</p>
          </div>
        ))}
      </div>
    </div>
  )
}
