package media.qimeng.app.core.data.device

/**
 * 设备级授权的纯判定（2026-11-07 task-17，ADR-0055/0057）。
 *
 * 关键边界：设备结果包端点必须使用**专用设备 AI 令牌**（服务端 `ai_clients` 的
 * `device.apply` scope，且 `device_ids` 绑定本机 targetDeviceId）。管理员登录会话
 * token 是全权凭据，服务端会把它当管理员放行，无法证明「这台手机就是目标设备」；
 * 因此本 App 有意不提供「无设备令牌时回退会话 token」的路径。
 *
 * 导入作者 TXT / v2 备份仍走当前登录会话（本机内嵌实例或用户连接的 NAS）；设备令牌
 * 只用于领取/段下载/回传等设备端点。两者不是同一凭据，也不互相提升。
 */
object DeviceApplyAuthorization {

    /** 设置页与 Worker 共用的缺失提示（明确会话 token 不能替代设备级授权）。 */
    const val SESSION_TOKEN_NOT_DEVICE_AUTH_MESSAGE =
        "未配置设备 AI 令牌：后台领取与立即应用不可用。登录会话 token 不能用于设备级授权；" +
            "请在服务器本机执行 qimeng ai-client create -label <名称> -scopes device.apply " +
            "-device-ids <设备标签>，把只显示一次的 token 填入设备令牌。"

    /** 设备标签缺失时的提示（与令牌缺失分开，便于用户定位）。 */
    const val MISSING_TARGET_DEVICE_MESSAGE =
        "请填写设备标签（1–100 位字母、数字或 ._:-）；它必须与设备令牌的 device-ids 完全一致。"

    /**
     * 判定是否可以发起设备端点请求。trim 后校验：UI 保存与 Worker 执行使用同一口径。
     */
    fun check(targetDeviceId: String, deviceToken: String): AuthorizationState {
        val target = targetDeviceId.trim()
        val token = deviceToken.trim()
        if (target.isEmpty()) return AuthorizationState.Missing(MISSING_TARGET_DEVICE_MESSAGE)
        if (!DeviceApplyPolicy.isValidTargetDeviceId(target)) {
            return AuthorizationState.Missing(
                "设备标签只允许 1–100 位字母、数字或 ._:-，且不能包含空格或其他字符。",
            )
        }
        if (token.isEmpty()) return AuthorizationState.Missing(SESSION_TOKEN_NOT_DEVICE_AUTH_MESSAGE)
        return AuthorizationState.Ready
    }

    sealed interface AuthorizationState {
        data object Ready : AuthorizationState
        data class Missing(val message: String) : AuthorizationState
    }
}
