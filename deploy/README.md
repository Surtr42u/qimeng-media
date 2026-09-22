# deploy - NAS 部署

服务端上 NAS 的标准路径（M5 部署三件套）：**Dockerfile（镜像）+ docker-compose.yml（生产样例）+ 本文档**。
2026-09-22 已在 fnOS 1.2 虚拟机（VirtualBox，amd64）全流程实测通过，实测记录见文末。

## 文件清单

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

**网络备注**（国内环境实测）：Docker Hub 直连常超时。可先从镜像源拉基础镜像，再把
Dockerfile 的 `FROM` 换成完整引用（虚拟机实测用的是 `docker.m.daocloud.io`；fnOS 自带
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

## 安全红线（部署时逐条自查）

- 不在公网暴露端口：8420 只在内网/隧道（Tailscale）可达；
- `auth_dev_mode` 生产必须关闭；
- 媒体库与数据目录分离，数据目录有自动备份（默认 24h 快照 × 7 份，见维护页）；
- 删除语义 = 回收站；物理删除是维护页里的独立显式操作。

## 遗留项

- **buildx 双架构（arm64）**：2026-09-22 用户完成真机 NAS 部署测试（M5 收官，见 PROJECT_PLAN）；实测部署形态为 amd64，arm64 镜像移为按需储备（未来部署 arm64 NAS 时按本文档同法构建，产物换 `GOARCH=arm64` + `docker buildx`）。
- 非 root 运行（compose `user: "1000:1000"`）：可选加固项，未在实测路径验证过，启用前需确认两个挂载卷对 uid 1000 可写。
- 端口改动属协议改动：`api/openapi.yaml`、`config-docker.yaml`、compose 三处同步。

## 实测记录（2026-09-22，fnOS 虚拟机彩排）

- 环境：VirtualBox 7.2.16 + fnOS 1.2.0401（在线升级至 1.2.0604），amd64，桥接网络；
- 镜像 `qimeng-media:1.0`（590MB，含 ffmpeg）构建成功，容器内 ffmpeg/ffprobe 自检通过；
- compose 部署后：`GET /api/v1/healthz` 200（局域网 2ms）、Web UI 托管正常、
  容器状态 `healthy`；`restart=unless-stopped` 经服务重启验证自动拉起；
- 全链路：注册库（白名单内）→ 扫描 8 个合成测试文件 → 缩略图/时长 → 局域网播放 → 上传 201 入库。
