/**
 * 跨页面共享的展示文案常量（页面级共享；list 见下）。
 *
 * 【归属待定】M2 任务约定 src/lib 为禁止改动目录，本模块暂放 pages/_shared，
 * 待主 AI 集成时确认归属（候选 src/lib/constants.ts 或 format.ts）后整体平移。
 */

import type { Library, ScanProgressEvent } from '@/api/generated'

/**
 * 库扫描状态（generated 未导出独立类型，从 Library.scanState 内联联合派生——
 * 协议侧扩展枚举值时此处类型自动跟随，但下方两个 Record 需同步补分支）。
 */
export type LibraryScanState = NonNullable<Library['scanState']>

/** 出处分区 name=null 的兜底标签（协议 SourceCount.name 可为 null；assets query 的 source='其他' 同字面量） */
export const UNKNOWN_SOURCE_LABEL = '其他'

/** 目录树根节点显示标签（库根相对路径=""，协议 DirTree.path 根节点为空串） */
export const ROOT_DIR_LABEL = '／库根'

/** 扫描进度 phase → 中文（协议 ScanProgressEvent.phase：walking/reconciling） */
export const SCAN_PHASE_LABELS: Record<ScanProgressEvent['phase'], string> = {
  walking: '遍历文件',
  reconciling: '比对入库',
}

/** 扫描状态 → 中文标签（StatsPage 库子卡与 AdminPage 库管理卡共用） */
export const SCAN_STATE_LABELS: Record<LibraryScanState, string> = {
  idle: '空闲',
  scanning: '扫描中',
  error: '出错',
}

/**
 * 扫描状态 → 徽标样式（Badge className）。
 * tokens.css 是中性色系且无"成功/蓝色"语义 token（tokens.css 禁止改动），
 * scanning 以主色衬底 + 脉冲表达"运行中"语义；后续品牌化（tokens 增加语义色）时替换。
 */
export const SCAN_STATE_BADGE_CLASSES: Record<LibraryScanState, string> = {
  idle: 'bg-[var(--qm-chip-bg)] text-[var(--qm-text-muted)]',
  scanning: 'bg-[var(--qm-primary-soft)] text-[var(--qm-primary)] animate-pulse',
  error: 'bg-[var(--qm-destructive)]/10 text-[var(--qm-destructive)]',
}
