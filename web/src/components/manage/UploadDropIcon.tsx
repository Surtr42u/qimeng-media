/**
 * 上传导入卡的上传箭头图标（BackupCard / TxtAuthorImportCard 两卡共用）。
 * 2026-09-20 全库审查抽离：同一段内联 SVG（含 style）此前在两卡逐字复制
 * 两份——代码卫生约束 6「第 2 次出现即抽共享件」。视觉零变化（逐字搬运）。
 */

export function UploadDropIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      style={{ width: 22, height: 22, display: 'block', margin: '0 auto 6px', color: 'var(--qm-primary)' }}
      aria-hidden="true"
    >
      <path d="M12 16V4" />
      <path d="m6 9 6-6 6 6" />
      <path d="M4 20h16" />
    </svg>
  )
}
