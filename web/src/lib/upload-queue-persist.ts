/**
 * 上传队列持久化核心（纯函数 + 存储端口；HANDOVER §5 待办#2「大文件上传中刷新
 * 丢整条」的落地面）。本文件零 IO：存储由端口注入（浏览器 IndexedDB 实现在
 * lib/upload-queue-persist-instance 装配，模式同打点账本 event-ledger /
 * ledger-instance 双文件），lib 纯函数单测（node 环境）无需 IndexedDB。
 *
 * 选型记档（AI_README_FIRST「选型约束」决策序）：原生 IndexedDB（决策序第 1 级
 * 平台机制）——浏览器自带的异步大容量键值存储，既有先例=打点账本
 *（lib/ledger-instance.ts，2026-09「Web 事件账本（IndexedDB）」）；不引 idb-keyval
 * 等小库（平台机制已命中，决策序命中即停），零新依赖。
 *
 * 平台客观限制（如实口径，禁止伪造续传假象）：文件拾取链路是
 * input[type=file] + DataTransfer entry（UploadWorkbench / lib/folder-upload），
 * 拿到的是 File 快照句柄，不跨页面存活——刷新后句柄必然失效，无法自动续传。
 * 恢复条目一律落「需重新选择文件」态（needs-file），用户重选同名文件后才按
 * 持久化的分片断点续传（分片通道，服务端会话为准）。未采用 File System
 * Access API 句柄恢复：现有拾取链路不基于它，按行为目标不做新拾取机制。
 */

/** 持久化的挂靠载荷（与 store 的 UploadAttach 结构同形；独立声明避免 store↔
 *  本模块类型循环依赖，结构化兼容由 TS 保证） */
interface PersistedAttach {
  authorId: string
  authorName: string
  sources: string[]
}

/** 持久化队列条目（可序列化纯数据；不含 File 句柄与运行态 status——恢复条目
 *  一律按 needs-file 落位，percent 由 chunkOffset 推导不双写） */
export interface PersistedUploadItem {
  /** 队列条目 id（= IndexedDB 主键；恢复后沿用原 id，持久化更新可原位覆盖） */
  id: string
  /** 落库名（编辑基名 + 锁定扩展名，与 UploadItem.name 同口径） */
  name: string
  /** 原文件名（仅编辑过基名时存在；重选文件的同名判定基准=originalName ?? name） */
  originalName?: string
  sizeBytes: number
  /** 目标库 ID 快照 */
  targetLibraryId: string
  /** 目标目录快照（'' = 库根） */
  targetDir: string
  /** 201 后自动挂靠载荷快照（缺省 = 不挂靠） */
  attach?: PersistedAttach
  /** 分片会话 id（null = 直传条目，或分片会话尚未建立/已失效重置） */
  sessionId: string | null
  /** 已传分片偏移（字节，服务端权威 offset 的最近一次本地快照；直传恒 0） */
  chunkOffset: number
  /** 入队时刻（epoch ms） */
  createdAt: number
  /** 最近推进时刻（epoch ms） */
  updatedAt: number
}

/** 存储端口：浏览器实现 = IndexedDB（keyPath id），单测与降级 = 内存 Map */
export interface UploadQueueStorage {
  getAll(): Promise<PersistedUploadItem[]>
  put(item: PersistedUploadItem): Promise<void>
  delete(id: string): Promise<void>
}

/** 重选文件后的续传决策（同名同大小才恢复；有会话 id 走会话续传，否则从头） */
export type ResumeDecision =
  | { kind: 'resume-session'; sessionId: string }
  | { kind: 'restart' }
  | { kind: 'mismatch' }

/** 重选文件续传决策（纯函数）：名称或大小任一不符 = mismatch（拒绝恢复，防止
 *  把别的文件传进原条目的目标位置）；同名录且留有分片会话 = resume-session
 *（既有 GET 探测→PATCH 续传路径，服务端 offset 为唯一真相源）；无会话（直传
 *  条目 / 会话未建立）= restart（同一队列条目从头重传，直传通道本无断点） */
export function decideResume(
  record: Pick<PersistedUploadItem, 'name' | 'originalName' | 'sizeBytes' | 'sessionId'>,
  picked: { name: string; size: number },
): ResumeDecision {
  const expectedName = record.originalName ?? record.name
  if (picked.name !== expectedName || picked.size !== record.sizeBytes) return { kind: 'mismatch' }
  if (record.sessionId !== null) return { kind: 'resume-session', sessionId: record.sessionId }
  return { kind: 'restart' }
}

/** 恢复条目形态（store 落 needs-file 态的输入；percent 由断点偏移推导供展示） */
export interface RestoredUploadEntry {
  id: string
  name: string
  originalName?: string
  sizeBytes: number
  /** 断点进度（0–100；由 chunkOffset 推导，仅展示用） */
  percent: number
  targetLibraryId: string
  targetDir: string
  attach?: PersistedAttach
  /** 分片会话 id（重选文件后续传既有会话用；null = 从头） */
  sessionId: string | null
  /** 持久化断点偏移（回写记录与续传决策的依据） */
  chunkOffset: number
  createdAt: number
}

/** 持久化记录 → 恢复条目（纯映射）：无 File 句柄（平台限制，见文件头）、
 *  status 由调用方统一落 needs-file。偏移越界防御性夹取（记录被手改/降级
 *  存储脏数据不产生 >100% 假进度） */
export function toRestoredEntry(record: PersistedUploadItem): RestoredUploadEntry {
  const ratio = record.sizeBytes > 0 ? record.chunkOffset / record.sizeBytes : 0
  return {
    id: record.id,
    name: record.name,
    originalName: record.originalName,
    sizeBytes: record.sizeBytes,
    percent: Math.min(100, Math.max(0, Math.round(ratio * 100))),
    targetLibraryId: record.targetLibraryId,
    targetDir: record.targetDir,
    attach: record.attach,
    sessionId: record.sessionId,
    chunkOffset: record.chunkOffset,
    createdAt: record.createdAt,
  }
}
