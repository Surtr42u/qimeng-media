package media.qimeng.app.feature.detail.video

/**
 * requestedOrientation 写点条件化裁决（任务U11 批次C，U10-1 三步实验续）。
 *
 * 背景：U10-1 真机三步实验证实「App 启动且仅启动瞬间一次」触发 nubia ROM 把系统
 * 自动旋转开关重新打开（安装无辜、前台静置不触发）；App 无 WRITE_SETTINGS 权限，
 * 主嫌疑=ROM 智能旋转对 Activity 方向写入的反应——哪怕写入值与当前一致。本文件
 * 把「仅目标 ≠ 当前值才落写」收敛为纯函数：防御回退/离场恢复等常与当前值相同的
 * 写点全部裁掉（典型=冷启动恢复期 UNSPECIFIED→UNSPECIFIED 的冗余兜底写），仅真
 * 跨方向迁移才写。中途横竖切换语义不变（跨方向必写）。
 *
 * 纯 JVM 可测：不 import android.content.pm（模块纪律，见
 * VideoFullscreenStateMachine 的同类取舍）；ActivityInfo 常量按值镜像，对齐由
 * 单测编译期交叉断言锁定（常量在 JVM 单测可用——静态 final int 编译期内联）。
 */

/** 值 = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED（自然基线，App 不锁方向） */
internal const val SCREEN_ORIENTATION_UNSPECIFIED = -1

/** 值 = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE */
internal const val SCREEN_ORIENTATION_LANDSCAPE = 0

/** 值 = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT */
internal const val SCREEN_ORIENTATION_PORTRAIT = 1

/** 状态机方向指令 → Activity 方向常量（执行层唯一映射点） */
internal fun VideoFullscreenOrientation.toScreenOrientation(): Int = when (this) {
    VideoFullscreenOrientation.PORTRAIT -> SCREEN_ORIENTATION_PORTRAIT
    VideoFullscreenOrientation.LANDSCAPE -> SCREEN_ORIENTATION_LANDSCAPE
}

/**
 * 方向写入裁决：目标 ≠ 当前才返回目标值（调用方落写），相同返回 null（跳过写）。
 * 同值写不是 no-op——它仍是一次 requestedOrientation 写事务，正是 U10-1 实验
 * 圈出的 ROM 反应触发面。
 */
internal fun resolveOrientationWrite(currentRequested: Int, target: Int): Int? =
    if (currentRequested != target) target else null
