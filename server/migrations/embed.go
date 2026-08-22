// Package migrations 内嵌全部 migration SQL 文件，供 store 包引导建库。
//
// 为什么 embed 放在本目录：Go 的 //go:embed 只能引用包目录内的文件，
// internal/store 无法直接嵌入 ../../migrations；把 embed 声明放在 SQL 文件旁边，
// 目录仍是唯一的 migration 真身（新增 migration 只加文件，见 AI_README_FIRST「迁移唯一」）。
package migrations

import "embed"

// FS 内嵌本目录全部 *.sql（golang-migrate 按文件名版本号识别 up/down）。
// 同时被 sqlc 当作 schema 来源（sqlc 解析时自动忽略 down migration）。
//
//go:embed *.sql
var FS embed.FS
