package events

// 事件载荷结构体集中定义：与 api/openapi.yaml 的 SSE data 帧 schema 一一对应，
// 增删字段必须先改协议再改这里（协议先行，见 AI_README_FIRST.md）。

// UploadDoneEvent 是 upload.done 主题的 data 帧载荷（上传入库完成）。
// 协议定义：components/schemas/UploadDoneEvent。
type UploadDoneEvent struct {
	AssetID string `json:"assetId"`
}

// EngagementChangedEvent 是 favorite.changed 与 like.changed 两个主题共用
// 的 data 帧载荷（ADR-0029）：两事件同构，都只携带发生变更的资产 id。
// 只带 id 不带变更后状态——事件是"变更信号"不是状态面，消费方收到后
// 重拉权威状态即可，避免载荷与 handler 的状态计算形成第二份真相。
// 运行时事件面不入 openapi（见 bus.go 主题常量注释）；且严禁订阅方拿
// 这两个事件 bump 库内容修订号（ADR-0026：revision 只保证资产集合面）。
type EngagementChangedEvent struct {
	AssetID string `json:"assetId"`
}
