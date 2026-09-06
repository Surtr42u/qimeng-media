package media.qimeng.app.feature.detail

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * 舞台动作穿墙通道（DetailStageActions + LocalDetailStageActions）已于 3d 解冻批删除：
 * DetailStage.kt 解冻后改以具名参数向 ImageStage/VideoStage 逐层下发动作（DetailScreen 单源），
 * CompositionLocal 不再有存在理由（技术债清偿）。本文件仅存 findActivity（沉浸模式取宿主
 * window 用，DetailScreen SystemBarsImmersiveEffect 消费）。
 */

/** Compose 上下文逐层解包找宿主 Activity（沉浸模式取 window 用；判空由调用方处理——LEGACY_REQUIREMENTS E） */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
