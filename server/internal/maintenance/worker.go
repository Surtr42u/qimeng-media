package maintenance

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"runtime/debug"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/backup"
	"qimeng-media/server/internal/safego"
)

// Worker 默认值。config 包有同名同值常量并在 Load 期兜底；这里再兜底一次，
// 因为测试/内嵌形态可能手工构造 Service 与 Worker 而不经过 config.Load
// （ticker 周期为 0 会 panic；并发为 0 会永远不领取）。
const (
	// DefaultWorkerPollInterval 是空闲时的轮询间隔（默认 5s）：一轮只是两条
	// 索引查询，个人库规模成本可忽略；更短只会白跑事务，不提高实时性收益
	//（任务创建本来就要经 HTTP 往返）。
	DefaultWorkerPollInterval = 5 * time.Second
	// DefaultWorkerLeaseTTL 是租约有效期（默认 2 分钟）：必须远大于单步执行
	// 时长与心跳间隔；崩溃后最多等一个 TTL 即可被本进程/下次启动重新领取。
	DefaultWorkerLeaseTTL = 2 * time.Minute
	// DefaultWorkerConcurrency 是同时在跑的任务数上限（默认 1）：账本写入走
	// SQLite 单写者，备份/导出类步骤又是重 IO，默认串行最稳；需要并行由部署
	// 方显式调大（配置层有硬上限）。
	DefaultWorkerConcurrency = 1
)

// maxWorkerHeartbeatFailures 是心跳连续非租约失败上限：达到后不再信任租约，
// 取消在途 handler 并停止本轮执行（任务保持原状态，租约到期后重领；已完成
// 步骤不会重复副作用）。非租约错误包括 DB 忙/临时故障，不应把可重试任务
// 永久标成 failed。
const maxWorkerHeartbeatFailures = 2

// 账本 error_code 约定（worker 侧）：步骤失败但 handler 未提供具体错误码时
// 用 STEP_FAILED；panic 兜底用 WORKER_PANIC；其余 handler 自报（如
// BACKUP_EXPORT_*）。error_code 上限 MaxErrorCodeRunes，超长会被截断。
const (
	StepFailedCode  = "STEP_FAILED"
	WorkerPanicCode = "WORKER_PANIC"
)

// StepSpec 是 handler 声明的一个流水线步骤。
//
// Name 是账本主键 (job_id, step) 的 step 段：同一 job 内必须唯一且稳定，
// 崩溃恢复靠它识别「已完成的步骤不重复执行」。Stage 是该步骤执行前任务应
// 处于的流水线阶段；各步骤按声明顺序执行，阶段必须能从初始 queued 起沿
// 状态机相邻推进（允许研究/计划类 kind 使用 research/planned）。
type StepSpec struct {
	Name  string
	Stage Status
}

// StepOutcome 是一次步骤执行的结果。
//
// Detail 是写入 maintenance_steps.detail_json 的账本证据（nil = {}）；
// Artifacts 是本次步骤产出的工件元数据，由 worker 归一到当前 job 后入账
// （正文不入库，URI 只是引用）；Skip 表示该步骤无副作用地跳过（例如载荷
// 无对应目标），所有步骤跑完后任务终态记为 partial。
type StepOutcome struct {
	Detail    json.RawMessage
	Artifacts []ArtifactInput
	Skip      bool
}

// Handler 执行某一 kind 的流水线步骤。本包只做账本/租约/状态推进，业务
// 规则由 handler 实现（backup_export 内置，其余 kind 后续接入）。
//
// 契约：
//   - Steps 返回稳定有序的步骤表；同一 job 每次调用必须一致（恢复语义）。
//   - Execute 必须对同一 (job, step) 幂等或可安全重跑；worker 保证
//     已 completed 的步骤不会再次执行，但崩溃可能发生在「副作用已发生、
//     账本尚未记录」的窗口内，handler 应尽量让重跑可收敛。
//   - prior 是本次领取时账本已记录的步骤（含本次已提交步骤）快照，只读；
//     用于需要回读前序步骤证据的 handler（如 backup_export 的 verify 步骤）。
//   - 返回 error 即步骤失败：worker 记录 failed 步骤并把任务完成到 failed
//     终态；带 *HandlerError 时使用其 Code 作为账本 error_code。
type Handler interface {
	Steps(job Job) []StepSpec
	Execute(ctx context.Context, job Job, spec StepSpec, prior map[string]Step) (StepOutcome, error)
}

// HandlerError 给步骤失败附上账本 error_code（协议外的内部诊断字段）。
type HandlerError struct {
	Code string
	Err  error
}

func (e *HandlerError) Error() string {
	if e == nil {
		return ""
	}
	if e.Err == nil {
		return e.Code
	}
	return e.Err.Error()
}

func (e *HandlerError) Unwrap() error {
	if e == nil {
		return nil
	}
	return e.Err
}

// WorkerOptions 是 worker 的全部依赖（注入只在组装根发生；测试直接构造）。
type WorkerOptions struct {
	// Service 必填：账本/租约/状态推进原语的唯一入口。
	Service *Service
	// Backups 非 nil 时自动注册内置 backup_export handler（main 传入既有
	// backup.Manager 实例，不新建第二套快照管理器）。
	Backups *backup.Manager
	// Handlers 显式注册的 handler（测试/未来 kind）；与内置注册冲突时以本表
	// 为准，便于测试替换。
	Handlers map[Kind]Handler
	// Logger nil 时回落 slog.Default()。
	Logger *slog.Logger
	// PollInterval/LeaseTTL/Concurrency <=0 时回落 DefaultWorker*（与 config
	// 包兜底同口径；config 显式配 0 也会被拒绝或回落）。
	PollInterval time.Duration
	LeaseTTL     time.Duration
	Concurrency  int
	// Owner 是 worker 实例身份前缀（不含主机名/用户名，避免敏感环境信息
	// 进日志与库）；空时生成 "<pid>-<uuid>"。每次领取还会在该前缀后追加一个
	// 随机 fencing token，所以即使两个实例前缀相同，它们的租约也不会互相
	// 匹配（见 newLeaseToken）。
	Owner string
	// Now 时钟注入（测试固定时钟；与 Service.Now 同源时账本时间一致）。
	Now func() time.Time
}

// Worker 是常驻维护任务执行器：按已注册 kind 领取任务、心跳续租、逐步骤
// 记录并推进状态。它不开放任何 HTTP 端点（领取/续租/完成仍是本包内部原语）。
type Worker struct {
	svc          *Service
	logger       *slog.Logger
	pollInterval time.Duration
	leaseTTL     time.Duration
	concurrency  int
	owner        string
	now          func() time.Time
	handlers     map[Kind]Handler
}

// NewWorker 构造 worker；不做任何 IO（目录/库由依赖注入）。
func NewWorker(opts WorkerOptions) *Worker {
	logger := opts.Logger
	if logger == nil {
		logger = slog.Default()
	}
	now := opts.Now
	if now == nil {
		now = time.Now
	}
	pollInterval := opts.PollInterval
	if pollInterval <= 0 {
		pollInterval = DefaultWorkerPollInterval
	}
	leaseTTL := opts.LeaseTTL
	if leaseTTL <= 0 {
		leaseTTL = DefaultWorkerLeaseTTL
	}
	concurrency := opts.Concurrency
	if concurrency <= 0 {
		concurrency = DefaultWorkerConcurrency
	}
	owner := strings.TrimSpace(opts.Owner)
	if owner == "" {
		// pid 便于运维在日志/租约列定位进程；uuid 保证跨进程、跨重启唯一。
		owner = fmt.Sprintf("qimeng-worker-%d-%s", os.Getpid(), uuid.NewString())
	}
	handlers := make(map[Kind]Handler, len(opts.Handlers)+1)
	if opts.Backups != nil {
		handlers[KindBackupExport] = NewBackupExportHandler(opts.Backups)
	}
	for kind, handler := range opts.Handlers {
		handlers[kind] = handler
	}
	return &Worker{
		svc:          opts.Service,
		logger:       logger,
		pollInterval: pollInterval,
		leaseTTL:     leaseTTL,
		concurrency:  concurrency,
		owner:        owner,
		now:          now,
		handlers:     handlers,
	}
}

// newLeaseToken 为一次领取生成唯一租约令牌（"<实例 owner>/<随机 UUID>"）。
//
// 为什么不能只用实例身份：同一实例的租约若过期后被重新领取（或两个实例被
// 误配了相同 owner），旧执行上下文仍以为自己是持有者，而心跳/完成只比较
// owner 字符串时无法区分「旧租约」与「新租约」——两者 owner 相同，旧执行器
// 会继续续租并与新执行器并发执行同一任务。每次领取改用新 token 后，旧租约
// 的 owner 永远不再匹配，旧执行器的心跳/步骤/工件/终态写入都会被账本拒绝
// （fencing），从根上消除这种并发覆盖。
func (w *Worker) newLeaseToken() string {
	return w.owner + "/" + uuid.NewString()
}

// Start 由组合根调用一次：启动有界并发的领取循环，ctx 取消后停止领取并等
// 在途任务收敛（main 在关库前等它返回）。panic 由每轮循环的 safego 兜底，
// 单个任务崩溃只终止该任务，进程继续服务。
func (w *Worker) Start(ctx context.Context) {
	kinds := w.registeredKinds()
	if len(kinds) == 0 {
		// 没有 handler 时启动空转没有意义：明确留痕后直接返回（任务仍可经
		// HTTP 建账，等后续版本/外部执行器处理）。
		w.logger.Info("维护任务 worker 未注册任何 handler，不领取任务")
		return
	}
	names := make([]string, 0, len(kinds))
	for _, kind := range kinds {
		names = append(names, string(kind))
	}
	w.logger.Info("维护任务 worker 已启动",
		"owner", w.owner, "kinds", strings.Join(names, ","),
		"concurrency", w.concurrency,
		"pollInterval", w.pollInterval.String(), "leaseTTL", w.leaseTTL.String())
	var wg sync.WaitGroup
	for i := 0; i < w.concurrency; i++ {
		wg.Add(1)
		safego.Go(w.logger, "维护任务领取", func() {
			defer wg.Done()
			w.loop(ctx)
		})
	}
	wg.Wait()
}

// registeredKinds 返回已注册 handler 的 kind（排序后保证多 kind 领取顺序
// 稳定、测试可复现）。
func (w *Worker) registeredKinds() []Kind {
	kinds := make([]Kind, 0, len(w.handlers))
	for kind := range w.handlers {
		kinds = append(kinds, kind)
	}
	sort.Slice(kinds, func(i, j int) bool { return kinds[i] < kinds[j] })
	return kinds
}

// loop 是单个并发槽位的领取循环：领取到任务就同步执行（槽位被占用即达到
// 并发上限），空轮按 pollInterval 等待。
func (w *Worker) loop(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}
		claimed := w.claimAndProcessOne(ctx)
		if ctx.Err() != nil {
			return
		}
		if claimed {
			continue
		}
		if !waitContext(ctx, w.pollInterval) {
			return
		}
	}
}

// claimAndProcessOne 依次尝试各已注册 kind，领取到一笔就执行并返回 true。
// 按 kind 过滤领取的语义：未注册 handler 的 kind 永远保持 queued，供外部
// 执行器/后续版本处理，不会被本 worker 误消费。
func (w *Worker) claimAndProcessOne(ctx context.Context) bool {
	for _, kind := range w.registeredKinds() {
		// 每次领取使用全新 fencing token：即使同一任务在本实例内过期后被
		// 重领，旧执行上下文也无法再用旧 token 续租或写结果。
		leaseOwner := w.newLeaseToken()
		claimed, ok, err := w.svc.Claim(ctx, kind, leaseOwner, w.leaseTTL)
		if err != nil {
			if ctx.Err() == nil {
				w.logger.Warn("领取维护任务失败（下轮重试）", "kind", kind, "error", err)
			}
			return false
		}
		if !ok {
			continue
		}
		w.process(ctx, claimed, leaseOwner)
		return true
	}
	return false
}

// process 执行一笔已领取任务。恢复语义：按 handler 的步骤表顺序推进，
// status 已记录为 completed 的步骤跳过（不重复副作用），其余步骤从当前
// 状态机阶段继续；任何步骤失败即记录 failed 并终态 failed；真正执行完成
// 才 Complete(completed/partial)。
func (w *Worker) process(ctx context.Context, claimed Detail, leaseOwner string) {
	job := claimed.Job
	var (
		activeStep  StepSpec
		stepAttempt int
		stepStarted time.Time
	)
	// panic 兜底：只影响当前任务（safego 兜底下单个任务崩溃不杀进程），
	// 尽力把任务收敛到 failed 终态并记录当前步骤；崩溃前已提交的步骤保留。
	defer func() {
		if p := recover(); p != nil {
			w.logger.Error("维护任务执行 panic 已恢复（仅影响当前任务）",
				"jobId", job.ID, "kind", job.Kind, "step", activeStep.Name,
				"panic", p, "stack", string(debug.Stack()))
			w.failJob(context.WithoutCancel(ctx), job, leaseOwner, activeStep, stepAttempt, stepStarted,
				WorkerPanicCode, fmt.Sprintf("worker panic: %v", p))
		}
	}()

	handler, ok := w.handlers[job.Kind]
	if !ok {
		// 领取已按 kind 过滤，正常不可达；防御未来手工调用。
		w.logger.Warn("缺少 kind 的 handler，跳过任务", "jobId", job.ID, "kind", job.Kind)
		return
	}
	specs, err := planSteps(handler, job)
	if err != nil {
		w.failJob(context.WithoutCancel(ctx), job, leaseOwner, StepSpec{}, 0, time.Time{},
			StepFailedCode, "步骤规划不合法: "+err.Error())
		return
	}

	// 已记录步骤：completed 的跳过；其余（running/failed/pending/skipped）在
	// 本次领取中重新执行（幂等契约见 Handler 注释）。
	prior := make(map[string]Step, len(claimed.Steps))
	for _, step := range claimed.Steps {
		prior[step.Step] = step
	}
	// 详情端点只返回最近 MaxDetailSteps 条步骤；长任务更早的 completed 步骤
	// 会落在窗口外。从账本读取完整 completed/skipped 名集合补齐，否则那些
	// 步骤会被当成"从未执行"重复产生副作用（2026-11-08 修复）。
	completedNames, namesErr := w.svc.CompletedStepNames(ctx, job.ID)
	if namesErr != nil {
		// 读不到完整步骤集合时不执行任何步骤：停止本次领取，让租约到期后
		// 重试（与临时 DB 错误同款处理，不把可恢复错误写成 failed 终态）。
		w.logger.Warn("读取已完成步骤失败，暂停本次执行等待重领",
			"jobId", job.ID, "error", namesErr)
		return
	}
	for _, name := range completedNames {
		if _, seen := prior[name]; !seen {
			prior[name] = Step{Step: name, Status: StepCompleted}
		}
	}

	// jobCtx 是本任务的执行上下文（handler 专用）：租约丢失或心跳判定不可
	// 靠时主动 cancel，让 backup 等长步骤的在途副作用尽快停止，避免暂停/
	// 取消后 resume 或重领的新执行器与旧执行器并发执行同一步。账本收尾写入
	// 仍用 WithoutCancel 派生上下文，取消不会打断已开始的终态记录。
	jobCtx, jobCancel := context.WithCancel(ctx)
	defer jobCancel()
	// 心跳：租约 TTL/3 续租一次。失去租约（暂停/取消/被接管）时 cancel
	// jobCtx 并关闭 leaseLost；连续非租约失败或租约余量不足时同样取消在途
	// 步骤并停止心跳（任务保持原状态，等租约到期重领）。
	leaseLost := make(chan struct{})
	hbDone := make(chan struct{})
	hbCtx, hbCancel := context.WithCancel(ctx)
	var leaseLostOnce sync.Once
	safego.Go(w.logger, "维护任务租约心跳", func() {
		defer close(hbDone)
		interval := w.heartbeatInterval()
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		// 本地跟踪的租约到期时刻：初始来自领取结果，成功续租后刷新。
		var leaseUntil time.Time
		if job.LeaseUntil != nil {
			leaseUntil = *job.LeaseUntil
		}
		failures := 0
		for {
			select {
			case <-hbCtx.Done():
				return
			case <-ticker.C:
				err := w.svc.Heartbeat(hbCtx, job.ID, leaseOwner, w.leaseTTL)
				if err == nil {
					failures = 0
					leaseUntil = w.now().UTC().Add(w.leaseTTL)
					continue
				}
				if errors.Is(err, ErrLeaseLost) {
					// 暂停/取消/被接管：取消在途 handler，并让步骤边界停止。
					leaseLostOnce.Do(func() {
						jobCancel()
						close(leaseLost)
					})
					return
				}
				if hbCtx.Err() != nil {
					return
				}
				failures++
				w.logger.Warn("维护任务心跳失败（间歇重试）", "jobId", job.ID,
					"failures", failures, "error", err)
				remaining := time.Duration(0)
				if !leaseUntil.IsZero() {
					remaining = leaseUntil.Sub(w.now().UTC())
				}
				if failures >= maxWorkerHeartbeatFailures ||
					(!leaseUntil.IsZero() && remaining <= interval) {
					w.logger.Warn("维护任务续租不可靠（连续失败/租约余量不足），取消在途步骤并停止本轮执行",
						"jobId", job.ID, "failures", failures, "remaining", remaining.String())
					jobCancel()
					return
				}
			}
		}
	})
	defer func() {
		hbCancel()
		<-hbDone
	}()

	status := job.Status
	skipped := false
	for _, spec := range specs {
		if ctx.Err() != nil {
			// 进程退出：不写终态，租约过期后由下次启动重新领取并从已完成
			// 步骤续跑。
			w.logger.Info("维护任务因进程退出中止（保留已提交步骤）", "jobId", job.ID, "step", spec.Name)
			return
		}
		if jobCtx.Err() != nil {
			// 心跳已判定租约丢失或不可靠：在途步骤已取消，不再开始新步骤。
			w.logger.Info("维护任务租约不可用，停止后续步骤（保留已提交步骤）",
				"jobId", job.ID, "status", status, "nextStep", spec.Name)
			return
		}
		if channelClosed(leaseLost) {
			w.logger.Info("维护任务租约已丢失，停止后续步骤（保留已提交步骤）",
				"jobId", job.ID, "status", status, "nextStep", spec.Name)
			return
		}
		// 步骤边界复核租约：暂停/取消会清空租约，必须在开始新副作用前发现。
		if err := w.svc.Heartbeat(ctx, job.ID, leaseOwner, w.leaseTTL); err != nil {
			if errors.Is(err, ErrLeaseLost) || ctx.Err() != nil {
				jobCancel()
				w.logger.Info("维护任务租约已失效，停止后续步骤（保留已提交步骤）", "jobId", job.ID, "step", spec.Name)
				return
			}
			// P2-2：非租约错误（DB 忙/临时故障）不把任务标 failed——停止本槽
			// 并取消 jobCtx，租约到期后由重领路径接续（已完成步骤不会重复副作用）。
			w.logger.Warn("维护任务续租遇非租约错误，停止本轮执行（任务保持原状态，租约到期后重领）",
				"jobId", job.ID, "step", spec.Name, "error", err)
			jobCancel()
			return
		}
		next, ok := advanceStage(status, spec.Stage)
		if !ok {
			w.failJob(context.WithoutCancel(ctx), job, leaseOwner, StepSpec{}, 0, time.Time{},
				StepFailedCode, fmt.Sprintf("无法从 %s 推进到步骤 %q 的阶段 %s", status, spec.Name, spec.Stage))
			return
		}
		if next != status {
			if _, err := w.svc.AdvanceStatus(ctx, job.ID, leaseOwner, next); err != nil {
				if errors.Is(err, ErrLeaseLost) || ctx.Err() != nil {
					w.logger.Info("维护任务状态推进时租约已失效，停止后续步骤", "jobId", job.ID, "step", spec.Name)
					return
				}
				if !errors.Is(err, ErrStateConflict) {
					// 非状态冲突的 DB/临时错误同样不判定任务失败：停止本轮，
					// 等租约到期重领（与续租错误分类一致）。
					w.logger.Warn("维护任务状态推进遇临时错误，停止本轮执行（任务保持原状态）",
						"jobId", job.ID, "step", spec.Name, "error", err)
					jobCancel()
					return
				}
				w.failJob(context.WithoutCancel(ctx), job, leaseOwner, StepSpec{}, 0, time.Time{},
					"STATE_CONFLICT", fmt.Sprintf("状态推进 %s -> %s 失败: %v", status, next, err))
				return
			}
			status = next
		}
		if priorStep, done := prior[spec.Name]; done && priorStep.Status == StepCompleted {
			continue
		}

		attempt := prior[spec.Name].Attempt + 1
		startedAt := w.now().UTC()
		if err := w.svc.RecordStep(ctx, StepInput{
			JobID: job.ID, Step: spec.Name, Status: StepRunning,
			Attempt: attempt, StartedAt: &startedAt, Detail: json.RawMessage(`{}`),
			LeaseOwner: leaseOwner,
		}); err != nil {
			if ctx.Err() != nil {
				// 进程退出打断在步骤开始前：不写终态，租约过期后重跑该步骤。
				w.logger.Info("维护任务因进程退出中止（步骤尚未开始）", "jobId", job.ID, "step", spec.Name)
				return
			}
			if w.stopOnLeaseLost(err, job.ID, "记录步骤开始") {
				return
			}
			w.failJob(context.WithoutCancel(ctx), job, leaseOwner, spec, attempt, startedAt,
				StepFailedCode, "记录步骤开始失败: "+err.Error())
			return
		}
		activeStep, stepAttempt, stepStarted = spec, attempt, startedAt

		// handler 使用 jobCtx：租约丢失时心跳会 cancel 它，backup 等长步骤的
		// 在途副作用立即停止，不再与 resume/重领后的新执行器并发执行同一步。
		outcome, execErr := handler.Execute(jobCtx, job, spec, prior)
		if ctx.Err() != nil {
			// 进程退出打断步骤：不判定失败（下次启动重跑该步骤）。
			w.logger.Info("维护任务步骤因进程退出中断（下次启动续跑）", "jobId", job.ID, "step", spec.Name)
			return
		}
		if jobCtx.Err() != nil {
			// 租约丢失/心跳判定不可靠导致主动取消：不判定失败、不写终态，
			// 已提交步骤保留，重领后从该步骤之后续跑。
			w.logger.Info("维护任务在途步骤已取消（租约不可用），停止执行（保留已提交步骤）",
				"jobId", job.ID, "step", spec.Name)
			return
		}
		if execErr != nil {
			if channelClosed(leaseLost) {
				// 暂停/取消/接管发生在本步骤期间：不把被打断的尝试记成失败，
				// 由当前状态（paused/cancelled/他人持有）决定后续。
				w.logger.Info("维护任务步骤执行期间租约丢失，不判定失败（保留已提交步骤）",
					"jobId", job.ID, "step", spec.Name)
				return
			}
			code, message := handlerFailure(execErr)
			w.failJob(context.WithoutCancel(ctx), job, leaseOwner, spec, attempt, startedAt, code, message)
			return
		}
		// 副作用已完成：尝试把步骤与工件落账，并携带本次领取的 fencing token。
		// 若租约在步骤执行期间过期/被重领/被暂停取消，账本会拒绝写入
		// （ErrLeaseLost），旧实例只能停止，不能覆盖新持有者或终态结果。
		for i := range outcome.Artifacts {
			artifact := outcome.Artifacts[i]
			artifact.JobID = job.ID
			artifact.LeaseOwner = leaseOwner
			if artifact.CreatedAt.IsZero() {
				artifact.CreatedAt = w.now().UTC()
			}
			if err := w.svc.AddArtifact(context.WithoutCancel(ctx), artifact); err != nil {
				if w.stopOnLeaseLost(err, job.ID, "记录工件") {
					return
				}
				w.failJob(context.WithoutCancel(ctx), job, leaseOwner, spec, attempt, startedAt,
					StepFailedCode, "记录工件失败: "+err.Error())
				return
			}
		}
		finishedAt := w.now().UTC()
		stepStatus := StepCompleted
		if outcome.Skip {
			stepStatus = StepSkipped
			skipped = true
		}
		detail := outcome.Detail
		if len(detail) == 0 {
			detail = json.RawMessage(`{}`)
		}
		if err := w.svc.RecordStep(context.WithoutCancel(ctx), StepInput{
			JobID: job.ID, Step: spec.Name, Status: stepStatus,
			Attempt: attempt, StartedAt: &startedAt, FinishedAt: &finishedAt, Detail: detail,
			LeaseOwner: leaseOwner,
		}); err != nil {
			if w.stopOnLeaseLost(err, job.ID, "记录步骤完成") {
				return
			}
			w.failJob(context.WithoutCancel(ctx), job, leaseOwner, spec, attempt, startedAt,
				StepFailedCode, "记录步骤完成失败: "+err.Error())
			return
		}
		prior[spec.Name] = Step{
			Step: spec.Name, Status: stepStatus, Attempt: attempt,
			StartedAt: &startedAt, FinishedAt: &finishedAt, Detail: detail,
		}
		activeStep, stepAttempt, stepStarted = StepSpec{}, 0, time.Time{}
		if channelClosed(leaseLost) {
			w.logger.Info("维护任务步骤已完成并记录，租约已丢失，停止后续步骤",
				"jobId", job.ID, "step", spec.Name)
			return
		}
	}

	// 完成前把状态补齐到 verifying（账本状态机要求 verifying -> completed/
	// partial）：短流水线（末步骤阶段早于 verifying）与异常恢复都靠这里收敛，
	// 避免出现「步骤全完成但状态停在 inventory/applying」的不可判定终态。
	if _, settleErr := w.settleStages(ctx, job.ID, leaseOwner, status, StatusVerifying); settleErr != nil {
		if errors.Is(settleErr, ErrLeaseLost) || ctx.Err() != nil {
			w.logger.Info("完成前推进状态时租约已失效，停止", "jobId", job.ID, "step", "")
			return
		}
		if !errors.Is(settleErr, ErrStateConflict) {
			// 非状态冲突错误（DB/临时故障）不判定任务失败；保持原状态等重领。
			w.logger.Warn("完成前状态推进遇临时错误，停止本轮执行（任务保持原状态）",
				"jobId", job.ID, "error", settleErr)
			return
		}
		w.failJob(context.WithoutCancel(ctx), job, leaseOwner, StepSpec{}, 0, time.Time{},
			StepFailedCode, "完成前状态推进失败: "+settleErr.Error())
		return
	}

	final := StatusCompleted
	if skipped {
		final = StatusPartial
	}
	if _, err := w.svc.Complete(context.WithoutCancel(ctx), job.ID, leaseOwner, final, "", ""); err != nil {
		if errors.Is(err, ErrLeaseLost) {
			w.logger.Info("完成维护任务时租约已失效（暂停/取消/接管），保留已提交步骤",
				"jobId", job.ID, "status", final)
			return
		}
		w.logger.Error("完成维护任务失败（租约到期后将重新领取重试）",
			"jobId", job.ID, "status", final, "error", err)
		return
	}
	w.logger.Info("维护任务完成", "jobId", job.ID, "kind", job.Kind, "status", final)
}

// failJob 记录失败步骤并把任务收敛到 failed 终态（尽力而为：租约已失则
// 打印后放弃，由暂停/取消/接管方决定最终状态）。
func (w *Worker) failJob(ctx context.Context, job Job, leaseOwner string, step StepSpec, attempt int, startedAt time.Time, code, message string) {
	code, message = sanitizeFailure(code, message)
	recordCtx := context.WithoutCancel(ctx)
	if step.Name != "" {
		finishedAt := w.now().UTC()
		wasStarted := !startedAt.IsZero()
		var started *time.Time
		if wasStarted {
			value := startedAt.UTC()
			started = &value
		}
		detail, marshalErr := json.Marshal(map[string]string{"errorCode": code, "message": message})
		if marshalErr != nil {
			detail = json.RawMessage(`{}`)
		}
		if wasStarted {
			if err := w.svc.RecordStep(recordCtx, StepInput{
				JobID: job.ID, Step: step.Name, Status: StepFailed,
				Attempt: attempt, StartedAt: started, FinishedAt: &finishedAt, Detail: detail,
				LeaseOwner: leaseOwner,
			}); err != nil {
				w.logger.Warn("记录失败步骤失败", "jobId", job.ID, "step", step.Name, "error", err)
			}
		}
	}
	if _, err := w.svc.Complete(recordCtx, job.ID, leaseOwner, StatusFailed, code, message); err != nil {
		if errors.Is(err, ErrLeaseLost) {
			w.logger.Warn("标记任务失败时租约已失效（暂停/取消/接管），保持当前状态",
				"jobId", job.ID, "errorCode", code)
			return
		}
		w.logger.Error("标记任务失败时写库失败（租约到期后将重新领取重试）",
			"jobId", job.ID, "errorCode", code, "error", err)
		return
	}
	w.logger.Warn("维护任务失败", "jobId", job.ID, "kind", job.Kind, "step", step.Name,
		"errorCode", code, "errorMessage", message)
}

// stopOnLeaseLost 统一识别「写账本时租约已失效」：旧执行器的步骤/工件/状态
// 写入被 fencing 拒绝后只记日志并停止，不把任务标成 failed（任务已由新执行
// 器接管，或已被暂停/取消，终态由当前持有者/公开端点决定）。
func (w *Worker) stopOnLeaseLost(err error, jobID, phase string) bool {
	if !errors.Is(err, ErrLeaseLost) {
		return false
	}
	w.logger.Info("维护任务租约已失效，停止当前执行（旧实例不覆盖新结果）",
		"jobId", jobID, "phase", phase)
	return true
}

// heartbeatInterval 是租约续租间隔（TTL/3）：一次自检式续租失败（DB 忙）
// 仍有两个周期余量，不会把健康任务误判为过期。
func (w *Worker) heartbeatInterval() time.Duration {
	interval := w.leaseTTL / 3
	if interval <= 0 {
		interval = time.Second
	}
	return interval
}

// planSteps 校验 handler 步骤表的恢复契约：步骤名唯一稳定、阶段合法且能沿
// 状态机从 queued 相邻推进（inventory 必须是首阶段——账本流水线统一从
// inventory 开始；允许 research/planned 给研究类 kind 用）。
func planSteps(handler Handler, job Job) ([]StepSpec, error) {
	specs := handler.Steps(job)
	if len(specs) == 0 {
		return nil, errors.New("handler 未声明任何步骤")
	}
	seen := make(map[string]struct{}, len(specs))
	previous := StatusQueued
	for _, spec := range specs {
		name := strings.TrimSpace(spec.Name)
		if name == "" || !checkRunes(name, MaxStepNameRunes) {
			return nil, fmt.Errorf("步骤名须为 1–%d 字符", MaxStepNameRunes)
		}
		if _, duplicated := seen[name]; duplicated {
			return nil, fmt.Errorf("步骤名 %q 重复", name)
		}
		seen[name] = struct{}{}
		if pipelineRank(spec.Stage) < 0 {
			return nil, fmt.Errorf("步骤 %q 的阶段 %q 不是流水线阶段", name, spec.Stage)
		}
		if spec.Stage != previous && !previous.CanTransitionTo(spec.Stage) {
			return nil, fmt.Errorf("步骤 %q 的阶段 %s 不能从 %s 推进", name, spec.Stage, previous)
		}
		previous = spec.Stage
	}
	if specs[0].Stage != StatusInventory {
		return nil, errors.New("首个步骤的阶段须为 inventory")
	}
	return specs, nil
}

// advanceStage 返回从 current 到目标阶段所需的下一跳；same 阶段返回 current。
// 处理器步骤表已验证相邻可推进，因此这里最多一跳；恢复时账本状态可能领先
// 于尚未执行的步骤（理论上不应出现），返回 false 由调用方按不一致处理。
func advanceStage(current, target Status) (Status, bool) {
	if pipelineRank(target) <= pipelineRank(current) {
		return current, true
	}
	if pipelineRank(target) == pipelineRank(current)+1 || current.CanTransitionTo(target) {
		return target, true
	}
	return current, false
}

// settleStages 把任务状态沿状态机推进到 target（当前只用于完成前的
// verifying 收敛）：恢复后状态可能落后于已完成步骤，短流水线也可能以较早
// 阶段收尾；逐阶段单跳推进比一次性跳跃更容易保持状态机自洽。
func (w *Worker) settleStages(ctx context.Context, jobID, leaseOwner string, status, target Status) (Status, error) {
	for _, stage := range []Status{StatusInventory, StatusResearch, StatusPlanned, StatusApplying, StatusVerifying} {
		if pipelineRank(stage) <= pipelineRank(status) || pipelineRank(stage) > pipelineRank(target) {
			continue
		}
		next, ok := advanceStage(status, stage)
		if !ok || next == status {
			return status, fmt.Errorf("无法从 %s 推进到 %s", status, stage)
		}
		if _, err := w.svc.AdvanceStatus(ctx, jobID, leaseOwner, next); err != nil {
			return status, err
		}
		status = next
	}
	if pipelineRank(status) != pipelineRank(target) {
		return status, fmt.Errorf("最终状态 %s 未到达 %s", status, target)
	}
	return status, nil
}

// pipelineRank 给流水线阶段排序（仅 worker 使用的线性顺序；等待态/终态/
// paused 不在此列）。rank 只用于恢复态比较，合法性仍以状态机为准。
func pipelineRank(status Status) int {
	switch status {
	case StatusQueued:
		return 0
	case StatusInventory:
		return 1
	case StatusResearch:
		return 2
	case StatusPlanned:
		return 3
	case StatusApplying:
		return 4
	case StatusVerifying:
		return 5
	default:
		return -1
	}
}

// channelClosed 非阻塞判断信号通道是否已关闭。
func channelClosed(ch <-chan struct{}) bool {
	select {
	case <-ch:
		return true
	default:
		return false
	}
}

// waitContext 等待 d 或 ctx 取消；返回 false 表示 ctx 已取消。
func waitContext(ctx context.Context, d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

// handlerFailure 把 handler 错误归一为账本 error_code/message（都受账本长度
// 上限约束；未附 HandlerError 时用 STEP_FAILED）。
func handlerFailure(err error) (string, string) {
	code := StepFailedCode
	message := err.Error()
	var handlerErr *HandlerError
	if errors.As(err, &handlerErr) {
		if strings.TrimSpace(handlerErr.Code) != "" {
			code = handlerErr.Code
		}
	}
	return sanitizeFailure(code, message)
}

// sanitizeFailure 截断/兜底 error_code 与 error_message（账本 CHECK 之外
// 还有长度上限，超长会让 Complete 返回 ErrInvalidInput 而丢失失败证据）。
func sanitizeFailure(code, message string) (string, string) {
	code = strings.TrimSpace(code)
	if code == "" {
		code = StepFailedCode
	}
	return truncateRunes(code, MaxErrorCodeRunes), truncateRunes(message, MaxErrorMessageRunes)
}

// truncateRunes 按字符（非字节）截断，保证中文错误信息不被切坏。
func truncateRunes(value string, max int) string {
	runes := []rune(value)
	if len(runes) <= max {
		return value
	}
	return string(runes[:max])
}
