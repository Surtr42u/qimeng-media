package media.qimeng.app.core.model

/**
 * 来源词表的「单独词」提取（DOMAIN_RULES §6：来源=获取渠道/平台名，建议唯一
 * 数据源=通用来源词表）。
 *
 * 词表条目有两种形态：单独词（「site-a」）与组合（「site-a  site-b」= 一次多渠道
 * 获取的组合记录，来自作者片段来源区的一行多词）。组合里的平台词不会以单独条目
 * 存在于词表（如 site-d/site-f/site-g 只出现在组合里），故提取口径 = **按空白拆词 +
 * 去重**：每个组合条目拆出的平台词全部纳入建议，单独条目原样保留，首现顺序不变
 * （2026-09-29 用户实测拍板：只过滤组合会漏词，必须拆词提取）。
 * 词表管理卡（维护场景）仍展示全量原始词表，不做拆分。
 * 纯函数（铁律 3/7）：无 IO 无状态，行为由 SourceWordsTest 锁定。
 */
fun List<String>.individualSourceWords(): List<String> =
    flatMap { it.split(Regex("\\s+")) }
        .filter { it.isNotBlank() }
        .distinct()
