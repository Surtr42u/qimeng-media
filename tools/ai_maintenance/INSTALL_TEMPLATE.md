# 绮梦影库 AI 工具包 {{BUNDLE_NAME}} 安装与接入

> 本文件是分发模板，构建时替换版本/摘要占位符并在包根生成 `INSTALL.md`。
> 包内只含工具核心、规则、recipes、OpenAPI 快照与可选研究脚本；不含服务端数据库、
> 真实媒体、凭据或 Go 业务实现。所有路径、地址与令牌均使用占位符，按实际环境替换。

## 1. 包内文件与版本

| 内容 | 位置 | 说明 |
| --- | --- | --- |
| 工具入口 | `tools/ai_maintenance/cli.py` | CLI 与工具注册表统一入口 |
| stdio MCP | `tools/ai_maintenance/mcp_server.py` | MCP stdio 适配器；与 CLI 共用内核 |
| 工具核心 | `tools/ai_maintenance/*.py` | 查询、计划、受控写入、本地文件原语 |
| 领域规则 | `tools/ai_maintenance/rules.json` | 唯一手写规则源 |
| recipes | `tools/ai_maintenance/recipes/*.json` | 可执行编排配方 |
| 协议快照 | `api/openapi.yaml` | 与服务端能力核对用，不启动服务端 |
| 本地研究脚本 | `tools/research_kit/` | 可选；缺失时 `research.*` 返回明确不可用原因 |
| 短入口 | `qai` / `qai.cmd` | 只转发参数，不读取 token、不启动服务 |
| 来源清单 | `{{MANIFEST_NAME}}` | 版本、OpenAPI 指纹与逐文件长度/SHA-256 |

当前版本：工具包 `{{PLATFORM_VERSION}}`，工具契约 `{{TOOL_CONTRACT_VERSION}}`，
规则包 `{{RULEPACK_VERSION}}`，计划结构 `{{PLAN_VERSION}}`，OpenAPI SHA-256
`{{OPENAPI_SHA256}}`。构建时间 `{{GENERATED_AT}}`。

research_kit：{{RESEARCH_KIT_STATUS}}。额外分发文档：{{DOCS_STATUS}}。

## 2. 基础依赖

以下条件不满足时，网络工具与受控写入无法正常使用；`doctor` 缺基础依赖返回退出码 2。

| 依赖 | 要求 | 检查/配置 |
| --- | --- | --- |
| Python | 3.13 或更高，只用标准库 | `python -X utf8 tools/ai_maintenance/cli.py version` |
| 服务端地址 | `QIMENG_API_BASE`，形如 `http://127.0.0.1:<PORT>` 或 HTTPS origin | 不能带子路径、查询、片段或用户信息；HTTP 仅限回环/私有地址 |
| 凭据 | `QIMENG_API_TOKEN`，建议使用最小权限 AI 客户端 token | 只从环境变量注入，不写入命令行参数、配置样例或日志 |
| 实例可达 | 显式 `--online` 时检查 `/api/v1/healthz` 与 `/api/v1/readyz` | 只读 GET；不打印 token，不写业务数据 |

最小权限 AI 客户端 token 由服务端管理员在本机签发（占位示例，替换 `<...>`）：

```text
qimeng ai-client create -config <SERVER_CONFIG> -label ai-executor -scopes read -device-ids <DEVICE_ID>
```

把 create/rotate 只输出一次的 token 注入执行端环境：

```text
QIMENG_API_BASE=<API_ORIGIN>
QIMENG_API_TOKEN=<AI_CLIENT_TOKEN>
```

不要使用管理员 Bearer 作为长期模型执行端凭据；写入权限按实际任务另行授予，并受服务端
scope 再次约束。缺失基础依赖时命令以退出码 2 结束，错误只列变量名，不回显值。

## 3. 可选研究依赖

缺少可选依赖只产生 warning，`doctor` 仍以 0 退出；但相应能力会明确标记不可用，
不会返回空结果冒充识别成功。

| 依赖 | 用途 | 缺失时的能力状态 |
| --- | --- | --- |
| Node.js 24+ | Lens 批处理（`lens_batch.mjs`） | Lens 本地批处理不可用；Yandex、SauceNAO、作者探针仍可运行 |
| ffmpeg | 抽帧、dHash64、拼图、头像转 JPEG | 抽帧/感知哈希/拼图不可用；纯下载与 Python 查询不受影响 |
| ffprobe | 媒体宽高、时长等元数据 | `media_probe` 元数据不可用；ffmpeg 存在时抽帧仍可能可用 |
| Edge/Chrome/Chromium | Lens 批处理浏览器 | Lens 批处理不可用；不要假定 ARM/NAS 一定有浏览器 |
| ADB | 设备推送/拉取（仅设备场景） | 设备操作不可用；查询、整理、研究不受影响；必须显式 `QIMENG_ADB_SERIAL` |
| Lens sidecar | 服务端 `/research/lens` 代理 | 工具端无法确认；未部署时服务端返回 503，不能判定作者/作品不存在 |

recipe 的 `lens` 能力核对还读取 `QIMENG_AI_LENS_ENABLED=1`；它只表示执行端声明可用，
不代表 sidecar 已部署或可访问。sidecar 状态以服务端 `/research/context` 与实际调用为准。

可选覆盖变量（只写名称，不填真实路径）：`QIMENG_RESEARCH_KIT_NODE`、`QIMENG_AI_NODE`、
`QIMENG_AI_FFMPEG`、`QIMENG_AI_FFPROBE`、`QIMENG_FFMPEG`、`QIMENG_FFPROBE`、
`QIMENG_RESEARCH_KIT_FFMPEG`、`QIMENG_RESEARCH_KIT_FFPROBE`、
`QIMENG_RESEARCH_KIT_EDGE`、`QIMENG_RESEARCH_SIDECAR_EDGE`、`QIMENG_ADB`。
工具包不自动下载或安装未知二进制；外部依赖的来源与版本由部署方自行核对。
research_kit 的帧/缓存/证据请通过 `QIMENG_RESEARCH_KIT_CACHE` 或
`QIMENG_AI_RESEARCH_DIR` 指向可写目录，不要写入工具包目录。

## 4. 启动 CLI 与 MCP

在包根（包含本 `INSTALL.md` 的目录）执行；工具会自动按自身位置解析 `tools/` 与
`api/openapi.yaml`，不依赖开发工作区。

```text
cd <BUNDLE_ROOT>
python -X utf8 tools/ai_maintenance/cli.py version
python -X utf8 tools/ai_maintenance/cli.py recipes list
python -X utf8 tools/ai_maintenance/cli.py task <RECIPE_ID>
```

Windows 也可用 `qai.cmd <参数>`；POSIX 可用 `./qai <参数>`。

MCP stdio 宿主配置占位示例（不要写入真实 token）：

```json
{
  "mcpServers": {
    "qimeng-ai": {
      "command": "python",
      "args": ["-X", "utf8", "<BUNDLE_ROOT>/tools/ai_maintenance/mcp_server.py"],
      "env": {
        "QIMENG_API_BASE": "<API_ORIGIN>",
        "QIMENG_API_TOKEN": "<AI_CLIENT_TOKEN>",
        "QIMENG_AI_READONLY": "1",
        "QIMENG_AI_GRANTED_PERMISSIONS": "read",
        "QIMENG_AI_STATE_DIR": "<PRIVATE_STATE_DIR>",
        "PYTHONDONTWRITEBYTECODE": "1"
      }
    }
  }
}
```

当前只支持 CLI 与 stdio MCP。远程 HTTP/Streamable MCP 尚未实现，属于后续独立范围；
不得把普通 Go HTTP API 当作远程 MCP 端点，也不要在本次包内新增对外监听面。
停止方式：结束对应 CLI 命令或宿主启动的 MCP 子进程即可；工具核心本身不驻留、
不监听端口，也不自动启动服务端。若希望工具包目录保持与来源清单完全一致，请设置
`PYTHONDONTWRITEBYTECODE=1` 或使用 `python -B`；否则 Python 可能在包内生成
`__pycache__`，`verify` 会把它报告为清单外文件。

## 5. 状态、读写根与 storage

| 配置 | 占位示例 | 说明 |
| --- | --- | --- |
| `QIMENG_AI_STATE_DIR` | `<PRIVATE_STATE_DIR>` | 私有执行端状态根；保存结果续读、任务预算等，不写入工具包目录 |
| `QIMENG_AI_READ_ROOTS` | `<READ_ROOT_1>,<READ_ROOT_2>` | 绝对路径，逗号/分号分隔；未配置目录默认拒绝读取 |
| `QIMENG_AI_WRITE_ROOTS` | `<WRITE_ROOT>` | 绝对路径；写操作与读根分开授权 |
| `QIMENG_AI_MEDIA_ROOTS` | `<MEDIA_ROOT>` | `media.organize.prepare` scan 模式的只读扫描根 |
| `QIMENG_AI_STORAGES` | `<STORAGE_ID>=<STORAGE_ROOT>` | 逻辑存储声明；`STORAGE_ID` 使用小写字母/数字/下划线/连字符 |
| `QIMENG_AI_RESEARCH_DIR` | `<PRIVATE_RESEARCH_DIR>` | research 与派生文件的忽略工作根；必须为绝对路径 |
| `QIMENG_AI_GRANTED_PERMISSIONS` | `read` 或最小写入权限 | 未配置时仅授予 `read`；写入必须显式授权 |

工作目录、状态目录与授权根都不要放在工具包源码目录内。升级/回退时应保留
`<PRIVATE_STATE_DIR>`、服务端 DataDir、配置与任务状态；工具包升级不自动清空它们。

## 6. 日志位置

工具核心不新增日志文件：CLI/MCP 的诊断与业务错误写到 stderr 或由宿主捕获；
research_kit 脚本按自身参数输出，sidecar 如启用可选日志则写到其配置位置。
不要把 token、真实媒体名、完整本地路径或上游响应正文写入公开日志/报告。

## 7. 凭据轮换与撤销

凭据只在服务端管理，不要写入本包、脚本、任务卡或模型上下文：

```text
qimeng ai-client list -config <SERVER_CONFIG>
qimeng ai-client rotate -config <SERVER_CONFIG> -id <CLIENT_ID>
qimeng ai-client revoke -config <SERVER_CONFIG> -id <CLIENT_ID>
```

rotate/revoke 后旧 token 在服务端缓存 TTL 内失效；执行端更新 `QIMENG_API_TOKEN`
并重启 CLI/MCP 进程。疑似泄露时立即撤销，不要只依赖工具层隐藏。

## 8. 升级与回退

1. 停止使用当前工具包的 CLI/MCP 进程，并保留 `<PRIVATE_STATE_DIR>`、服务端 DataDir、
   配置与未完成任务状态。
2. 用新的发行目录解压/生成工具包，在同一包根执行 `verify` 与 `doctor`。
3. 确认版本、OpenAPI 指纹与依赖状态后切换宿主配置指向新包；旧包暂时保留用于回退。
4. 升级不自动重设数据目录、不覆盖人工配置、不强制清空运行记录。
5. 工具契约或规则变化后，旧计划可能不再兼容；必须用当前工具包重新 `prepare`，
   不要拼接旧计划继续写入。回退时同样保留状态目录，不要用旧版本打开已变化的结构。

## 9. 安全边界

- 本包不包含数据库、真实媒体、凭据、内网拓扑或含用户名的绝对路径；来源清单只记录
  POSIX 相对路径、长度与 SHA-256。
- **8420 真库禁止测试写入**；验证只用隔离实例、临时目录与合成媒体。
- 删除媒体先移回收站；永久删除仅走独立管理操作。写工具仍须计划、人工确认、
  版本/指纹校验与回读，工具包不提供任意 shell/Python/全盘文件执行通道。
- 路径必须规范化并限制在授权根内；工具端不默认信任模型传入的路径或令牌。
- 分发物完整性用 `verify` 核对；不要跳过清单校验运行来源不明或被修改的包。
- 远程 HTTP MCP、OAuth 平台、对外监听端口和 NAS 硬件验收均不在本包范围内。

## 10. 自检

```text
cd <BUNDLE_ROOT>
python -X utf8 tools/ai_maintenance/package.py verify --bundle .
python -X utf8 tools/ai_maintenance/package.py doctor --bundle . --json
# 可选：只调用 healthz/readyz；需要基础 API 配置
python -X utf8 tools/ai_maintenance/package.py doctor --bundle . --online --json
```

`doctor` 退出码：0 = 基础检查通过（可选依赖缺失仅 warning）；2 = 基础依赖或完整性
失败；显式 `--online` 且健康检查未通过时为 1。报告不输出 token 或绝对路径集合。
