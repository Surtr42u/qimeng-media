# 维护与验证工具

工具只处理源码、文档或隔离合成数据，不访问 8420 真库。

## 文档门禁

仓库根执行 `python -X utf8 tools/check_docs.py`；自测执行 `python -m unittest discover -s tools -p 'test_*.py'`。无需安装 Python 运行时依赖。

检查本地内联 Markdown 链接/章节、ADR 唯一编号和索引、AGENTS 路径、近期 CHANGELOG 上限、外部 Actions 完整 SHA 与顶层只读权限基线；后两项是文本规则，不替代 YAML/actionlint 或 GitHub 执行。支持本仓现用语法，不解析所有 Markdown 扩展；不证明正文语义准确。

PR CI 额外运行 `python tools/check_docs.py --base <base-sha> --event-path <event-json>`：按代码目录提示相关专题；没有更新专题时要求 PR 正文提供“文档影响：无；原因：具体依据”。只更新 CHANGELOG 不通过相关专题检查，内部重构不强制伪造文档变化。目录映射集中在工具的 IMPACT_RULES，调整文档职责时同步它和协作规范。

本地不指定 --base 只做静态检查；base 模式比较已提交的 base..HEAD，不包含工作区未提交改动。CI 使用 pull_request 事件、只读权限，不执行 pull_request_target、不写评论或外部消息。项目语义与验证边界仍须按 AI_README_FIRST 复核。

## 其他可靠性门禁

`release_manifest.py` 校验本机服务端 `-version` 与当前 Git/发布版本身份，生成 schema/协议/SDK 锁及产物哈希清单，默认拒绝 dirty 正式交付；`--development` 仅记录工作区验证。`cold_backup.py create/verify/restore` 复用标准库复制/哈希，停写确认后创建副本并只恢复到不存在的目录。执行顺序、安全边界与命令见 [恢复与升级](../docs/RESTORE.md#ai-升级与回退执行清单)，均不自动部署或操作运行库。自测只使用合成临时目录。

`python tools/check_migrations.py` 对 HEAD 检查暂存/工作区的历史 SQL；`--base <sha>` 同时覆盖基线之后提交的改动。PR CI 用 base SHA，master push 用 before SHA，新增 migration 允许，既有 SQL 修改/删除/改名失败；缺失基线报错而非静默跳过。测试仅建立临时合成 Git 仓库。

Go `go test ./internal/architecture` 和 Web `npm test` 的 architecture 测试负责直接依赖边界，口径见 ARCHITECTURE §5.1。执行模型无可靠完整名称/变体时，交付记录如实标记未知，不沿用旧署名。维护改进的分批进度见 [实施清单](../docs/AI_MAINTENANCE_PLAN.md)。

security.yml 固定 govulncheck v1.9.0/cargo-audit 0.22.2；后者 MSRV 1.88，扫描 job 使用 Rust 1.90。npm audit high 起阻断，不用 force 或统一忽略压下失败。可本地按 workflow 同命令执行；网络失败不是扫描通过。恢复和真实跨卷命令见 [RESTORE](../docs/RESTORE.md)。

Go 安全扫描及各构建工作流的 Go 版本来自 server/go.mod；lint 固定版本源码在该工具链下执行。Dependabot 已按用户最新要求恢复每周升级候选与自动安全升级 PR；每个更新配置（生态 + 目录）最多 1 个常规待处理 PR，不自动合并。机器人或人工发起的依赖更新均需逐项审查并提供具体文档影响说明，不绕过门禁。

## glib 安全补丁验证

`python tools/check_glib_patch.py` 校验官方快照的全部原始文件（含两行已修复源码）及桌面 Cargo 的本地补丁来源，拒绝文件漂移和重新引入注册表 glib；自测与文档工具共用 unittest discover。第三方 README 的 rustdoc 符号链接不按本地路径解析，普通失效链接仍检查。

安全 CI 在 Linux/Rust 1.98.1 下执行上游 `variant_iter::tests` 的 release 优化测试：临时恢复经哈希核实的原始源码确认运行时失败，再恢复固定补丁并运行相同测试；网络/编译失败不算缺陷复现。快照许可、锁文件及整件退役规则见 [补丁说明](../desktop/vendor/README.md)。
