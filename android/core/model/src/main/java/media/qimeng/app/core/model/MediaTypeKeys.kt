package media.qimeng.app.core.model

/**
 * openapi MediaType 枚举字面量单源（image/animated_image/video）。
 * **双写同步责任**：与 `api/openapi.yaml` 的 MediaType schema（含 /stats/most-viewed、
 * /assets 等处内联的同集 mediaType 枚举）双写同步——协议侧改动须同步此处，反之亦然
 * （协议侧定位：openapi.yaml 搜 `enum: [image`）。App 内消费点（趋势取数参数、系列
 * 名映射、MediaKind↔协议键映射、SDK 枚举映射）一律引此处常量，禁止手抄字面量。
 */
object MediaTypeKeys {

    /** 图片 */
    const val IMAGE = "image"

    /** 视频 */
    const val VIDEO = "video"

    /** 动图（协议字面量 animated_image） */
    const val ANIMATED_IMAGE = "animated_image"
}

/**
 * openapi `/stats/trends` source 查询参数枚举字面量单源（normal/cos）。
 * **双写同步责任**：与 `api/openapi.yaml` 的 source 参数枚举（口径 = DOMAIN_RULES §6
 * 分区判定：normal=不关联 COS 作者 / cos=关联 COS 作者）双写同步——协议侧改动须同步
 * 此处，反之亦然（协议侧定位：openapi.yaml 搜 `enum: [normal, cos]`）。
 * 注意与库类型 kind 枚举 `[normal, cos]`（libraries）及分区枚举 `[all, regular, cos]`
 * 不是同一参数，勿混引。
 */
object SourceKeys {

    /** 常规来源（不关联 COS 作者） */
    const val NORMAL = "normal"

    /** COS 来源（关联 COS 作者） */
    const val COS = "cos"
}
