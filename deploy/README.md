# deploy - NAS 部署

服务端上 NAS 的标准路径（M5 部署三件套）：**Dockerfile（镜像）+ docker-compose.yml（生产样例）+ 本文档**。
2026-09-22 已在 fnOS 1.2 虚拟机（VirtualBox，amd64）全流程实测通过，实测记录见文末。

## PC 日常更新（云端出包）

当前 PC 的 Windows 服务端与 Web 使用 [build-pc.yml](../.github/workflows/build-pc.yml)，手动触发或推送 `codex/build-pc/**` 专用分支即可出 `pc-windows-amd64` artifact（7 天保留），包含 `qimeng-server.exe`、`web-dist/` 和 `release-manifest.json`，不包含用户数据、配置、FFmpeg 或桌面连接壳。此通道只出包，完整 CI/安全/文档门禁仍须按同一来源提交核对。

AI 下载后核对 GitHub artifact 摘要、清单逐文件哈希和服务端 `-version` 的版本/提交/非 dirty 身份，按 [升级清单](../docs/RESTORE.md#ai-升级与回退执行清单)判断兼容性与副本需求。正常停服后保留旧程序和 Web，整体替换到 `server/qimeng-server.exe` 与 `web/dist`，保持原配置、DataDir 和媒体根，再以原日常环境启动并核对监听进程、健康/就绪、Web 实际资源。根目录三份启动 bat 只运行已安装程序，不再触发本地编译；这些操作由 AI 完成，下载成功不等于已经部署。 当前 PC 与手机安装包的来源、实际安装字节和现场健康验证统一见 [HANDOVER](../docs/HANDOVER.md#2-当前源码运行包与验证边界)，后补文档提交不重标产物来源。

无头快捷方式连接的是这套共享启动脚本，不保留独立旧程序；它保留普通黑色后端控制台和任务栏入口，不打开浏览器或桌面端，也不另建图形状态窗。已有健康后端时只读显示状态，不再启动重复实例；控制台生命周期与输出约定见 [服务端入口](../server/README.md#构建身份)。

## Android 候选包与发布资格

[build-apk](../.github/workflows/build-apk.yml) 先构建并检查内嵌三件套，再执行 Debug 单测、core:model 测试和 Release Lint；任一失败直接阻断安装包 artifact 上传，移除旧 continue-on-error。签名、版本号、应用 ID 和三件套锁不因此改变。

artifact 仅为候选包。AI 部署前必须核对产物来源 SHA 对应的 ci/security/docs 全部必需检查和 Codex 审查线程，再核对签名/内嵌身份/摘要；缺少检查、失败或过期结果均不取得发布资格。工作流绿色或 artifact 存在不能单独放行，不自动部署。当前实际执行范围见 HANDOVER；配置变更不等同于新版 Release/设备验收。

## NAS 文件清单

| 文件 | 用途 |
|---|---|
| `Dockerfile` | 服务端镜像构建（debian-slim + ffmpeg + 静态二进制 + SPA 产物） |
| `config-docker.yaml` | 打进镜像的兜底配置（生产安全基线，部署差异走环境变量） |
| `docker-compose.yml` | 生产样例：端口/卷挂载/健康检查/自动重启 |

## 第一步 · 开发机构建产物

```bash
make web-build             # -> web/dist（SPA 静态产物）
make server-linux-amd64    # -> build/linux/amd64/qimeng-server（CGO_ENABLED=0 纯 Go 静态）
```

## 第二步 · 组装构建上下文并构建镜像

构建上下文目录需要三个条目（名字固定）：`qimeng-server`、`web-dist/`、`config-docker.yaml`。

```bash
mkdir -p ctx/web-dist
cp build/linux/amd64/qimeng-server ctx/qimeng-server
cp -r web/dist/* ctx/web-dist/
cp deploy/config-docker.yaml ctx/config-docker.yaml
docker build -f deploy/Dockerfile -t qimeng-media:1.0 ctx
```

**网络备注**（国内环境实测）：Docker Hub 直连常超时。可先从镜像源拉基础镜像，按同一 digest 从受信镜像源拉取；替换
Dockerfile 的 `FROM` 时保留 digest，不能回退为裸 tag（虚拟机实测用的是 `docker.m.daocloud.io`；fnOS 自带
`docker.fnnas.com` 加速源）：

```bash
docker pull docker.m.daocloud.io/library/debian:bookworm-slim
```

## 第三步 · NAS 上部署

1. 把镜像导入 NAS（`docker save qimeng-media:1.0 | ssh ... docker load`，或 NAS 能联网时直接构建）；
2. 把 `deploy/docker-compose.yml` 放到 NAS（如 `/vol1/1000/qimeng/`），改两处卷路径：
   - 媒体库根（挂到容器 `/media`，读写——上传/回收站要写权限）；
   - 服务端数据目录（挂到容器 `/data`，**绝不能指向媒体库目录内**）；
3. `docker compose up -d`，浏览器访问 `http://<NAS IP>:8420` 即可注册媒体库（填 `/media` 下子目录）。

## 配置调整

扫描调度支持 `QIMENG_SCANNER_POLL_INTERVAL`（默认 `5m`，非正回默认）与 `QIMENG_SCANNER_WATCH_ENABLED`（默认 `true`），启动生效。网络挂载可能静默漏通知，仍需轮询；确认本地监听和可接受延迟后才能显式调长周期。配置格式、事件处理与成本边界统一见 [服务端扫描说明](../server/README.md#媒体库后台扫描)。本次不代表未来 NAS 硬件已验收。

镜像内 yaml 只是兜底基线；所有部署差异用 `QIMENG_*` 环境变量覆盖（compose `environment:`），
可覆盖项清单见 `server/internal/config/config.go` 的 `applyEnv`。常用：

| 变量 | 说明 |
|---|---|
| `QIMENG_AUTH_DEV_MODE` | 免密直进开关。**仅限内网测试，生产必须保持关闭（默认）** |
| `QIMENG_UPLOAD_MAX_BYTES` | 单文件上传上限（字节，默认 2GB） |
| `QIMENG_THUMBNAIL_WORKERS` | 缩略图并发（默认按 CPU 核数） |
| `QIMENG_ALLOWED_LIBRARY_ROOTS` | 库注册白名单（镜像基线为 `/media`） |
| `QIMENG_TRASH_RETENTION_DAYS` | 回收站保留天数（默认 30；到期自动物理清除） |
| `QIMENG_TRASH_SWEEP_INTERVAL` | 回收站到期巡检间隔（默认 1h） |
| `QIMENG_TRUSTED_HOSTS` | 允许的 Host 域名白名单（默认只放行 IP 直连与 localhost；用域名/魔法 DNS 访问时配置） |
| `QIMENG_LOCAL_SYNC_ROOT` | 本机文件夹自动同步通道的同步根（**空=关，默认关闭**）；直接子文件夹名=库名自动匹配入库，成功源文件移走、失败原地保留（ADR-0030） |
| `QIMENG_LOCAL_SYNC_INTERVAL` | 同步根轮询周期（默认 30s；想立即同步走维护页「立即同步」或 POST /local-sync/trigger） |
| `QIMENG_LOCAL_SYNC_STABLE_AGE` | 文件稳定门槛（默认 60s）：mtime 年龄≥门槛且跨轮 size/mtime 不变才处理，防半截拷贝入库 |
| `QIMENG_BACKUP_ENABLED` / `QIMENG_BACKUP_INTERVAL` / `QIMENG_BACKUP_RETENTION` | 备份调度（compose 注释亦引用此族）：库文件热备快照 开关（默认 true）/ 间隔（默认 24h）/ 轮转保留份数（默认 7 份）。**2026-10-03 起：Web 维护页保存过调度参数后（kv 覆盖），这三键即不再生效**（优先级 kv>env>yaml>默认，热生效免重启；详见 GUIDE_API 备份节） |

## 安全红线（部署时逐条自查）

- 不在公网暴露端口：8420 只在内网/隧道（Tailscale）可达；
- `auth_dev_mode` 生产必须关闭；
- 媒体库与数据目录分离，数据目录有自动备份（默认 24h 快照 × 7 份，见维护页）；
- 删除语义 = 回收站；物理删除是维护页里的独立显式操作。

## 可选权限加固

设置部署环境的 `QIMENG_CONTAINER_UID`、`QIMENG_CONTAINER_GID`（不可为 0），核对 /data 与媒体/回收站卷对其可读写，再叠加：

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.hardened.yml config
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.hardened.yml up -d
```

配置要求显式 UID/GID，丢弃全部 capability 并设置 no-new-privileges；不自动 chown 真实媒体。先在合成库验证健康、上传、回收站/恢复、快照及 ffmpeg，再在目标设备验收。当前环境无 Docker/NAS，未运行此部署。备份类型、独立原件副本和恢复清单见 [恢复指南](../docs/RESTORE.md)。

## 遗留项

- **buildx 双架构（arm64）**：2026-09-22 记录为 fnOS 虚拟机 amd64 彩排；当前无 NAS 硬件。arm64 镜像按未来设备需求构建并验收，不能把旧里程碑当目标硬件证明。
- 非 root/降权配置已提供，目标卷权限未验收，按下节显式选择并验证；基础镜像 digest 已固定并核对官方清单，当前镜像构建与 CI 镜像产出仍待落实。
- 端口改动属协议改动：`api/openapi.yaml`、`config-docker.yaml`、compose 三处同步。

## 实测记录（2026-09-22，fnOS 虚拟机彩排）

- 环境：VirtualBox 7.2.16 + fnOS 1.2.0401（在线升级至 1.2.0604），amd64，桥接网络；
- 镜像 `qimeng-media:1.0`（590MB，含 ffmpeg）构建成功，容器内 ffmpeg/ffprobe 自检通过；
- compose 部署后：`GET /api/v1/healthz` 200（局域网 2ms）、Web UI 托管正常、
  容器状态 `healthy`；`restart=unless-stopped` 经服务重启验证自动拉起；
- 全链路：注册库（白名单内）→ 扫描 8 个合成测试文件 → 缩略图/时长 → 局域网播放 → 上传 201 入库。
