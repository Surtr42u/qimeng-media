/**
 * 来源词表展示过滤（铁律 7：展示规则落 lib 纯函数，组件只消费）。
 *
 * 通用来源词表（GET /authors/source-vocabulary，DOMAIN_RULES §6）里，含空白分隔
 * 多词的条目是「组合」形态（如「kemono  小红车」= 一次多渠道获取的组合记录，来自
 * 作者片段来源区的一行多词）。选择器建议只出「单独」词——组合可经自由输入获得，
 * 不作为快捷 chip 占位；词表管理卡（维护场景）仍展示全量词表，不过滤。
 * 与 Android 端 core:model individualSourceWords 同口径（行为对齐，各自实现）。
 */

/** 过滤组合与空白条目，仅保留不含任何空白字符的单独词，原序保留 */
export function individualSourceWords(sources: readonly string[]): string[] {
  return sources.filter((s) => s !== '' && !/\s/.test(s))
}
