/**
 * 来源词表展示提取（铁律 7：展示规则落 lib 纯函数，组件只消费）。
 *
 * 通用来源词表（GET /authors/source-vocabulary，DOMAIN_RULES §6）条目有两种形态：
 * 单独词（「site-a」）与组合（「site-a  site-b」= 一次多渠道获取的组合记录，来自
 * 作者片段来源区的一行多词）。组合里的平台词不会以单独条目存在于词表（site-d/
 * site-f/site-g 只出现在组合里），故建议提取口径 = **按空白拆词 + 去重**：组合拆出的
 * 平台词全部纳入建议，首现顺序不变（2026-09-29 用户实测拍板：只过滤组合会漏词）。
 * 快捷 chip 只出单独词——组合可经自由输入获得，不占建议位；词表管理卡（维护
 * 场景）仍展示全量原始词表。与 Android 端 core:model individualSourceWords 同口径。
 */

/** 每个条目按空白拆词，去空去重，首现顺序保留 */
export function individualSourceWords(sources: readonly string[]): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const entry of sources) {
    for (const word of entry.split(/\s+/)) {
      if (word === '' || seen.has(word)) continue
      seen.add(word)
      out.push(word)
    }
  }
  return out
}
