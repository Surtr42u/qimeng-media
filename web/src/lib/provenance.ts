/**
 * provenance.ts：关联记录溯源的展示映射（ADR-0032 详情面读取）。
 *
 * 为什么在 lib 不在组件：UI 组件零业务规则（ADR-0008）——origin→中文词表
 * 映射与「不可考不显示标记」判定属展示规则，收敛在逻辑层，组件只渲染
 * 返回的注记字符串。
 *
 * 词表同步责任：与服务端受控词表单源 server/internal/store/provenance.go、
 * 协议侧 api/openapi.yaml（AssetDetailTag/AssetDetailAuthor 的 origin 字段
 * 说明）三方双写——协议侧改动须同步此处，反之亦然（AI_README_FIRST 代码
 * 卫生约束 3）。
 */
import { formatDateTime } from './format'

/** 关联条目溯源两字段的公共形状（详情响应 AssetDetailTag/AssetDetailAuthor 同型） */
export interface ProvenanceInfo {
  origin?: string | null
  createdAtMillis?: number | null
}

// origin → 用户面中文标签（ADR-0032 写入通道受控词表）。
// legacy = 0016 迁移回填哨兵 = 不可考：不产出标签（与 null 同待遇，不伪造）。
const ORIGIN_LABELS: Record<string, string> = {
  client: '客户端',
  txt: 'TXT 导入',
  import: '备份导入',
  'local-sync': '本机同步',
  scanner: '扫描器',
}

/**
 * 溯源注记「客户端 · 10-1 12:30」（时间格式复用 formatDateTime 口径）。
 * 完全不可考（无词表内 origin 且无有效时间）返回 null——调用方不渲染任何
 * 标记；createdAtMillis ≤0（epoch 等哨兵）与缺省同待遇（DOMAIN_RULES §10）。
 */
export function provenanceNote(p?: ProvenanceInfo | null): string | null {
  if (!p) return null
  const label = p.origin ? (ORIGIN_LABELS[p.origin] ?? null) : null // 词表外值不显示（宁不可考不造假）
  const time = formatDateTime(p.createdAtMillis)
  if (label && time) return `${label} · ${time}`
  return label || time || null
}
