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
        MediaKind.IMAGE -> MediaTypeKeys.IMAGE
        MediaKind.ANIMATED_IMAGE -> MediaTypeKeys.ANIMATED_IMAGE
        MediaKind.VIDEO -> MediaTypeKeys.VIDEO
    }

    /**
     * MediaKind → 旧版中文类型名（与服务端 facetMediaTypeLabels 同源逐字：
     * 图片/动图/视频；折叠芯片显示当前类型名用，不依赖候选是否已加载）。
     */
    fun mediaKindLabel(kind: MediaKind): String = when (kind) {
        MediaKind.IMAGE -> "图片"
        MediaKind.ANIMATED_IMAGE -> "动图"
        MediaKind.VIDEO -> "视频"
    }

    /**
     * 维度芯片行（文字带数量；分区/类型行减去「全部」桶）。
     * 类型维折叠态特例（M4-2A-B2 拍板，旧版 GUIDE_UI §全部页「折叠时显示当前类型名」）：
     * activeDim=类型 且容器折叠时显示「当前类型名 ▼」（无选中=「全部 ▼」），
     * 其余形态一律「{维度} (N)」计数式。
     */
    fun dimChips(model: FourDimPillModel, dims: List<AlbumDim> = AlbumDim.entries): List<DimChipSpec> =
        dims.map { dim ->
            val count = when (dim) {
                AlbumDim.PARTITION -> (model.partitionOptions.size - 1).coerceAtLeast(0)
                AlbumDim.AUTHOR -> model.authorOptions.size
                AlbumDim.CHARACTER -> model.characterOptions.size
                AlbumDim.TYPE -> (model.typeOptions.size - 1).coerceAtLeast(0)
            }
            val text = if (dim == AlbumDim.TYPE && model.activeDim == AlbumDim.TYPE && !model.filter.expanded) {
                model.filter.mediaType?.let { "${mediaKindLabel(it)} ▼" } ?: "全部 ▼"
            } else {
                "${dim.label} ($count)"
            }
            DimChipSpec(
                dim = dim,
                text = text,
                selected = model.activeDim == dim,
            )
        }

    /**
     * 当前维度药丸行（「其他」置底已由调用方在候选上先做 withOtherBucketLast；
     * 作者/角色行服务端已按 fileCount 降序——openapi 承诺）。类型行例外：服务端
     * 恒固定枚举序（all/image/animated_image/video），旧版按计数降序展示，
     * 这里客户端重排（「全部」桶恒首位），单测锁定。
     * 零计数药丸照常显示——可见性只由数据行决定，不做计数过滤
     * （旧版实录 album_tab.txt 等场景零计数药丸「角色 (0)」在列；
     * GUIDE_UI §相册页类型药丸列固定四项不随计数缺省）；「全部」胶囊（清行动作）
     * 恒保留——否则选中后无处可清。
     */
    fun pillsFor(model: FourDimPillModel, dim: AlbumDim = model.activeDim): List<PillSpec> =
        when (dim) {
            AlbumDim.PARTITION -> model.partitionOptions
                .map { option ->
                    PillSpec(
                        text = option.labelWithCount(),
                        selected = option.key == zoneToKey(model.filter.partition),
                        payload = zoneFromKey(option.key),
                    )
                }
            AlbumDim.AUTHOR -> listOf(allPillSpec(model, model.filter.authors.isEmpty())) + model.authorOptions
                .map { option ->
                    PillSpec(
                        text = option.labelWithCount(),
                        selected = AlbumFilter.isAuthorActive(model.filter, option),
                        payload = option,
                    )
                }
            AlbumDim.CHARACTER -> listOf(allPillSpec(model, model.filter.characters.isEmpty())) + model.characterOptions
                .map { option ->
                    PillSpec(
                        text = option.labelWithCount(),
                        selected = AlbumFilter.isCharacterActive(model.filter, option),
                        payload = option,
                    )
                }
            AlbumDim.TYPE -> model.typeOptions
                .partition { it.key == KEY_ALL }
                .let { (all, rest) -> all + rest.sortedByDescending { it.fileCount } }
                .map { option ->
                    PillSpec(
                        text = option.labelWithCount(),
                        selected = option.key == mediaKindToKey(model.filter.mediaType),
                        payload = mediaKindFromKey(option.key),
                    )
                }
        }

    /**
     * 「全部」胶囊：清本行；计数 = 分区栏 all 桶（当前其他维选择下的总数，Web 同口径）。
     * 选中态（任务W W6 #2 对标旧版，原 C8 报告 §3.3 S2/S3 差异项拍板翻案）：
     * 旧实录该丸在行内无具体候选选中时深底白字实底高亮（分区/类型行的「全部」桶
     * 本就按 key 判选中，作者/角色行此前硬编码恒不选中——即 C8 记档的交互口径差），
     * 现按行选中集是否为空对齐旧版；点击=清行、payload、查询投影零变化（纯展示位）。
     */
    private fun allPillSpec(model: FourDimPillModel, rowAllActive: Boolean): PillSpec = PillSpec(
        text = model.totalForAllPill?.let { "全部 ($it)" } ?: "全部",
        selected = rowAllActive,
        payload = null,
    )

    private fun FacetOption.labelWithCount(): String = "$name ($fileCount)"
}
