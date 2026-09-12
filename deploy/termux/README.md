# 启梦媒体库 · Termux 单机形态部署指南（M6 形态 A）

把 Go 服务端跑进手机里的 Termux，App 通过 `http://127.0.0.1:18430` 访问本机服务端——
不依赖 PC / NAS，整台手机就是一台媒体库。

本目录四个文件：

| 文件 | 作用 |
|---|---|
| `qimeng-start.sh` | 一键启动（环境自检 → 拉起服务端 → 健康检查 → 通知栏提示） |
| `qimeng-stop.sh` | 优雅停止（SIGTERM 等 10s，超时 SIGKILL 兜底） |
| `qimeng-watchdog.sh` | 看门狗：服务端掉了自动拉回，连续失败指数退避 |
| `README.md` | 本文档 |

三个脚本内嵌同一份常量（目录 / 端口等，注释互指），**改路径或端口需三处同步**；
其中端口 `18430` 是定稿口径：与 App 端本机连接预设互指，单值不改，确需变更必须两端同步。

## 0. 前置：安装 Termux 与 Termux:Boot

- 渠道：**只从 F-Droid 装**（https://f-droid.org ，分别搜 Termux 与 Termux:Boot）。
  官方警告：Google Play 上的 Termux 版本早已停止更新，在 Android 10+ 上已无法正常使用，**不要装 Play 版**。
- Termux:Boot 是开机自启组件（可选；不需要开机自启可以不装），必须与 Termux 同渠道安装；
  **装完后必须手动打开一次 Termux:Boot 应用**（只打开即可），否则它不会接收开机广播。

## 1. 投放文件

### 1.1 服务端二进制

PC 上（本仓库根目录）交叉编译：

```bash
make server-android-arm64    # 产物 build/android/arm64-v8a/qimeng-server
```

传入手机（adb 通道示例；网盘 / 聊天工具传文件也可）：

```bash
adb push build/android/arm64-v8a/qimeng-server /sdcard/Download/
```

> adb 无法直接写 Termux 私有目录，所以先中转 `/sdcard/Download/`，再在 Termux 里挪进去。

Termux 里：

```bash
termux-setup-storage        # 授权存储（首次必做；弹出框点「允许」）
mkdir -p ~/.qimeng/bin
mv /sdcard/Download/qimeng-server ~/.qimeng/bin/qimeng-server
chmod +x ~/.qimeng/bin/qimeng-server
```

### 1.2 脚本三件

把本目录 `qimeng-start.sh`、`qimeng-stop.sh`、`qimeng-watchdog.sh` 一并传入手机（任意通道），
与二进制同放 `~/.qimeng/bin/`。脚本用 `bash 脚本名` 方式运行，无需 chmod。

## 2. ffmpeg 两条来路（二选一，可不装）

服务端依赖 ffmpeg / ffprobe 生成缩略图与读取媒体元数据；**缺失时降级运行**
（缩略图 404 占位、元数据留空、下次扫描自动重探），不影响启动。
启动脚本只检测、不代装，装不装留给你决定：

- **来路 A（简单）**：Termux 里执行 `pkg install ffmpeg`。
  注意这是 GPL-3.0 构建（含 GPL 组件），个人自用没有问题。
- **来路 B（讲究）**：投放 LGPL 构建成品（`ffmpeg` 与 `ffprobe` **成对**）到 `~/.qimeng/bin/`。
  成品来源与锁 hash 的细节由开发者侧的 M6 调研备忘录管理（本仓库外），
  正式投放清单在 M6 内嵌批次（任务T T6）定稿；当前个人使用推荐来路 A。

两条来路互斥：`bin/` 下有投放版就优先用投放版（PATH 前置），否则用 pkg 版。

## 3. 启动、验证与开机自启

一键启动：

```bash
bash ~/.qimeng/bin/qimeng-start.sh
```

看到 `[成功] 服务端已就绪：http://127.0.0.1:18430` 即启动完成。App 端连接：
当前先在 App 的服务器设置里手动填 `http://127.0.0.1:18430`；「本机模式一键预设」
随 M6 后续批次（任务T T3）交付，届时可直接选。

开机自启（需已装 Termux:Boot）：新建 `~/.termux/boot/qimeng.sh`：

```bash
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/qimeng.sh <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Termux:Boot 官方建议 boot 脚本第一行先拿唤醒锁；qimeng-start.sh 内部已做，这里直接调即可
bash ~/.qimeng/bin/qimeng-start.sh
nohup bash ~/.qimeng/bin/qimeng-watchdog.sh >> ~/.qimeng/watchdog.log 2>&1 &
EOF
# Termux:Boot 只执行有执行位的脚本（官方要求）——创建后必须 chmod：
chmod +x ~/.termux/boot/qimeng.sh
```

重启手机验证：开机后不久 Termux 通知出现、`curl http://127.0.0.1:18430/healthz` 通。

## 4. 保活三件（必做，否则锁屏后服务端会被系统杀掉）

1. **通知勿划掉**：Termux 的存活依赖其前台服务通知（官方 issue #4657）——
   从任务栏划掉 Termux 通知 = 杀掉 Termux = 服务端和看门狗一起死。
2. **电池优化白名单**：系统设置 → 电池 → Termux → 不限制（各 ROM 入口名称不一）。
3. **国产 ROM 自启 / 后台**：MIUI / EMUI / ColorOS 等的「自启动管理」「后台运行」里
   允许 Termux 与 Termux:Boot 自启并后台驻留。

## 5. 停止与看门狗

```bash
# 停服务端（优雅退出最多 10s，超时强杀）
bash ~/.qimeng/bin/qimeng-stop.sh

# 看门狗：后台常驻，服务端不在则拉回；连续失败指数退避（30s→60s→120s→240s→上限 5min）
# 日志落 ~/.qimeng/watchdog.log（排查拉回行为时看它）
nohup bash ~/.qimeng/bin/qimeng-watchdog.sh >> ~/.qimeng/watchdog.log 2>&1 &
```

主动停机必须**先停看门狗、再停服务端**，否则看门狗会把服务端拉回来：

```bash
pkill -f qimeng-watchdog.sh           # 先停看门狗
bash ~/.qimeng/bin/qimeng-stop.sh     # 再停服务端
```

（开机自启 + 看门狗双拉的 boot 脚本写法见第 3 节，此处不重复。）

## 6. 常见故障

| 现象 | 排查 |
|---|---|
| 启动报 `[失败] ... 15 秒内未就绪` | 看日志：`tail -n 50 ~/.qimeng/server.log`（上一代日志 `server.log.old`） |
| 存储授权失败 | 重跑 `termux-setup-storage`，手机上点「允许」后重试 |
| 端口 18430 被占 | `ss -tlnp \| grep 18430`；Termux 无 ss 则 `pkg install iproute2` 后再试，或 `netstat -tlnp`（需 `pkg install net-tools`） |
| 缩略图全 404 / 元数据空 | ffmpeg 未装（降级模式），按第 2 节补装后等下次扫描自动补齐 |
| App 连不上 | 先在手机上验证服务端活着：浏览器开 `http://127.0.0.1:18430/healthz` 看到 `{"status":"alive"}` 即通（Termux 全新安装没有 curl 命令，浏览器或 `pkg install curl` 后 curl 均可）；不通看日志，通了再查 App 端；再检查保活三件是否失守 |
| 更新服务端版本 | PC 重新 `make server-android-arm64` → 按 1.1 重投 → 先 `qimeng-stop.sh` 再 `qimeng-start.sh` |
