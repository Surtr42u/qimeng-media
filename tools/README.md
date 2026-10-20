# 维护与验证工具

工具只处理源码、文档或隔离合成数据，不访问 8420 真库。

## 文档门禁

仓库根执行 `python -X utf8 tools/check_docs.py`；自测执行 `python -m unittest discover -s tools -p 'test_*.py'`。无需安装 Python 运行时依赖。

检查本地内联 Markdown 链接/章节、ADR 唯一编号和索引、AGENTS 路径、近期 CHANGELOG 上限、外部 Actions 完整 SHA 与顶层只读权限基线；后两项是文本规则，不替代 YAML/actionlint 或 GitHub 执行。支持本仓现用语法，不解析所有 Markdown 扩展；不证明正文语义准确。

PR CI 额外运行 `python tools/check_docs.py --base <base-sha> --event-path <event-json>`：按代码目录提示相关专题；没有更新专题时要求 PR 正文提供“文档影响：无；原因：具体依据”。只更新 CHANGELOG 不通过相关专题检查，内部重构不强制伪造文档变化。目录映射集中在工具的 IMPACT_RULES，调整文档职责时同步它和协作规范。

本地不指定 --base 只做静态检查；base 模式比较已提交的 base..HEAD，不包含工作区未提交改动。CI 使用 pull_request 事件、只读权限，不执行 pull_request_target、不写评论或外部消息。项目语义与验证边界仍须按 AI_README_FIRST 复核。

## 其他可靠性门禁

security.yml 固定 govulncheck v1.9.0/cargo-audit 0.22.2；后者 MSRV 1.88，扫描 job 使用 Rust 1.90。npm audit high 起阻断，不用 force 或统一忽略压下失败。可本地按 workflow 同命令执行；网络失败不是扫描通过。恢复和真实跨卷命令见 [RESTORE](../docs/RESTORE.md)。
