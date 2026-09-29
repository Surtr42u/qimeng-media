/**
 * 上传暂存条目模型与纯函数（上传工作台/暂存列表共用；铁律 7：模型与派生口径
 * 落 lib 单源，组件只做渲染）。AuthorAttachValue 为 type-only 引用（运行时零
 * 依赖 components）——联想受控值形状单一来源仍是 AuthorSuggestField 的接口定义。
 */

import type { AuthorAttachValue } from '@/components/manage/AuthorSuggestField'
import { composeUploadName, extensionOf } from '@/lib/upload-naming'

/** 暂存条目（页面会话态；file 句柄只在入队时交给上传队列 hook） */
export interface StagedUploadItem {
  id: string
  file: File
  /** 拖拽目录条目的相对路径（undefined = 点击选择，无子目录段） */
  relativePath?: string
  /** 原文件名（扩展名锁定的唯一依据；拖拽条目 = relativePath 末段同值） */
  displayName: string
  sizeBytes: number
  /** 编辑后的落库基名（undefined/空白 = 未编辑，入队回退 displayName） */
  uploadBaseName?: string
  /** 逐项挂靠作者（ingest 时继承批次默认；null = 未挂） */
  attachAuthor: AuthorAttachValue | null
  /** 逐项来源词（仅作者已挂时有意义；ingest 时继承批次默认） */
  attachSources: string[]
}

/** 批次默认挂靠（新进暂存项的继承源；工作台持有并随「应用到全部」下发） */
export interface BatchAttachDefaults {
  author: AuthorAttachValue | null
  sources: string[]
}

/** 模块级序号：暂存条目 id（页面生命周期内唯一即可，不进协议） */
let stagedSeq = 0

/** 新进暂存条目（三条管道共用：点击选择 / 拖文件 / 拖目录——统一继承批次默认） */
export function makeStagedItem(
  file: File,
  relativePath: string | undefined,
  batch: BatchAttachDefaults,
): StagedUploadItem {
  return {
    id: `staged-${++stagedSeq}`,
    file,
    relativePath,
    displayName: file.name,
    sizeBytes: file.size,
    attachAuthor: batch.author,
    attachSources: [...batch.sources],
  }
}

/** 展示名 = 实际落库名（编辑基名 + 锁定扩展名拼装，lib/upload-naming 单一口径；未编辑 = 原文件名） */
export function effectiveUploadName(item: StagedUploadItem): string {
  const base = item.uploadBaseName?.trim()
  if (base === undefined || base === '') return item.displayName
  return composeUploadName(base, extensionOf(item.displayName)) || item.displayName
}
