package media.qimeng.app.feature.detail

/**
 * 资产编辑页路由契约单源（DetailRoutes 同范式；feature 禁依赖 :app，壳层
 * QimengNavHost 反向引用此处合法）。入口 = 详情作者 Sheet「编辑作者与来源」。
 */
object AssetEditRoutes {

    /** 路由参数键——单源在 [DetailRoutes.KEY_ASSET_ID]（同包引用；两页路由契约同键，防两处手抄漂移） */
    const val KEY_ASSET_ID = DetailRoutes.KEY_ASSET_ID

    /** 资产编辑路由模式（pushed 覆盖页：入栈隐藏底栏，返回栈语义与 detail 同族） */
    const val ASSET_EDIT_ROUTE = "assetEdit/{$KEY_ASSET_ID}"

    /** 路由构建（防 route 字符串第二次手抄） */
    fun assetEditRoute(assetId: String): String = "assetEdit/$assetId"
}
