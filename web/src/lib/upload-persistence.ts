import type { PersistedUploadItem, UploadQueueStorage } from './upload-queue-persist'

export interface PersistenceHealth {
  unavailable: boolean
  restoreFailed: boolean
  failedWrites: number
  failedDeletes: number
  retrying: boolean
}

type Operation = { value: PersistedUploadItem | null }

/** 只管理 IO 意图/健康，不持有传输状态。单一排空体合并同 ID，终态删除先于旧写收尾。 */
export class UploadPersistence {
  private pending = new Map<string, Operation>()
  private blocked = new Map<string, Operation>()
  private tombstones = new Set<string>()
  private lastRecords = new Map<string, PersistedUploadItem>()
  private running: Promise<void> | null = null
  private listeners = new Set<() => void>()
  private health: PersistenceHealth = { unavailable: false, restoreFailed: false, failedWrites: 0, failedDeletes: 0, retrying: false }
  private storage: () => Promise<UploadQueueStorage>
  private reopen: () => void | Promise<void>

  constructor(storage: () => Promise<UploadQueueStorage>, reopen: () => void | Promise<void>) {
    this.storage = storage
    this.reopen = reopen
  }

  getHealth = (): PersistenceHealth => this.health
  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  private update(patch: Partial<PersistenceHealth> = {}) {
    const failures = [...this.blocked.values()]
    const next = {
      ...this.health, ...patch,
      failedWrites: failures.filter((op) => op.value !== null).length,
      failedDeletes: failures.filter((op) => op.value === null).length,
    }
    if (Object.keys(next).every((key) => next[key as keyof PersistenceHealth] === this.health[key as keyof PersistenceHealth])) return
    this.health = next
    for (const listener of this.listeners) listener()
  }

  put(record: PersistedUploadItem): void {
    // 队列 ID 不复用；已终态任务的迟到进度不能复活持久化记录。
    if (this.tombstones.has(record.id)) return
    this.lastRecords.set(record.id, record)
    this.enqueue(record.id, record)
  }

  delete(id: string): void {
    this.tombstones.add(id)
    this.enqueue(id, null)
  }

  private enqueue(id: string, value: PersistedUploadItem | null) {
    this.pending.set(id, { value })
    this.blocked.delete(id)
    void this.flush()
  }

  private flush(): Promise<void> {
    if (this.running) return this.running
    this.running = this.drain().finally(() => {
      this.running = null
      if ([...this.pending.keys()].some((id) => !this.blocked.has(id))) void this.flush()
    })
    return this.running
  }

  private async drain(): Promise<void> {
    while (true) {
      const batch = [...this.pending].filter(([id]) => !this.blocked.has(id))
      if (batch.length === 0) { this.update(); return }
      let storage: UploadQueueStorage
      try {
        storage = await this.storage()
      } catch {
        for (const [id, op] of batch) if (this.pending.get(id) === op) this.blocked.set(id, op)
        this.update({ unavailable: true })
        return
      }
      for (const [id, op] of batch) {
        if (this.pending.get(id) !== op) continue
        try {
          if (op.value === null) await storage.delete(id)
          else await storage.put(op.value)
          if (this.pending.get(id) === op) {
            if (storage.durable === false) this.blocked.set(id, op)
            else {
              this.pending.delete(id); this.blocked.delete(id)
              if (op.value === null) this.lastRecords.delete(id)
            }
          }
        } catch {
          // 删除失败不等于原记录不存在；尽力留下终态标记，避免刷新误恢复。
          const previous = this.lastRecords.get(id)
          if (op.value === null && previous) {
            try { await storage.put({ ...previous, terminal: true }) } catch { /* 双重存储故障仍保留警告与待删意图。 */ }
          }
          if (this.pending.get(id) === op) this.blocked.set(id, op)
        }
      }
      this.update({ unavailable: storage.durable === false })
    }
  }

  async read(): Promise<PersistedUploadItem[]> {
    try {
      const storage = await this.storage()
      const records = await storage.getAll()
      this.update({ unavailable: storage.durable === false, restoreFailed: false })
      for (const record of records) {
        if (record.terminal) this.delete(record.id)
        else if (!this.tombstones.has(record.id)) this.lastRecords.set(record.id, record)
      }
      return records.filter((record) => !record.terminal && !this.tombstones.has(record.id))
    } catch {
      this.update({ restoreFailed: true })
      return []
    }
  }

  async retry(): Promise<void> {
    if (this.health.retrying) return
    this.update({ retrying: true })
    try {
      // 先等旧 IO 收尾再换连接，避免旧 put 在重试 delete 后完成。
      await this.running
      await this.reopen()
      this.blocked.clear()
      await this.flush()
      await this.read()
    } catch {
      this.update({ unavailable: true })
    } finally {
      this.update({ retrying: false })
    }
  }
}
