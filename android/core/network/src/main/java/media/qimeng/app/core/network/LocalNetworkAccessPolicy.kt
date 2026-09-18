package media.qimeng.app.core.network

/**
 * 局域网权限判定（任务P P4b，2026-09-19 用户拍板升 targetSdk 37）。
 *
 * Android 17（API 37）起系统对 targetSdk 37 应用强制 ACCESS_LOCAL_NETWORK 运行时权限
 * （developer.android.com/about/versions/17/behavior-changes-17；Android 16 该权限为
 * opt-in 不强制），未授权时连局域网设备直接失败。本 App 数据面（浏览/播放/上传/打点）
 * 全走局域网连 NAS，是全 App 最重的行为变更影响点。
 *
 * 版本线（37）在此单源：两处消费方（登录提交门 feature:login LoginScreen、冷启动补请求
 * :app MainActivity）一律调 [shouldRequestLocalNetworkPermission]，禁再手抄版本号
 * （代码卫生约束 2/4：与系统行为联动的值只能有一处定义）。保持纯函数无 Android 依赖，
 * 便于 JVM 单测；权限名常量与 checkSelfPermission 调用留在各消费方（平台 API 所在处）。
 */
const val ANDROID_17_SDK_INT = 37

/**
 * 是否需要发起 ACCESS_LOCAL_NETWORK 运行时请求：仅 Android 17+ 系统且尚未授权时为真。
 * 旧系统恒否（新检查逻辑不存在于旧系统，与 targetSdk 声明无关）；已授权恒否。
 */
fun shouldRequestLocalNetworkPermission(sdkInt: Int, granted: Boolean): Boolean =
    sdkInt >= ANDROID_17_SDK_INT && !granted
