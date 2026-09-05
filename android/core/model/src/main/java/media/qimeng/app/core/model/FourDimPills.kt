package media.qimeng.app.core.model

/**
 * 四维药丸装配的纯模型层（相册/收藏共用；历史页用其维度子集）。
 * UI 层只把 [PillSpec] 翻译成胶囊控件——状态→文案/激活/点击负载的推导全部在此，
 * 保证多页一套口径（代码卫生：同一逻辑禁止第 2 份手抄）。
 */

/** 一条药丸的渲染规格；payload=null 表示「全部」胶囊（点击=清本行）或分区/类型枚举位 */
data class PillSpec(
    val text: String,
    val selected: Boolean,
    /** 点击回传：分区=Zone、作者/角色=FacetOption（null=清行）、类型=MediaKind（null=全部） */
    val payload: Any?,
)

/** 维度芯片渲染规格（数量不含「全部/收起」） */
data class DimChipSpec(
    val dim: AlbumDim,
    val text: String,
    val selected: Boolean,
)

/** 四维装配所需的最小状态快照（各页 UiState 投影进来） */
data class FourDimPillModel(
    val filter: AlbumFilterState,
    val activeDim: AlbumDim,
    val partitionOptions: List<FacetOption>,
    val authorOptions: List<FacetOption>,
    val characterOptions: List<FacetOption>,
    val typeOptions: List<FacetOption>,
    val totalForAllPill: Int?,
)

object FourDimPills {

    /** 分区桶 key（协议 Partition 字面值） */
    private const val KEY_ALL = "all"
    private const val KEY_REGULAR = "regular"
    private const val KEY_COS = "cos"

    fun zoneFromKey(key: String): Zone = when (key) {
        KEY_REGULAR -> Zone.REGULAR
        KEY_COS -> Zone.COS
        else -> Zone.ALL
    }

    fun zoneToKey(zone: Zone): String = when (zone) {
        Zone.ALL -> KEY_ALL
        Zone.REGULAR -> KEY_REGULAR
        Zone.COS -> KEY_COS
    }

    /** 类型桶 key → MediaKind（key=all = 全部 = null） */
    fun mediaKindFromKey(key: String): MediaKind? =
        if (key == KEY_ALL) null else MediaKind.valueOf(key.uppercase())

    fun mediaKindToKey(kind: MediaKind?): String = when (kind) {
        null -> KEY_ALL
        MediaKind.IMAGE -> "image"
        MediaKind.ANIMATED_IMAGE -> "animated_image"
        MediaKind.VIDEO -> "video"
    }

    /** 维度芯片行（文字带数量；分区/类型行减去「全部」桶） */
    fun dimChips(model: FourDimPillModel, dims: List<AlbumDim> = AlbumDim.entries): List<DimChipSpec> =
        dims.map { dim ->
            val count = when (dim) {
                AlbumDim.PARTITION -> (model.partitionOptions.size - 1).coerceAtLeast(0)
                AlbumDim.AUTHOR -> model.authorOptions.size
                AlbumDim.CHARACTER -> model.characterOptions.size
                AlbumDim.TYPE -> (model.typeOptions.size - 1).coerceAtLeast(0)
            }
            DimChipSpec(
                dim = dim,
                text = "${dim.label} ($count)",
                selected = model.activeDim == dim,
            )
        }

    /** 当前维度药丸行（「其他」置底已由调用方在候选上先做 withOtherBucketLast） */
    fun pillsFor(model: FourDimPillModel, dim: AlbumDim = model.activeDim): List<PillSpec> =
        when (dim) {
            AlbumDim.PARTITION -> model.partitionOptions.map { option ->
                PillSpec(
                    text = option.labelWithCount(),
                    selected = option.key == zoneToKey(model.filter.partition),
                    payload = zoneFromKey(option.key),
                )
            }
            AlbumDim.AUTHOR -> listOf(allPillSpec(model)) + model.authorOptions.map { option ->
                PillSpec(
                    text = option.labelWithCount(),
                    selected = AlbumFilter.isAuthorActive(model.filter, option),
                    payload = option,
                )
            }
            AlbumDim.CHARACTER -> listOf(allPillSpec(model)) + model.characterOptions.map { option ->
                PillSpec(
                    text = option.labelWithCount(),
                    selected = AlbumFilter.isCharacterActive(model.filter, option),
                    payload = option,
                )
            }
            AlbumDim.TYPE -> model.typeOptions.map { option ->
                PillSpec(
                    text = option.labelWithCount(),
                    selected = option.key == mediaKindToKey(model.filter.mediaType),
                    payload = mediaKindFromKey(option.key),
                )
            }
        }

    /** 「全部」胶囊：清本行；计数 = 分区栏 all 桶（当前其他维选择下的总数，Web 同口径） */
    private fun allPillSpec(model: FourDimPillModel): PillSpec = PillSpec(
        text = model.totalForAllPill?.let { "全部 ($it)" } ?: "全部",
        selected = false,
        payload = null,
    )

    private fun FacetOption.labelWithCount(): String = "$name ($fileCount)"
}
