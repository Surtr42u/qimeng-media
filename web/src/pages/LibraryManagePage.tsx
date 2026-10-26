import { Link } from 'react-router'
import { ArchiveRestore, ArrowUpRight, BookOpen, Database, FolderOpen, FolderSearch, Tags, Upload, Users } from 'lucide-react'
import { ManagePageHeader } from '@/components/manage/ManagePageHeader'
import { MAINTENANCE_FILES_BROWSER_PATH, MAINTENANCE_FILES_VOCAB_EDIT_PATH, MAINTENANCE_FILES_VOCABULARY_PATH, MAINTENANCE_LIBRARIES_PATH, MAINTENANCE_UPLOAD_PATH } from '@/lib/route-keys'

/** 按用户任务组织入口；日常入库优先，词表/迁移仍走原有页面与协议。 */
const GROUPS = [
  { title: '入库与整理', note: '添加新文件，或整理已经入库的内容', intake: true, entries: [
    { title: '上传文件', sub: '选择目标库与目录，指定批次作者和来源，选入即上传', to: MAINTENANCE_UPLOAD_PATH, Icon: Upload, primary: true, badge: '日常使用' },
    { title: '库管理', sub: '登记服务器文件夹、扫描与启停媒体库', to: MAINTENANCE_LIBRARIES_PATH, Icon: Database },
    { title: '文件整理', sub: '浏览已入库目录、重命名、移动与移入回收站', to: MAINTENANCE_FILES_BROWSER_PATH, Icon: FolderOpen },
  ] },
  { title: '作者与词表', note: '维护人物、获取来源和作品检索规则', entries: [
    { title: '作者管理', sub: '查看作者、关注状态和已有媒体关联', to: '/app/authors', Icon: Users },
    { title: '来源词表与镜像', sub: '获取渠道建议词、作者清单的单向导出镜像', to: MAINTENANCE_FILES_VOCABULARY_PATH, Icon: Tags },
    { title: '词表维护', sub: '出处组、角色别名与停用词，不是获取渠道', to: MAINTENANCE_FILES_VOCAB_EDIT_PATH, Icon: BookOpen },
  ] },
  { title: '迁移与恢复', note: '低频操作单独放置，先核对范围再执行', entries: [
    { title: '作者 TXT 导入', sub: '导入作者文件清单，并按片段重建关联', to: '/app/maintenance/files/authors-txt', Icon: FolderSearch },
    { title: '备份导入导出', sub: '当前 v2 数据记录与关联；不包含媒体原件', to: '/app/maintenance/files/backup', Icon: ArchiveRestore },
    { title: '回收站', sub: '找回误删文件；彻底删除在此独立确认', to: '/app/maintenance/trash', Icon: ArchiveRestore },
  ] },
] as const

export default function LibraryManagePage() {
  return <div className="page manage-workspace" id="page-maintenance-files">
    <ManagePageHeader title="数据管理" description="把文件入库、整理与关联维护放在清晰的动线上" backTo="/app/maintenance" backLabel="服务与维护" />
    {GROUPS.map(group => <section className="manage-hub-group" key={group.title}>
      <div className="manage-group-head"><h3>{group.title}</h3><p>{group.note}</p></div>
      <div className={`manage-hub-grid${'intake' in group ? ' manage-hub-grid--intake' : ''}`}>{group.entries.map(entry => <Link className={`entry-card manage-hub-entry${'primary' in entry ? ' manage-hub-entry--primary' : ''}`} key={entry.to} to={entry.to}>
        <div className="manage-entry-top"><entry.Icon aria-hidden="true" />{'badge' in entry && <span className="monitor-update">{entry.badge}</span>}<ArrowUpRight aria-hidden="true" /></div>
        <h3>{entry.title}</h3><p>{entry.sub}</p>
      </Link>)}</div>
    </section>)}
  </div>
}
