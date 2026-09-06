package media.qimeng.app.feature.detail

/**
 * 详情路由契约单源（修补：原先 "assetId" 键与路由串在 feature/app 两侧各持一份、注释互斥，
 * 收敛到 feature:detail——feature 禁依赖 :app（ADR-0010），app 侧 QimengNavHost 反向引用此处合法）。
 */
object DetailRoutes {

    /** 详情路由参数键（路由占位符与 DetailViewModel SavedStateHandle 读取同键） */
    const val KEY_ASSET_ID = "assetId"

    /** 资产详情路由模式（M4-3；列表/详情推荐栏进入，入栈隐藏底栏） */
    const val DETAIL_ROUTE = "detail/{$KEY_ASSET_ID}"

    /** 详情路由构建（防 route 字符串第二次手抄） */
    fun detailRoute(assetId: String): String = "detail/$assetId"
}
