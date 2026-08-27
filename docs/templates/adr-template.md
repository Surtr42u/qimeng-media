# ADR-NNNN：<决策标题>

> Nygard 五段式模板（Title / Context / Decision / Status / Consequences）。
> 既有 ADR（0001-0008）沿用旧格式（背景/决策/理由/后果），**新 ADR 一律按本模板**。

## 背景（Context）

<遇到什么问题/需求？什么约束下必须做决定？有哪些备选方案？>

## 决策（Decision）

<决定了什么——具体到技术、规则、配置，能被执行的程度；对比过哪些方案、为什么选这个。>
<若刻意"不做什么/不引入什么"也是决策的一部分，明确写出来。>

## 状态（Status）

Accepted | Superseded | Deprecated

- 新 ADR 一律 Accepted（已接受即成为行为约束）。
- 决策被推翻时：**保留旧文不改写历史**，把状态改为 `Superseded by ADR-XXXX`（并在新 ADR 的 Context 里说明推翻理由）；Deprecated 用于"不再适用但无替代"的情况。
- 修订不推翻原文：追加 `修订记录` 小节记日期与改动点，历史可追溯。

## 后果（Consequences）

<积极后果；代价与风险；对哪些模块/文件/文档有联动影响（写清路径，供 INDEX 反查）。>

---

## 中文填写指引

- 每篇 500 字内说清，宁可具体不可空泛；"为什么"比"做了什么"更重要。
- 后果段必须写清**受影响文件/文档路径**——`docs/adr/INDEX.md` 依赖它做反向定位。
- 新增 ADR 后同步更新 `docs/adr/INDEX.md` 索引行，并在对应文档（ARCHITECTURE/DOMAIN_RULES/SECURITY 等）引用编号。
- 编号顺延现有最大值，禁止跳号；文件名 `NNNN-简短中文标题.md`。
