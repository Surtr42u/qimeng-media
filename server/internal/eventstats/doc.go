// Package eventstats 编排事件流到日物化表的事务重建；仅依赖 store/stats，
// 不感知 HTTP。调用方事务内恢复只扫描单资产，管理重建从持写锁后读取全站。
package eventstats
