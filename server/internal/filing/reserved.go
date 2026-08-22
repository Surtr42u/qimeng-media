package filing

import (
	"fmt"
	"strings"
)

// windowsReservedNames 是 Windows 保留设备名全集（大小写不敏感）。
//
// 为什么在 Linux NAS 上也要拒绝：媒体目录可能通过 SMB/NFS 暴露给 Windows 客户端、
// 被 rsync/备份工具同步到 Windows 磁盘，或 Docker 卷实际落在 NTFS 分区上。保留名
// 文件在这些场景下无法创建/打开/删除（Windows API 直接拒绝），会造成服务端数据库
// 与文件系统状态永久不一致，且事后无法在 Windows 侧修复。预防成本为零，直接拒绝。
var windowsReservedNames = func() map[string]struct{} {
	m := map[string]struct{}{
		"CON": {}, "PRN": {}, "AUX": {}, "NUL": {},
	}
	for i := 1; i <= 9; i++ {
		m[fmt.Sprintf("COM%d", i)] = struct{}{}
		m[fmt.Sprintf("LPT%d", i)] = struct{}{}
	}
	return m
}()

// isReservedDeviceName 判断单个路径段/文件名是否命中 Windows 保留设备名。
//
// 匹配规则：名字到第一个点为止的部分（"基名"）与保留名精确相等即命中——
// Windows 对 "CON.txt"、"CON.x.jpg" 同样拒绝创建。必须精确匹配而不能前缀匹配：
// 前缀匹配会误杀 "conference"、"nokia.jpg" 这类以保留名开头的合法名字；
// 同理 COM1~COM9 精确匹配后，COM10 是合法名（与 Windows 实际行为一致）。
func isReservedDeviceName(seg string) bool {
	base, _, _ := strings.Cut(seg, ".")
	base = strings.ToUpper(base)
	_, reserved := windowsReservedNames[base]
	return reserved
}
