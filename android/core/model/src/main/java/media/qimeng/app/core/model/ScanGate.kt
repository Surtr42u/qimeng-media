package media.qimeng.app.core.model

/**
 * 扫描充电门（批C 任务Q C-3，PROJECT_PLAN M6 性能项）：本机模式下扫描烧的是手机自己的
 * 电，未充电时不立即扫、记待扫标记等接通电源补扫；连 NAS 的常规模式不受限（扫描耗的
 * 是 NAS 的电）。冻结设计=纯 App 侧，零协议改动。
 *
 * 纯函数（无 IO，单测锁定），判定输入由调用方采集：
 * - isCharging：BatteryManager ACTION_BATTERY_CHANGED sticky 查询（core:data）；
 * - settingOn：「仅充电时扫描」设置项（DataStore，默认开）；
 * - isLocalMode：ServerConfigDataSource 当前地址是否本机模式预设（core:network
 *   ServerAddress.isLocalModePreset，连 localhost:18430 语义）。
 */
object ScanGate {

    /**
     * 本次「重扫」请求是否应推迟（true=不立即扫、记待扫标记）。
     * 只有「本机模式 + 设置开 + 未充电」三者同时成立才推迟；任一不满足都直接扫：
     * - 非本机模式：扫描耗 NAS 的电，不受限（冻结语义）；
     * - 设置关：用户显式关闭了省电约束，尊重；
     * - 充电中：直接扫（含 BATTERY_STATUS_FULL 满电形态，由调用方归一为 isCharging）。
     */
    fun shouldDeferScan(isCharging: Boolean, settingOn: Boolean, isLocalMode: Boolean): Boolean =
        isLocalMode && settingOn && !isCharging
}
