package media.qimeng.app.core.data.device

/**
 * 领取前的本地前置校验（2026-11-08 task-21 修复 P2-1，纯逻辑/JVM 单测锁定）。
 *
 * 为什么必须在 claim 之前：服务端结果包一旦被领取，客户端错误只能以终态结果回传
 * （failed 也是终态、不可覆盖），PC 侧必须重新登记；而登录会话/维护目录/全文件访问
 * 都是**用户可修复**的本地条件。先检查再 claim，任务保持 pending，用户按提示授权后
 * 点「立即应用」即可继续，不消耗任务、不产生误导性 failed。
 *
 * 设备标签/令牌的配置检查在 [DeviceApplyAuthorization]（更早执行）；本对象只覆盖
 * 「任务已存在但本机还不具备应用条件」的三类可修复前置。
 */
object DeviceApplyPreconditions {

    /**
     * 登录会话缺失/失效提示。[DeviceApplyRunner] 用当前会话执行作者 TXT 与 v2 备份导入、
     * 两代备份导出；设备 AI 令牌只证明设备身份，不提供业务写权限。
     */
    const val LOGIN_REQUIRED_MESSAGE =
        "未登录或登录会话已失效：应用结果包需要当前登录会话（导入作者TXT/v2备份、导出两代备份）。" +
            "任务保持待领取，请在设置页重新登录后点「立即应用」重试。"

    /** 依次检查登录会话 → 维护根 → 所有文件访问；返回第一个阻塞原因。 */
    fun evaluate(
        sessionLoggedIn: Boolean,
        maintenanceRoot: String?,
        hasAllFilesAccess: Boolean,
    ): PreconditionState {
        if (!sessionLoggedIn) return PreconditionState.Blocked(LOGIN_REQUIRED_MESSAGE)
        val gate = DeviceApplyLocalBackup.gate(maintenanceRoot, hasAllFilesAccess)
        return when (gate) {
            LocalWriteGate.Ready -> PreconditionState.Ready
            is LocalWriteGate.Blocked -> PreconditionState.Blocked(gate.message)
        }
    }
}

/** 领取前本地前置状态；[Blocked.message] 进入本机状态行与 UI 提示，不含路径/令牌。 */
sealed interface PreconditionState {
    data object Ready : PreconditionState
    data class Blocked(val message: String) : PreconditionState
}
