# AI 工具平台最小可移植分发

本页说明如何从仓库生成、核验和接入最小 AI 工具分发包。目标是在**仓库外空目录**
可重复运行工具核心、recipes、规则与协议快照，而不是打包整份源码、服务端数据库或
真实媒体。安装步骤见分发包内的 `INSTALL.md`（由
[INSTALL_TEMPLATE.md](../tools/ai_maintenance/INSTALL_TEMPLATE.md) 渲染）。

## 1. 边界

- 分发物包含：`tools/ai_maintenance/**`、`tools/research_kit/**`（可选）、
  `api/openapi.yaml` 快照、根入口 `qai`/`qai.cmd`、`README.md`、`INSTALL.md` 与
  `source-manifest.json`。
- 不包含：服务端数据库、媒体文件、凭据、Go 业务实现、Web/Android/桌面源码；
  不新增任意 shell/Python/全盘文件执行工具。
- 工具包版本与 Go API 版本独立，通过 OpenAPI 快照和各工具自身声明的端点/方法/字段
  核对能力；不能因为“同一发行目录”假定业务协议一定兼容。
- 远程 HTTP/Streamable MCP 仍未实现，属于 S7 后续独立范围。当前接入形态是 CLI 与
  stdio MCP；普通 Go HTTP API 不是 MCP 端点。

## 2. 构建

构建脚本：[package.py](../tools/ai_maintenance/package.py)，只使用 Python 标准库。

```text
python -X utf8 tools/ai_maintenance/package.py build --out <EMPTY_DIR>
python -X utf8 tools/ai_maintenance/package.py build --out <EMPTY_DIR> --without-research-kit
python -X utf8 tools/ai_maintenance/package.py build --out <EMPTY_DIR> --without-docs
```

- `--out` 必须不存在或为空；拒绝覆盖非空目录。
- 先在同级临时目录生成 `qimeng-ai-toolkit-<platformVersion>/`，完成后再原子改名；
  失败只清理临时目录，不留下半成品。
- `--without-research-kit`：不复制 `tools/research_kit/`；`research.*` 本地工具仍会
  返回明确的不可用原因，不会伪装成功。
- `--without-docs`：不复制额外的 `docs/AI_PLATFORM_DISTRIBUTION.md`；包根
  `README.md` 与 `INSTALL.md` 始终生成。
- 排除生成物与本地配置：`__pycache__`、`.work`、`.test-temp`、`rulepack.json`、
  `*.pyc`、`*.local`、`.env*`、`*.token`/`*.key`/`*.pem` 等；源目录出现
  符号链接/junction 时拒绝打包。

## 3. 包布局

```text
qimeng-ai-toolkit-<version>/
  README.md
  INSTALL.md
  source-manifest.json
  qai
  qai.cmd
  api/openapi.yaml
  docs/AI_PLATFORM_DISTRIBUTION.md      # 未使用 --without-docs 且源文件存在时
  tools/ai_maintenance/
    cli.py  mcp_server.py  package.py  rules.json  versions.py  ...
    recipes/*.json
    INSTALL_TEMPLATE.md
  tools/research_kit/                   # 未使用 --without-research-kit 且源目录存在时
```

布局刻意镜像仓库相对路径：`rulepack.REPO_ROOT = parents[2]`、
`DEFAULT_OPENAPI_PATH = REPO_ROOT/api/openapi.yaml`、
`RECIPE_DIR = tools/ai_maintenance/recipes` 与 `RESEARCH_KIT_DIR = REPO_ROOT/tools/research_kit`
因此继续生效；工具包不依赖开发工作区、`QIMENG_*` 本机值或未声明文件。

## 4. 来源清单

`source-manifest.json` 字段：

| 字段 | 含义 |
| --- | --- |
| `schemaVersion` | 清单结构版本（当前 1） |
| `platformVersion` | 工具包发行版本（来自 `versions.py`） |
| `toolContractVersion` | 工具 ID/Schema/错误语义契约版本 |
| `rulepackVersion` | `rules.json` 规则版本 |
| `planVersion` | 计划结构版本 |
| `openapiSha256` | `api/openapi.yaml` 原始字节 SHA-256 |
| `generatedAt` | 构建时刻（UTC ISO-8601） |
| `gitRevision` | 可读时记录构建来源提交；否则 `unknown` |
| `files` | 按 POSIX 相对路径排序的 `{path,bytes,sha256}` 数组 |

约束：路径一律 POSIX 相对路径；不记录本机用户名、开发工作区绝对路径、token、真实媒体名
或数据库内容。`source-manifest.json` 不把自己列入 `files`。`gitRevision` 只用于来源
追踪，不替代当前提交门禁或云端构建结果。

## 5. 核验

```text
python -X utf8 tools/ai_maintenance/package.py verify --bundle <BUNDLE_DIR>
```

逐文件比较 `path`/`bytes`/`sha256`，报告：

- `missing`：清单声明但包内不存在；
- `extra`：包内存在但清单未声明（`source-manifest.json` 自身除外）；
- `modified`：长度或摘要不一致，或路径中出现符号链接/非普通文件。

`verify` 不跟随符号链接，不输出绝对路径集合。全部一致时退出码 0；存在差异时退出码 1。
在包内运行 CLI 时也建议设置 `PYTHONDONTWRITEBYTECODE=1` 或使用 `python -B`；否则
Python 生成的 `__pycache__` 属于清单外文件，会被严格报告。

重建清单（不覆盖已有文件）：

```text
python -X utf8 tools/ai_maintenance/package.py manifest --bundle <BUNDLE_DIR>
python -X utf8 tools/ai_maintenance/package.py manifest --bundle <BUNDLE_DIR> --out <NEW_MANIFEST>
```

`manifest` 默认只打印；`--out` 拒绝覆盖已有文件，也不会修改原清单或签名类文件。
重建时保留原清单的 `generatedAt` 与 `gitRevision`，便于比较文件差异。

## 6. 依赖诊断

```text
python -X utf8 tools/ai_maintenance/package.py doctor --bundle <BUNDLE_DIR> --json
python -X utf8 tools/ai_maintenance/package.py doctor --bundle <BUNDLE_DIR> --online --json
```

**基础依赖**（缺失时退出码 2）：

- Python 3.13+ 标准库；
- `QIMENG_API_BASE` 与 `QIMENG_API_TOKEN`（占位配置，只从环境变量读取）；
- 必需文件/清单完整性、OpenAPI 可解析、rules 与 recipes 可加载；
- 清单存在时逐文件核验，版本输出与清单一致。

**可选研究依赖**（缺失只 warning，退出码仍为 0）：

| 依赖 | 缺失时 |
| --- | --- |
| Node.js 24+ | Lens 批处理不可用；Yandex/SauceNAO/作者探针仍可运行 |
| ffmpeg | 抽帧、dHash、拼图、头像转 JPEG 不可用 |
| ffprobe | 媒体宽高/时长元数据不可用 |
| Edge/Chrome/Chromium | Lens 批处理不可用；不假定 ARM/NAS 有浏览器 |
| ADB | 设备推送/拉取不可用；必须显式 `QIMENG_ADB_SERIAL` |
| Lens sidecar | 工具端无法确认；未部署时 `/research/lens` 返回 503 |

`doctor` 的 `capabilities` 会分别给出 `lensBatch`、`mediaProbe`、`dhashCompare`、
`avatarFetch`、`yandexCbir`、`saucenaoBatch`、`authorProbe`、`research.lens.api` 与
`device.adb` 的准确状态，区分“未安装”“缺少可选二进制”“仅服务端未验证”。

`--online` 只对 `QIMENG_API_BASE` 调用 `GET /api/v1/healthz` 与 `GET /api/v1/readyz`，
不调用业务接口、不写数据、不打印 token 或响应正文；健康检查未通过时退出码 1。

Node/ffmpeg/ffprobe/浏览器等外部二进制不随包分发，也不写入 `files` 清单；`doctor`
只报告检测到的版本与可用性。部署方必须自行核对可执行文件来源与版本，工具包不会静默
下载或替换未知二进制。

## 7. 安装与接入

分发包内的 `INSTALL.md` 覆盖以下占位内容：

- Python 3.13 标准库与最小权限 AI 客户端 token 的配置；
- Node/ffmpeg/ffprobe/浏览器/ADB/sidecar 缺失时的能力状态；
- CLI、短入口 `qai`/`qai.cmd` 与 stdio MCP 的占位启动/停止命令；
- `QIMENG_AI_STATE_DIR`、读写根、媒体根、storage 与研究工作根；
- 日志位置（stderr 或宿主日志，工具核心不新增日志文件）；
- 凭据轮换/撤销；
- 升级/回退：保留 DataDir、人工配置和任务状态，不自动清空；旧计划不承诺无条件兼容，
  需要时用当前工具包重新 `prepare`；
- 安全边界：不打包数据库/真实媒体/凭据，8420 真库禁止测试写入，远程 HTTP MCP 未实现。

所有示例只使用 `<BUNDLE_ROOT>`、`<API_ORIGIN>`、`<AI_CLIENT_TOKEN>` 等占位符。

## 8. 验证与交付

回归测试入口：`tools/test_ai_distribution.py`（仓库内测试，不随分发物复制）。测试只使用
临时目录与合成文件，覆盖：

- 含/不含 research_kit（及 docs）的构建布局；
- `verify` 的通过、modified、missing、extra 与符号链接行为；
- 清单字段、稳定排序与无工作区绝对路径；
- 从 bundle 外临时目录、清空 `PYTHONPATH` 后运行 bundle 内 CLI `version`；
- `doctor` 的可选依赖 warning、缺必需文件退出码 2、清单不一致基础失败；
- 拒绝覆盖非空输出目录与构建失败清理临时目录；
- `--online` 不测试真实服务端。

云端 AI/文档工作流会执行 `tools/test_ai*.py` 与文档检查；本页不把“测试文件存在”
写成已经通过。实际发布仍需按任务书第 5 节完成对应场景，并记录工具来源、Go API 来源与
协议摘要，不能只凭同一 revision 推断三端一致。

## 9. 已知限制

- 不提供远程 HTTP MCP、OAuth、对外监听或 NAS 硬件部署验收（S7/后续范围）。
- 不包含 research_kit 所需的浏览器、Node、ffmpeg/ffprobe 二进制，也不自动下载。
- 不保证旧 recipe 计划、旧结果引用或旧状态目录无条件兼容新工具包。
- 工具包不替代服务端 scope、路径授权、确认门禁或回读；工具可见不等于获权。
