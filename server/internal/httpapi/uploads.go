// uploads.go：断点续传上传端点（tus 1.0 核心语义极简子集，ADR-0028）。
// 会话状态（注册表/分片文件/过期清扫）在 internal/uploadsess 包，本文件只做
// 参数校验与编排：create 前置校验（清洗/dir 规范化/白名单/上限），complete 对
// 拼装完成的整文件复跑直传同一套终检与入库管线（ingestPlacedUpload，upload.go）
// ——library.changed 照发（修订号照 bump）、upload.done 照发、富化照跑。
// dir 目标子目录与直传逐字同语义（空 = 库根）：create 校验规范化后存入会话，
// complete 按其落位（placeSealedInLibrary 与直传 #9 关键段同构）。
// 同名冲突语义与直传通道逐字一致：自动重命名 "基名 (2).ext"，永不 409。
package httpapi

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/sysmon"
	"qimeng-media/server/internal/uploadsess"
)

// PostApiV1Uploads 创建断点续传上传会话：创建即做可前置校验（文件名清洗、
// 扩展名白名单、size 上限、库存在且启用、autoAccept 开关），把弱网大文件的
// 校验失败挡在收第一个字节之前。会话与分片文件由 uploadsess.Manager 持有。
func (s *Server) PostApiV1Uploads(w http.ResponseWriter, r *http.Request) {
	var req gen.CreateUploadRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	// 第④道前置：文件名清洗（落盘名的唯一来源，与直传同一入口）；同名
	// 冲突在 complete 时按直传语义自动重命名，这里清洗结果即"目标名"。
	name, err := filing.SanitizeFilename(req.FileName)
	if err != nil {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeErr(w, http.StatusBadRequest, codeInvalidFilename, "文件名不合法")
		return
	}
	// 第①道前置：扩展名白名单（魔数终检在 complete——创建时还没有字节）。
	if _, ok := scanner.ClassifyMedia(name); !ok {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeErr(w, http.StatusBadRequest, codeInvalidExtension, "扩展名不在白名单")
		return
	}
	// 目标目录前置校验（与直传同一入口 resolveUploadDir：空 = 库根，非空过
	// NormalizeRelPath）：create 即挡住穿越形态，规范化结果存入会话——
	// complete 落位只认服务端持有的 dir，不再收客户端路径（SECURITY 红线 1）。
	// 协议 dir 可选（生成物 *string）：nil 与空串同语义 = 库根（openapi 缺省口径）。
	reqDir := ""
	if req.Dir != nil {
		reqDir = *req.Dir
	}
	dir, ok := resolveUploadDir(w, reqDir)
	if !ok {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		return
	}
	if req.LibraryId == "" || req.Size < 1 {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "libraryId 与 size 必填（size ≥ 1）")
		return
	}
	// 库存在且启用（直传通道未查启用位是历史口径，分片通道按 ADR-0028
	// 协议声明收紧；停用库的浏览面已隐藏，不应再是新文件的落点）。
	lib, err := s.q.GetLibrary(r.Context(), req.LibraryId)
	if errors.Is(err, sql.ErrNoRows) {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeErr(w, http.StatusBadRequest, codeLibraryNotFound, "libraryId 须指向已注册且启用的库")
		return
	}
	if err != nil {
		s.internalErr(w, "查询库", err)
		return
	}
	if lib.Enabled != 1 {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeErr(w, http.StatusBadRequest, codeLibraryNotFound, "libraryId 须指向已注册且启用的库")
		return
	}
	// 与直传同一道闸：autoAccept 关闭时两条上传通道一起关（防分片通道成为
	// 直传被关后的旁路）；上限与直传同源（配置文件与 kv 取小者）。
	policy := s.resolveUploadPolicy(r.Context())
	if policy.disabled {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeErr(w, http.StatusForbidden, codeUploadDisabled, "上传已被关闭（设置页自动接收上传开关）")
		return
	}
	if req.Size > policy.maxBytes {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeErr(w, http.StatusRequestEntityTooLarge, codeUploadTooLarge, "文件超过大小上限")
		return
	}
	snap, err := s.uploads.Create(lib.ID, name, dir, req.Size)
	if err != nil {
		s.internalErr(w, "创建上传会话", err)
		return
	}
	writeJSON(w, http.StatusCreated, uploadSessionToGen(snap))
}

// GetApiV1UploadsId 断点探测：客户端重试/续传前先问服务端"传到哪了"。
// 只读不续命——空轮询不能让弃传会话逃过过期清扫。
func (s *Server) GetApiV1UploadsId(w http.ResponseWriter, r *http.Request, id gen.UploadId) {
	snap, err := s.uploads.Snapshot(id.String())
	if errors.Is(err, uploadsess.ErrSessionNotFound) {
		writeErr(w, http.StatusNotFound, codeNotFound, "上传会话不存在或已过期")
		return
	}
	if err != nil {
		s.internalErr(w, "查询上传会话", err)
		return
	}
	writeJSON(w, http.StatusOK, uploadSessionToGen(snap))
}

// PatchApiV1UploadsId 追加分片：offset 必须等于服务端权威值（不等 409 回
// 权威 UploadSession）；单片上限/累计上限的语义映射见 uploadsess 哨兵错误。
// 磁盘写满/读流中断 → 500 且会话保留（客户端可整片重试，ADR-0028）。
func (s *Server) PatchApiV1UploadsId(w http.ResponseWriter, r *http.Request, id gen.UploadId, params gen.PatchApiV1UploadsIdParams) {
	if params.Offset < 0 {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "offset 不能为负")
		return
	}
	snap, err := s.uploads.Append(id.String(), params.Offset, r.Body)
	switch {
	case err == nil:
		writeJSON(w, http.StatusOK, uploadSessionToGen(snap))
	case errors.Is(err, uploadsess.ErrSessionNotFound):
		writeErr(w, http.StatusNotFound, codeNotFound, "上传会话不存在或已过期")
	case errors.Is(err, uploadsess.ErrOffsetMismatch):
		// tus 语义：409 响应体即权威状态，客户端重同步后重发本分片。
		// 会话刚被并发清扫出表的窗口按 500 兜底（下一轮探测即 404）。
		authSnap, gerr := s.uploads.Snapshot(id.String())
		if gerr != nil {
			s.internalErr(w, "查询上传会话", gerr)
			return
		}
		writeJSON(w, http.StatusConflict, uploadSessionToGen(authSnap))
	case errors.Is(err, uploadsess.ErrSizeOverflow):
		writeErr(w, http.StatusBadRequest, codeUploadTooLarge, "累计字节超出会话声明的 size")
	case errors.Is(err, uploadsess.ErrChunkTooLarge):
		writeErr(w, http.StatusRequestEntityTooLarge, codeUploadTooLarge, "单次分片超过上限")
	default:
		s.internalErr(w, "写入上传分片", err)
	}
}

// PostApiV1UploadsIdComplete 终结会话：对拼装完成的整文件复跑直传的终检
// 与入库管线。成功后临时文件已搬进库、会话删除；校验失败/库已删时会话与
// 临时文件保留（可修正后重试），过期时钟已在入口 Touch。
func (s *Server) PostApiV1UploadsIdComplete(w http.ResponseWriter, r *http.Request, id gen.UploadId) {
	sid := id.String()
	// complete 尝试即计入过期时钟：可重试的失败会话保留到 SessionTTL 兜底。
	if err := s.uploads.Touch(sid); err != nil {
		if errors.Is(err, uploadsess.ErrSessionNotFound) {
			writeErr(w, http.StatusNotFound, codeNotFound, "上传会话不存在或已过期")
			return
		}
		s.internalErr(w, "查询上传会话", err)
		return
	}
	seal, err := s.uploads.Seal(sid)
	if errors.Is(err, uploadsess.ErrSessionNotFound) {
		writeErr(w, http.StatusNotFound, codeNotFound, "上传会话不存在或已过期")
		return
	}
	if errors.Is(err, uploadsess.ErrNotComplete) {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "分片未传完（offset 未达 size）")
		return
	}
	if err != nil {
		s.internalErr(w, "终结上传会话", err)
		return
	}
	// 与直传同一道闸（会话创建后设置页开关可能翻转）与同一上限来源。
	policy := s.resolveUploadPolicy(r.Context())
	if policy.disabled {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeErr(w, http.StatusForbidden, codeUploadDisabled, "上传已被关闭（设置页自动接收上传开关）")
		return
	}
	// 四道终检对拼装后的整文件全部到位：②魔数（读拼装文件头部）→①扩展名/
	// ③最终大小/④文件名（ValidateUpload 内部同序短路）。终检失败会话保留。
	head, err := readUploadHead(seal.Path)
	if err != nil {
		s.internalErr(w, "读取分片文件头", err)
		return
	}
	if verr := filing.ValidateUpload(seal.FileName, seal.Size, policy.maxBytes, head); verr != nil {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeUploadValidationError(w, verr)
		return
	}
	// 会话创建后库可能已被删除：终检过了也无法入库，404 引导客户端放弃。
	lib, err := s.q.GetLibrary(r.Context(), seal.LibraryID)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, codeNotFound, "上传会话所属库不存在")
		return
	}
	if err != nil {
		s.internalErr(w, "查询库", err)
		return
	}
	mediaType, ok := scanner.ClassifyMedia(seal.FileName)
	if !ok {
		// 防御分支：创建时已过白名单，文件名不可变（会话内固定），理论不可达。
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeErr(w, http.StatusBadRequest, codeInvalidExtension, "扩展名不在白名单")
		return
	}
	// 搬运 + 冲突解析 + 入库（放置段拆出见 placeSealedInLibrary：重活锁外、
	// rename 锁内，与直传 #9 关键段同构）。dir 为 create 时校验规范化并存入
	// 会话的目标子目录（空 = 库根），落位语义与直传逐字一致。
	finalName, targetRel, targetAbs, gerr := s.placeSealedInLibrary(lib, seal)
	if gerr != nil {
		if errors.Is(gerr, errUploadTargetEscape) {
			writeErr(w, http.StatusBadRequest, codePathEscape, "目标路径不合法")
			return
		}
		if errors.Is(gerr, errSealedSizeMismatch) {
			// 400：与「未传完 400」同属 complete 的「校验失败且会话保留」
			// 语义（协议侧 complete 明文「永不 409」，冲突语义已被同名自动
			// 重命名占位）——size 复核是四道终检里「最终大小」的加固延伸，
			// 客户端无从修复（成品字节已不可信），只能放弃重建会话。
			sysmon.Default.IncUpload(sysmon.UploadFail)
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "分片成品与会话声明大小不符，请放弃本会话重新上传")
			return
		}
		s.internalErr(w, "落盘分片成品文件", gerr)
		return
	}
	detail, err := s.ingestPlacedUpload(r.Context(), lib.ID, targetAbs, targetRel, finalName, mediaType)
	if err != nil {
		s.internalErr(w, "入库上传资产", err)
		return
	}
	// 临时文件已 rename 进库：Drop 只剩删会话（文件 NotExist 被容忍）。
	if derr := s.uploads.Drop(sid); derr != nil && !errors.Is(derr, uploadsess.ErrSessionNotFound) {
		s.logger.Warn("清理已完成上传会话失败", "id", sid, "err", derr)
	}
	writeJSON(w, http.StatusCreated, detail)
}

// placeSealedInLibrary 把终结就绪的分片文件搬进库内目标目录（dir 空 = 库根，
// 与直传 dir 参数同语义）并解析冲突名：重活（跨卷拷贝预置）在库锁外、锁内
// 只做只读探测、size 复核与一次 rename——与直传 #9 关键段同构（持库锁时长
// 与文件大小无关）。dir 在 create 时已过 resolveUploadDir 规范化并存入会话
// （服务端持有，complete 不再收客户端路径）；baseDirAbs 的 PathWithinRoot 仍
// 保留为 handler 侧兜底（SECURITY 红线 1 纵深防御，与直传同一道闸）。预置/
// 放置失败清理临时副本；rename 成功后副本已不在（cleanup 报 NotExist 属正常）。
// 返回最终落盘名、库内相对路径与绝对路径（rel = dir/最终名，供入库管线）。
func (s *Server) placeSealedInLibrary(lib db.Library, seal uploadsess.SealedSession) (string, string, string, error) {
	// 目标子目录（含校验兜底）：预置与最终落点同目录才能原子 rename——
	// 与直传「先建目录、临时文件与最终文件同目录」同一顺序（MkdirAll 幂等
	// 且并发安全，在 stageSealedFile 内完成）。
	baseDirAbs := filepath.Join(lib.RootPath, filepath.FromSlash(seal.Dir))
	if !filing.PathWithinRoot(lib.RootPath, baseDirAbs) {
		return "", "", "", errUploadTargetEscape
	}
	staging, cleanup, err := s.stageSealedFile(baseDirAbs, seal.Path)
	if err != nil {
		return "", "", "", err
	}
	var (
		finalName string
		targetRel string
		targetAbs string
	)
	gerr := filing.WithLibraryGate(lib.ID, func() error {
		exists := func(n string) bool {
			_, serr := os.Stat(filepath.Join(baseDirAbs, n))
			return serr == nil
		}
		finalName = filing.ResolveConflict(seal.FileName, exists)
		targetRel = path.Join(seal.Dir, finalName)
		targetAbs = filepath.Join(lib.RootPath, filepath.FromSlash(targetRel))
		if !filing.PathWithinRoot(lib.RootPath, targetAbs) {
			return errUploadTargetEscape
		}
		// Seal→落位间隙 size 复核（第四百一十九笔，上批审查 P2-4 收口）：
		// complete 的 staging/rename 段不持会话锁，违规客户端可在 Seal 之后
		// 并发 PATCH（offset==size 恰好过 Append 的首道比对）在 rename 间隙
		// 往分片文件塞字节——落库文件就会与声明 size 不符（Append 自身的
		// 回滚截断可能落在 rename 之后，截的是已被移走的旧路径，拦不住）。
		// 锁内 os.Stat 复核 staging 实际字节数，不等 seal.Size 按会话已损坏
		// 处理（errSealedSizeMismatch → complete 400）。stat 与 rename 相邻
		// 执行把竞态窗压缩到两条系统调用之间，工程上不可利用；绝对串行须把
		// 整个落位搬进 session.mu（会话锁横跨库锁与跨卷拷贝），不值得。
		st, serr := os.Stat(staging)
		if serr != nil {
			return fmt.Errorf("复核分片成品大小失败: %w", serr)
		}
		if st.Size() != seal.Size {
			s.logger.Warn("分片成品与会话声明大小不符，拒绝落位", "session", seal.ID,
				"declared", seal.Size, "actual", st.Size())
			return errSealedSizeMismatch
		}
		return os.Rename(staging, targetAbs)
	})
	if gerr != nil {
		cleanup()
		return "", "", "", gerr
	}
	return finalName, targetRel, targetAbs, nil
}

// errSealedSizeMismatch 落位前复核发现分片成品实际字节数与会话声明 size
// 不符（Seal 后成品被篡改的信号）：按会话已损坏处理，complete 映射 400。
var errSealedSizeMismatch = errors.New("uploads: 分片成品与会话声明大小不符")

// DeleteApiV1UploadsId 放弃会话：删临时文件与会话，不入库、不产生任何资产。
func (s *Server) DeleteApiV1UploadsId(w http.ResponseWriter, r *http.Request, id gen.UploadId) {
	if err := s.uploads.Drop(id.String()); err != nil {
		if errors.Is(err, uploadsess.ErrSessionNotFound) {
			writeErr(w, http.StatusNotFound, codeNotFound, "上传会话不存在或已过期")
			return
		}
		s.internalErr(w, "放弃上传会话", err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// uploadSessionToGen 把会话快照装配为协议 UploadSession 载荷。
func uploadSessionToGen(snap uploadsess.Snapshot) gen.UploadSession {
	// ID 由 uploadsess 经 uuid.NewString() 生成，MustParse 不可失败；
	// 协议 id 为 uuid 格式（openapi UploadSession.id format: uuid）。
	return gen.UploadSession{
		Id:     uuid.MustParse(snap.ID),
		Offset: snap.Offset,
		Size:   snap.Size,
	}
}

// readUploadHead 读取文件头部 RecommendedHeadBytes 字节（complete 的魔数
// 终检输入；短文件整体返回，与直传收流的 head 读取同语义）。
func readUploadHead(path string) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	head := make([]byte, filing.RecommendedHeadBytes)
	n, err := io.ReadFull(f, head)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		if cerr := f.Close(); cerr != nil {
			return nil, errors.Join(err, cerr)
		}
		return nil, err
	}
	if cerr := f.Close(); cerr != nil {
		return nil, cerr
	}
	return head[:n], nil
}

// stageSealedFile 把拼装完成的分片文件预置进库根目录（与最终落点同卷，
// 后续锁内 rename 才是原子操作）：优先 rename（同卷零拷贝），跨卷（DataDir
// 与库根不同盘/挂载点）回退全量拷贝。返回预置路径与失败清理函数——预置
// 成功后所有权移交调用方（rename 成功后文件已不在，cleanup 报 NotExist
// 属正常）。
func (s *Server) stageSealedFile(libraryRoot, sealedPath string) (string, func(), error) {
	if err := os.MkdirAll(libraryRoot, dirPerm); err != nil {
		return "", nil, err
	}
	staging := filepath.Join(libraryRoot, uploadTmpPrefix+uuid.NewString()+uploadTmpSuffix)
	if err := os.Rename(sealedPath, staging); err == nil {
		return staging, s.stagingCleaner(staging), nil
	}
	// 跨卷回退：拷贝到库根隐藏临时名（.qm-upload-*.tmp 在媒体白名单外，
	// 扫描器不收），失败即清理半成品。
	src, err := os.Open(sealedPath)
	if err != nil {
		return "", nil, err
	}
	defer func() {
		if cerr := src.Close(); cerr != nil {
			s.logger.Warn("关闭分片源文件失败", "path", sealedPath, "err", cerr)
		}
	}()
	dst, err := os.Create(staging)
	if err != nil {
		return "", nil, err
	}
	if _, err := io.Copy(dst, src); err != nil {
		if cerr := dst.Close(); cerr != nil {
			s.logger.Warn("关闭分片预置文件失败", "path", staging, "err", cerr)
		}
		s.stagingCleaner(staging)()
		return "", nil, err
	}
	if err := dst.Close(); err != nil {
		s.stagingCleaner(staging)()
		return "", nil, err
	}
	return staging, s.stagingCleaner(staging), nil
}

// stagingCleaner 返回预置文件的清理函数（放弃/失败路径调用；rename 成功后
// 文件已不在，NotExist 静默忽略）。
func (s *Server) stagingCleaner(path string) func() {
	return func() {
		if rmErr := os.Remove(path); rmErr != nil && !os.IsNotExist(rmErr) {
			s.logger.Warn("清理分片预置文件失败", "path", path, "err", rmErr)
		}
	}
}

// StartUploadSweeper 组合根（main）调用一次：断点续传会话过期清扫 daemon，
// ctx 取消即退出（与 StartTrashSweeper 同款生命周期模式）。清扫不 bump
// 修订号——未产生任何资产（ADR-0028）。
func (s *Server) StartUploadSweeper(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(uploadsess.SweepInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				s.sweepUploadSessionsOnce()
			}
		}
	}()
}

// sweepUploadSessionsOnce 一轮会话过期清扫（测试直接调用注入时钟验证）。
func (s *Server) sweepUploadSessionsOnce() {
	expired, orphans := s.uploads.SweepOnce()
	if expired > 0 {
		s.logger.Info("过期上传会话已回收", "sessions", expired, "orphanFiles", orphans)
		return
	}
	if orphans > 0 {
		s.logger.Info("孤儿分片临时文件已清理", "count", orphans)
	}
}

// CloseUploadSessions 优雅关停时清空在途会话与其临时分片文件（尽力而为，
// 失败只记日志：遗留半成品由下轮孤儿清理兜底）。
func (s *Server) CloseUploadSessions() {
	if err := s.uploads.Close(); err != nil {
		s.logger.Warn("关停清理上传会话失败（遗留分片由过期清扫兜底）", "err", err)
	}
}
