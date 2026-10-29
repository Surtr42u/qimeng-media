package media.qimeng.app.core.model

/** 服务端混合发现结果；客户端只展示理由，不复制选池和排序。 */
data class DiscoveryEntry(val assetId: String, val title: String, val thumbUrl: String?, val reason: String)
