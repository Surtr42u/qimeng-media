package events

// 事件载荷结构体集中定义：与 api/openapi.yaml 的 SSE data 帧 schema 一一对应，
// 增删字段必须先改协议再改这里（协议先行，见 AI_README_FIRST.md）。

// UploadDoneEvent 是 upload.done 主题的 data 帧载荷（上传入库完成）。
// 协议定义：components/schemas/UploadDoneEvent。
type UploadDoneEvent struct {
	AssetID string `json:"assetId"`
}
