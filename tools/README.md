# 维护与验证工具

测试/门禁只处理源码、文档或隔离合成数据，不访问 8420 真库。下述历史对账工具是用户授权后的只读运维例外，不通过 API 写入数据。

## 历史数据只读对账

Python 3.13 标准库工具 `audit_library.py` 检查 SQLite quick_check/外键、全部登记资产的路径安全/普通文件/大小、事件便携身份和事件→日统计的一致性，并计数上传暂存文件。通过 `mode=ro`、`query_only=ON` 和 deferred 读事务读取包含已提交 WAL 的同一快照，在文件属性检查前关闭数据库连接；不修复数据、不重扫入库、不启动界面，也不读取或解码媒体内容。请在用户上传/移动完成后执行；在线文件属性与数据库不是跨介质原子快照，变化时需等待操作完成后复核。统计日界使用执行机本地时区，必须与服务端一致。

```powershell
python -X utf8 tools/audit_library.py --db <数据目录>/qimeng.db
python -X utf8 tools/audit_library.py --db <数据目录>/qimeng.db --backup <备份目录>/qimeng_backup.json
```

可选备份仅支持便携 v2，逐条核对媒体唯一映射/大小/类型、事件身份与原字段、作者/标签及关联、收藏/关注、时间轴、最后点赞日、TXT 全文和显式携带的推荐偏好。目标增量事件允许存在并单独计数；可变数据差异仅提示复核，不自动判定丢失。v2 的旧聚合兼容段不重复回放/比较；不声称逐字段审计全部溯源、设备配置或点赞历史（旧段只保留最后一天）。无备份明确标记未比对，旧 v1/非法或不可读输入以 incomplete 退出，不能冒充通过。

输出 JSON 只有计数和问题类别/序号，不含媒体名、作者名、路径或事件正文；重定向报告须放被忽略的 `build/*.local/`，禁止提交个人备份/报告。退出码 0=检查范围内一致，1=发现差异，2=输入/数据库不可读或不支持。上传暂存非空可能仍在传输，单独报告而非直接判损坏。快速属性校验不检测同大小内容修改、不证明手机队列已排空，也不覆盖媒体目录中尚未登记的文件。需要原件字节一致性时另做用户授权的源/目标哈希对比。

`test_audit_library.py` 仅在云端用临时合成库/文件验证不写库、WAL 可见、统计/外键/缺失关联/身份冲突检测和路径拒绝；文档 workflow 在 Linux 和 Windows 执行相关测试，随后执行文档门禁。真实数据只读运行是运维对账，不代替合成测试。

## 隐私与密钥门禁

`check_privacy.py` 用标准库补充私人文件路径/个人邮箱/设备标识/实际库汇总和 Git 作者元数据检查；密钥规则复用 [Gitleaks](https://github.com/gitleaks/gitleaks) **8.30.1** 默认规则，`.gitleaks.toml` 只对 auth 测试的一条明确合成 token 作精确放行，不豁免测试目录。扫描器云端下载经过固定 SHA-256 核对，不引入应用运行时依赖。

AI 负责本机安装预编译扫描器，配置 `git config privacy.gitleaksPath <扫描器路径>`（或 `QIMENG_GITLEAKS`），再设置 `git config core.hooksPath .githooks`。本机额外标识放忽略的 `.privacy-policy.local.json` 的 `privateIdentifiers` 字符串数组，只在本机读取，不写规则源码/测试/文档。重新检出后须重新启用 Git 本地 hooks 配置；CI 同时兜底，但云端检查发生在推送之后，不能代替本地拦截。

Windows 的 Git hook 子进程若不能正确定位 Python 标准库，AI 在 Git 本地配置设置 `privacy.pythonHome` 为已安装 Python 根目录；hook 只在自身子进程导出 `PYTHONHOME`，不修改系统环境。该个人路径不进入被跟踪文件。

- `--mode staged --gitleaks`：提交前读取 index 中将被提交的真实内容，防止工作区改回安全文本掩盖 staged 泄露；即使强制加入忽略文件，也按路径/载荷拒绝。
- `--mode worktree`：核对 tracked + 未忽略的新文件和当前 Git 身份，只读，不遍历被忽略的数据目录；媒体按路径清单处理，不打开/解码。
- `--mode history --gitleaks`：推送前核对全部本地可达文本历史和作者/提交者邮箱；Gitleaks 输出全量遮罩，程序不转发匹配原文，临时报告只在本机临时目录并自动清理。

退出码 0=所检规则通过，1=拦截，2=未完成；缺少固定版本扫描器或读取失败不能通过。`.githooks` 是可绕过的本地预防，不能保证所有可能的敏感文本都被识别；用户原件和运行配置继续依赖不入库纪律。`test_check_privacy.py` 只在云端用合成 Git 仓库验证强制加入拦截、staged/工作区差异与历史作者信息，不运行本机回归。

## 文档门禁

仓库根执行 `python -X utf8 tools/check_docs.py`；自测执行 `python -m unittest discover -s tools -p 'test_*.py'`。无需安装 Python 运行时依赖。

检查本地内联 Markdown 链接/章节、ADR 唯一编号和索引、AGENTS 路径、近期 CHANGELOG 上限、外部 Actions 完整 SHA 与顶层只读权限基线；后两项是文本规则，不替代 YAML/actionlint 或 GitHub 执行。支持本仓现用语法，不解析所有 Markdown 扩展；不证明正文语义准确。

PR CI 额外运行 `python tools/check_docs.py --base <base-sha> --event-path <event-json>`：按代码目录提示相关专题；没有更新专题时要求 PR 正文提供“文档影响：无；原因：具体依据”。只更新 CHANGELOG 不通过相关专题检查，内部重构不强制伪造文档变化。目录映射集中在工具的 IMPACT_RULES，调整文档职责时同步它和协作规范。

本地不指定 --base 只做静态检查；base 模式比较已提交的 base..HEAD，不包含工作区未提交改动。CI 使用 pull_request 事件、只读权限，不执行 pull_request_target、不写评论或外部消息。项目语义与验证边界仍须按 AI_README_FIRST 复核。

## 其他可靠性门禁

`test_windows_launcher.ps1` 只允许在 GitHub Actions 的 Windows runner 执行：把真实无头启动脚本复制到临时目录，编译无数据库的合成 HTTP 程序，检查冷启动就绪且控制台保持、重复调用显示 RUNNING 并只保留一个后端。运行前拒绝已占用端口，结束时仅清理本轮合成程序与启动器进程树；CI 的 Windows 文件操作 job 同批执行。不在开发机运行，不访问现用数据，也不把合成回归当本机 `.lnk` 双击验收。

`release_manifest.py` 校验本机服务端 `-version` 与当前 Git/发布版本身份，生成 schema/协议/SDK 锁及产物哈希清单，默认拒绝 dirty 正式交付；`--development` 仅记录工作区验证。`cold_backup.py create/verify/restore` 复用标准库复制/哈希，停写确认后创建副本并只恢复到不存在的目录。执行顺序、安全边界与命令见 [恢复与升级](../docs/RESTORE.md#ai-升级与回退执行清单)，均不自动部署或操作运行库。自测只使用合成临时目录。

`python tools/check_migrations.py` 对 HEAD 检查暂存/工作区的历史 SQL；`--base <sha>` 同时覆盖基线之后提交的改动。PR CI 用 base SHA，master push 用 before SHA，新增 migration 允许，既有 SQL 修改/删除/改名失败；缺失基线报错而非静默跳过。测试仅建立临时合成 Git 仓库。

Go `go test ./internal/architecture` 和 Web `npm test` 的 architecture 测试负责直接依赖边界，口径见 ARCHITECTURE §5.1。执行模型无可靠完整名称/变体时，交付记录如实标记未知，不沿用旧署名。当前维护状态见 [HANDOVER](../docs/HANDOVER.md)。

security.yml 固定 govulncheck v1.9.0/cargo-audit 0.22.2；后者 MSRV 1.88，扫描 job 使用 Rust 1.90。npm audit high 起阻断，不用 force 或统一忽略压下失败。可本地按 workflow 同命令执行；网络失败不是扫描通过。恢复和真实跨卷命令见 [RESTORE](../docs/RESTORE.md)。

Go 安全扫描及各构建工作流的 Go 版本来自 server/go.mod；lint 固定版本源码在该工具链下执行。Dependabot 已按用户最新要求恢复每周升级候选与自动安全升级 PR；每个更新配置（生态 + 目录）最多 1 个常规待处理 PR，不自动合并。机器人或人工发起的依赖更新均需逐项审查并提供具体文档影响说明，不绕过门禁。

## glib 安全补丁验证

`python tools/check_glib_patch.py` 校验官方快照的全部原始文件（含两行已修复源码）及桌面 Cargo 的本地补丁来源，拒绝文件漂移和重新引入注册表 glib；自测与文档工具共用 unittest discover。第三方 README 的 rustdoc 符号链接不按本地路径解析，普通失效链接仍检查。

安全 CI 在 Linux/Rust 1.98.1 下执行上游 `variant_iter::tests` 的 release 优化测试：临时恢复经哈希核实的原始源码确认运行时失败，再恢复固定补丁并运行相同测试；网络/编译失败不算缺陷复现。快照许可、锁文件及整件退役规则见 [补丁说明](../desktop/vendor/README.md)。
