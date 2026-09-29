package media.qimeng.app.core.model

/**
 * 来源词表的「单独词」过滤（DOMAIN_RULES §6：来源=获取渠道/平台名，建议唯一
 * 数据源=通用来源词表；组合条目=一次多渠道获取的组合记录，如「kemono  小红车」，
 * 来自作者片段来源区的一行多词）。
 *
 * 选择器建议只出单独词：组合形态可经自由输入获得，不作为快捷 chip 占位；
 * 词表管理卡（维护场景）仍展示全量词表，不过滤。
 * 纯函数（铁律 3/7）：无 IO 无状态，行为由 SourceWordsTest 锁定。
 */
fun List<String>.individualSourceWords(): List<String> =
    filter { it.isNotBlank() && it.none(Char::isWhitespace) }
