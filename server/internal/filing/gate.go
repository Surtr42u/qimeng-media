// gate.go：按库粒度的进程内互斥设施（#9 台账清欠）。
//
// 为什么需要它：上传/移动/回收站恢复的"探测目标占用 → 解析冲突名 →
// rename 落盘"是两段式序列，段间没有互斥时并发同名请求会解析出同一
// 冲突名，第二次 rename 静默覆盖第一次（Windows/Linux 同为静默覆盖，
// os.Rename 不提供 no-replace 语义）。把整段包进同一把按库互斥锁内，
// 序列化后冲突解析的读盘结果对同库其他写入方是稳定的。
//
// 为什么按库粒度：单用户 NAS 形态下全部写入方同进程（上传 handler、
// 移动 handler、恢复 handler），库与库之间的文件操作互不相干，按库加锁
// 让多库并发互不阻塞；多实例部署不在现状（进程内锁即足够，跨进程需
// 文件锁，届时另立方案）。粒度取"库"而非"目录"：冲突解析只看目标路径，
// 但库行的唯一索引（GetAssetByPath）也是冲突判定的一半，目录级锁锁不住
// 库行占位竞争，库级是能同时覆盖两半的最小现成单位。
//
// 与 scanner.libraryGate 的同构关系：scanner 闸门协调"全量扫描 vs 增量
// 处理"的互斥（防重入 + 移动合并视野完整），本设施协调"冲突探测→改名
// 落盘"关键段的互斥。二者都按 libraryID 隔离，但相互独立、不可互相
// 替代——scanner 闸门不可重入，把本关键段塞进扫描闸内会自锁。
//
// 本文件是并发设施而非纯函数，是包"纯函数"边界的唯一例外（见 doc.go）。
package filing

import "sync"

// gatesMu 守护 gates 的增删；gates 按 libraryID 惰性建锁（库数量级是个
// 位数到十位数，map 不做淘汰——锁对象开销可忽略，删除反而引入
// "取锁与建锁竞态"的复杂度，不值得）。
var (
	gatesMu sync.Mutex
	gates   = map[string]*sync.Mutex{}
)

// libraryGateMutex 返回（或建）libraryID 对应的互斥锁。
func libraryGateMutex(libraryID string) *sync.Mutex {
	gatesMu.Lock()
	defer gatesMu.Unlock()
	mu, ok := gates[libraryID]
	if !ok {
		mu = &sync.Mutex{}
		gates[libraryID] = mu
	}
	return mu
}

// WithLibraryGate 在 libraryID 的互斥锁内执行 fn。fn 应包含完整的
// "探测目标占用 → 解析冲突名 → rename 落盘"关键段（必要时含库行占位
// 检查与回滚），锁保证同库内该序列对其他写入方原子可见；不同库并发
// 不受影响。
//
// panic 安全：解锁走 defer，fn panic 时锁不外泄（进程通常随 panic 退出，
// 但测试框架经 defer 传播失败的场景下不吊死后续用例）。
func WithLibraryGate(libraryID string, fn func() error) error {
	mu := libraryGateMutex(libraryID)
	mu.Lock()
	defer mu.Unlock()
	return fn()
}
