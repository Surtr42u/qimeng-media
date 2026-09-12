# emulator-verify — 任务T T1 模拟器 shell 域闭环验证

## 用途

`run-t1.sh` 是任务T T1 的证据脚本：把 `make server-android-amd64` 的产物推进
**qimeng_api35t** 模拟器（x86_64 镜像），在设备 shell 域跑通 M6 单机形态
（ADR-0015）的服务端闭环——启动 → healthz → dev-login → 注册库 → 扫描 →
资产浏览 → **无 ffmpeg 降级断言**（视频元数据留空、缩略图 404 占位）→
sysmon 状态 → SQLite WAL 落盘 → 启动自检 Warn 日志（本批 `CheckBinaries`
的行为证据）。

每步落证据文件（curl 响应、命令输出、设备日志）到证据目录，`set -e` 失败即停。

## 前置

- 模拟器已由主会话启动：AVD `qimeng_api35t`，固定 `-port 5581`（serial =
  `emulator-5581`）。**脚本只检查不启动**，缺失时报错并提示启动命令。
  铁律13：严禁对 qimeng_api35 / qimeng_api35b / 雷电（emulator-5554）执行
  本脚本任何操作。
- 宿主机（Windows Git Bash）：`curl` / GNU `timeout`（Git for Windows 自带）；
  `make` / `go` 需自行安装并在 PATH（仓库工具链口径见 Makefile 头部 PATH 补丁
  与 `../dev-tools/TOOLCHAIN_GUIDE.md`）；adb 取
  `<AndroidSdk>/platform-tools/adb.exe`。
- 服务端免密通道：设备侧环境变量 `QIMENG_AUTH_DEV_MODE=1`（脚本内已带），
  对应 `POST /api/v1/auth/dev-login`。

## 运行

```bash
# 仓库根执行（脚本自行定位仓库根，也可任意目录直接跑）
bash deploy/emulator-verify/run-t1.sh

# 自定义证据目录（默认 $TEMP/qimeng-t1-evidence）
EVID_DIR=D:/tmp/t1-proof bash deploy/emulator-verify/run-t1.sh
```

## 证据目录

默认 `$TEMP/qimeng-t1-evidence`（`EVID_DIR=` 可覆盖），产物按步骤编号：
`01-adb-devices.txt`、`02-build.log`、`03-push.txt`、`04-fixture-ls.txt`、
`05-server-start.txt`、`06-healthz.txt`、`07-dev-login.json`、
`08-library-create.json`、`09-scan-accept.txt`、`10-scan-state-final.json`、
`11-assets.json`、`12-asset-detail.json`、`13-thumb-response.json`、
`14-system-status.json`、`15-qm-data-ls.txt`、`16-qm-server.log`。

设备端保留 `/data/local/tmp/qm-data/`（qimeng.db/-wal/-shm）与
`/data/local/tmp/qm-server.log` 供人工复查；下一轮运行由脚本步骤 0 幂等
回收（同根目录重复注册库会 409，必须先清旧库）。
