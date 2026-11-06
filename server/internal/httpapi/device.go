// device.go：设备结果包端点（PC→手机无前台维护服务端，ADR-0055）。
//
// 分层（ADR-0019）：本文件只做 multipart/query 协议校验、错误映射与响应装配；
// manifest 结构、段字节落盘与 SHA-256 复核、状态机、惰性过期与幂等都在
// internal/deviceapply。本切片没有 Android Worker、配对流程或设备身份校验：
// targetDeviceId 只是不透明标签，端点恒走全局 Bearer。
//
// 错误映射：不存在 404 NOT_FOUND；非法状态迁移（含终态/过期）409
// STATE_CONFLICT；参数/游标错误 400 INVALID_PARAM/INVALID_CURSOR；请求体
// 超过 66MiB 413 TOO_LARGE。
package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"strconv"

	"github.com/google/uuid"

	"qimeng-media/server/internal/deviceapply"
	"qimeng-media/server/internal/httpapi/gen"
)

const (
	// deviceApplyMaxBody 是整个 multipart 请求体上限：masterTxt 1MiB +
	// v2Backup 64MiB + manifest/边界预留 1MiB = 66MiB，超过即 413。
	deviceApplyMaxBody = 66 << 20
	// deviceApplyMultipartMemory 是 multipart 内存阈值；更大的段文件落临时
	// 文件，service 再流式写入结果包目录。
	deviceApplyMultipartMemory = 1 << 20
	// deviceApplyMaxManifestBytes 是 manifest 文本字段上限（与 service 一致，
	// 先在协议层拒绝超大字段，避免把明显非法的包交给业务层）。
	deviceApplyMaxManifestBytes = 256 << 10
	// headerContentSHA256 是段下载响应头的唯一写法（OpenAPI 同名字段）。
	headerContentSHA256 = "X-Content-SHA256"
	// contentTypeOctetStream 段下载响应类型（不参与 JSON 错误输出）。
	contentTypeOctetStream = "application/octet-stream"
)

// PostApiV1DeviceApplyTasks 登记设备结果包（multipart：manifest + 段文件）。
func (s *Server) PostApiV1DeviceApplyTasks(w http.ResponseWriter, r *http.Request) {
	mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || mediaType != "multipart/form-data" {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "请求体必须为 multipart/form-data")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, deviceApplyMaxBody)
	if err := r.ParseMultipartForm(deviceApplyMultipartMemory); err != nil { // #nosec G120 -- r.Body 已由 MaxBytesReader 限制为 66MiB；临时文件在 handler 结束 RemoveAll。
		if r.MultipartForm != nil {
			// 解析中途失败也可能已落临时文件；失败路径同样清理。
			_ = r.MultipartForm.RemoveAll()
		}
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeErr(w, http.StatusRequestEntityTooLarge, codeTooLarge, "设备结果包请求体超过 66MiB 上限")
			return
		}
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "multipart 请求体不合法")
		return
	}
	defer func() {
		if r.MultipartForm != nil {
			// 临时文件清理失败只影响磁盘占用，不影响本次结果。
			_ = r.MultipartForm.RemoveAll()
		}
	}()

	for name := range r.MultipartForm.Value {
		if name != "manifest" {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "不支持的 manifest 以外的文本字段")
			return
		}
	}
	for name := range r.MultipartForm.File {
		if name != string(deviceapply.SegmentMasterTxt) && name != string(deviceapply.SegmentV2Backup) {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "不支持的段文件字段")
			return
		}
	}
	manifestValues := r.MultipartForm.Value["manifest"]
	if len(manifestValues) != 1 || len(manifestValues[0]) == 0 {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "缺少 manifest 字段或重复提交")
		return
	}
	manifest := manifestValues[0]
	if len(manifest) > deviceApplyMaxManifestBytes {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "manifest 超过 256KiB 上限")
		return
	}

	segments := make(map[deviceapply.SegmentKind]io.Reader, 2)
	closers := make([]io.Closer, 0, 2)
	defer func() {
		for _, closer := range closers {
			_ = closer.Close()
		}
	}()
	for _, kind := range []deviceapply.SegmentKind{deviceapply.SegmentMasterTxt, deviceapply.SegmentV2Backup} {
		headers, ok := r.MultipartForm.File[string(kind)]
		if !ok {
			continue
		}
		if len(headers) != 1 {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "段文件字段重复")
			return
		}
		if headers[0].Size > kind.MaxBytes() {
			writeErr(w, http.StatusRequestEntityTooLarge, codeTooLarge, "段文件超过该类型上限")
			return
		}
		file, _, err := r.FormFile(string(kind))
		if err != nil {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "读取段文件失败")
			return
		}
		closers = append(closers, file)
		segments[kind] = file
	}

	task, err := s.deviceApply.Create(r.Context(), deviceapply.CreateInput{
		Manifest: []byte(manifest),
		Segments: segments,
	})
	if err != nil {
		s.writeDeviceApplyError(w, "登记设备结果包", err)
		return
	}
	writeJSON(w, http.StatusCreated, deviceApplyTaskToGen(task))
}

// GetApiV1DeviceApplyTasks 设备结果包列表（targetDevice 必填）。
func (s *Server) GetApiV1DeviceApplyTasks(w http.ResponseWriter, r *http.Request, params gen.GetApiV1DeviceApplyTasksParams) {
	opts := deviceapply.ListOptions{TargetDeviceID: params.TargetDevice}
	if params.Status != nil {
		opts.Status = deviceapply.Status(*params.Status)
	}
	if params.Limit != nil {
		opts.Limit = *params.Limit
	}
	if params.Cursor != nil {
		opts.Cursor = *params.Cursor
	}
	result, err := s.deviceApply.List(r.Context(), opts)
	if err != nil {
		s.writeDeviceApplyError(w, "列出设备结果包", err)
		return
	}
	items := make([]gen.DeviceApplyTaskSummary, 0, len(result.Items))
	for i := range result.Items {
		items = append(items, deviceApplySummaryToGen(result.Items[i]))
	}
	var next *string
	if result.NextCursor != "" {
		value := result.NextCursor
		next = &value
	}
	writeJSON(w, http.StatusOK, gen.DeviceApplyTaskList{Items: items, NextCursor: next})
}

// GetApiV1DeviceApplyTasksId 设备结果包详情。
func (s *Server) GetApiV1DeviceApplyTasksId(w http.ResponseWriter, r *http.Request, id gen.DeviceApplyTaskId) {
	task, err := s.deviceApply.Get(r.Context(), id.String())
	if err != nil {
		s.writeDeviceApplyError(w, "读取设备结果包", err)
		return
	}
	writeJSON(w, http.StatusOK, deviceApplyTaskToGen(task))
}

// PostApiV1DeviceApplyTasksIdClaim 领取（pending -> claimed；重复领取幂等）。
func (s *Server) PostApiV1DeviceApplyTasksIdClaim(w http.ResponseWriter, r *http.Request, id gen.DeviceApplyTaskId) {
	task, err := s.deviceApply.Claim(r.Context(), id.String())
	if err != nil {
		s.writeDeviceApplyError(w, "领取设备结果包", err)
		return
	}
	writeJSON(w, http.StatusOK, deviceApplyTaskToGen(task))
}

// PostApiV1DeviceApplyTasksIdResult 回传终态结果。
func (s *Server) PostApiV1DeviceApplyTasksIdResult(w http.ResponseWriter, r *http.Request, id gen.DeviceApplyTaskId) {
	var body gen.DeviceApplyResultRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	input := deviceapply.ResultInput{Status: deviceapply.Status(body.Status)}
	if body.Readback != nil {
		raw, err := json.Marshal(*body.Readback)
		if err != nil {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "readback 不是合法 JSON 对象")
			return
		}
		input.Readback = raw
	}
	if body.Message != nil {
		input.Message = *body.Message
	}
	if body.DeviceLocalHashes != nil {
		input.DeviceLocalHashes = make([]deviceapply.LocalHash, 0, len(*body.DeviceLocalHashes))
		for _, item := range *body.DeviceLocalHashes {
			input.DeviceLocalHashes = append(input.DeviceLocalHashes, deviceapply.LocalHash{
				Kind:   deviceapply.SegmentKind(item.Kind),
				SHA256: item.Sha256,
			})
		}
	}
	task, err := s.deviceApply.Result(r.Context(), id.String(), input)
	if err != nil {
		s.writeDeviceApplyError(w, "回传设备结果包结果", err)
		return
	}
	writeJSON(w, http.StatusOK, deviceApplyTaskToGen(task))
}

// PostApiV1DeviceApplyTasksIdCancel 取消（pending/claimed -> cancelled；重复取消幂等）。
func (s *Server) PostApiV1DeviceApplyTasksIdCancel(w http.ResponseWriter, r *http.Request, id gen.DeviceApplyTaskId) {
	task, err := s.deviceApply.Cancel(r.Context(), id.String())
	if err != nil {
		s.writeDeviceApplyError(w, "取消设备结果包", err)
		return
	}
	writeJSON(w, http.StatusOK, deviceApplyTaskToGen(task))
}

// GetApiV1DeviceApplyTasksIdSegmentsKind 下载段字节。
func (s *Server) GetApiV1DeviceApplyTasksIdSegmentsKind(w http.ResponseWriter, r *http.Request, id gen.DeviceApplyTaskId, kind gen.DeviceApplySegmentKind) {
	segment, reader, err := s.deviceApply.OpenSegment(r.Context(), id.String(), deviceapply.SegmentKind(kind))
	if err != nil {
		s.writeDeviceApplyError(w, "下载设备结果包段", err)
		return
	}
	defer func() { _ = reader.Close() }()
	w.Header().Set("Content-Type", contentTypeOctetStream)
	w.Header().Set("Content-Length", strconv.FormatInt(segment.Bytes, 10))
	w.Header().Set(headerContentSHA256, segment.SHA256)
	w.WriteHeader(http.StatusOK)
	if _, err := io.Copy(w, reader); err != nil {
		// 响应头已写出，只能记录；客户端会按 Content-Length/摘要发现截断。
		s.logger.WarnContext(r.Context(), "写出设备结果包段失败", "error", err)
	}
}

// writeDeviceApplyError 把设备结果包哨兵映射为协议错误码。
func (s *Server) writeDeviceApplyError(w http.ResponseWriter, what string, err error) {
	switch {
	case errors.Is(err, deviceapply.ErrNotFound):
		writeErr(w, http.StatusNotFound, codeNotFound, "设备结果包任务不存在")
	case errors.Is(err, deviceapply.ErrSegmentNotFound):
		writeErr(w, http.StatusNotFound, codeNotFound, "设备结果包段不存在")
	case errors.Is(err, deviceapply.ErrStateConflict):
		writeErr(w, http.StatusConflict, codeStateConflict, "设备结果包当前状态不允许该操作")
	case errors.Is(err, deviceapply.ErrInvalidCursor):
		writeErr(w, http.StatusBadRequest, codeInvalidCursor, "分页游标不合法")
	case errors.Is(err, deviceapply.ErrInvalidInput):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "设备结果包参数不合法")
	default:
		s.internalErr(w, what, err)
	}
}

// deviceApplyTaskToGen 装配任务响应（段按 masterTxt、v2Backup 排序；结果仅在
// 终态存在）。
func deviceApplyTaskToGen(task deviceapply.Task) gen.DeviceApplyTask {
	out := gen.DeviceApplyTask{
		Id:               deviceApplyUUID(task.ID),
		TargetDeviceId:   task.TargetDeviceID,
		Status:           gen.DeviceApplyTaskStatus(task.Status),
		ProtocolVersion:  task.ProtocolVersion,
		RulesFingerprint: task.RulesFingerprint,
		CreatedAt:        task.CreatedAt,
		UpdatedAt:        task.UpdatedAt,
		ClaimedAt:        task.ClaimedAt,
		FinishedAt:       task.FinishedAt,
		ExpiresAt:        task.ExpiresAt,
		Segments:         make([]gen.DeviceApplySegment, 0, len(task.Segments)),
	}
	for i := range task.Segments {
		segment := task.Segments[i]
		item := gen.DeviceApplySegment{
			Kind:   gen.DeviceApplySegmentKind(segment.Kind),
			Bytes:  segment.Bytes,
			Sha256: segment.SHA256,
		}
		if len(segment.ExpectedReadback) > 0 {
			var readback map[string]interface{}
			if json.Unmarshal(segment.ExpectedReadback, &readback) == nil {
				item.ExpectedReadback = &readback
			}
		}
		out.Segments = append(out.Segments, item)
	}
	if task.Result != nil {
		result := &gen.DeviceApplyResult{
			Status:            gen.DeviceApplyResultStatus(task.Result.Status),
			Readback:          map[string]interface{}{},
			DeviceLocalHashes: make([]gen.DeviceApplyLocalHash, 0, len(task.Result.DeviceLocalHashes)),
			ReportedAt:        task.Result.ReportedAt,
		}
		if len(task.Result.Readback) > 0 {
			_ = json.Unmarshal(task.Result.Readback, &result.Readback)
		}
		for _, hash := range task.Result.DeviceLocalHashes {
			result.DeviceLocalHashes = append(result.DeviceLocalHashes, gen.DeviceApplyLocalHash{
				Kind:   gen.DeviceApplySegmentKind(hash.Kind),
				Sha256: hash.SHA256,
			})
		}
		if task.Result.Message != "" {
			message := task.Result.Message
			result.Message = &message
		}
		out.Result = result
	}
	return out
}

// deviceApplySummaryToGen 装配列表摘要（不含段清单与结果正文）。
func deviceApplySummaryToGen(task deviceapply.Task) gen.DeviceApplyTaskSummary {
	return gen.DeviceApplyTaskSummary{
		Id:               deviceApplyUUID(task.ID),
		TargetDeviceId:   task.TargetDeviceID,
		Status:           gen.DeviceApplyTaskStatus(task.Status),
		ProtocolVersion:  task.ProtocolVersion,
		RulesFingerprint: task.RulesFingerprint,
		CreatedAt:        task.CreatedAt,
		UpdatedAt:        task.UpdatedAt,
		ClaimedAt:        task.ClaimedAt,
		FinishedAt:       task.FinishedAt,
		ExpiresAt:        task.ExpiresAt,
		SegmentCount:     task.SegmentCount,
	}
}

// deviceApplyUUID 把账本字符串 ID 转成协议 UUID；库内 ID 由服务端生成，
// 非法值只可能来自外部直接改库，按零值输出而不是 panic。
func deviceApplyUUID(value string) gen.DeviceApplyTaskId {
	id, err := uuid.Parse(value)
	if err != nil {
		return gen.DeviceApplyTaskId{}
	}
	return id
}
