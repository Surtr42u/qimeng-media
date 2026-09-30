#!/usr/bin/env bash
# =============================================================================
# 任务T T1：Android 模拟器 shell 域闭环验证脚本（M6 单机形态，ADR-0015）
#
# 用途：把 make server-android-amd64 的产物推进模拟器，在设备 shell 域跑通
#   启动 → healthz → dev-login → 注册库 → 扫描 → 资产浏览 → 无 ffmpeg 降级
#   断言（视频元数据留空 / 缩略图 404 占位）→ sysmon 状态 → SQLite WAL 落盘
#   → 启动自检 Warn 日志，逐步落证据到 $EVID_DIR。
#
# 模拟器口径（铁律13）：只允许 qimeng_api35t AVD，固定 -port 5581 → serial
#   emulator-5581。严禁触碰 qimeng_api35 / qimeng_api35b / 雷电模拟器
#   （emulator-5554，伪装 MI 9 的用户游戏机）。模拟器启动不在本脚本内
#   （主会话负责 powershell 脱离进程树拉起），缺失时报错退出并提示命令。
#
# 运行环境：Windows Git Bash。依赖：adb / make / go / curl / GNU timeout
#   （Git for Windows 自带后四者）。
#
# 幂等：每轮开始先清设备端旧产物（含 qm-data 旧库，避免同根目录注册 409），
#   结束保留 qm-data 与 qm-server.log 供人工复查（证据目录另有全量拷贝）。
# =============================================================================
set -euo pipefail

# ---------- 常量区 ----------
# adb 固定取 Android SDK platform-tools（PATH 里可能有别的 adb，显式指定唯一确定；
# 默认按 %LOCALAPPDATA% 定位 SDK，本机 SDK 装在别处时 ADB=/路径/adb.exe 覆盖）。
ADB="${ADB:-${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe}"
# qimeng_api35t 固定 -port 5581 拉起 → serial 恒为 emulator-5581（头注释口径）。
EMU_SERIAL="emulator-5581"
# 服务端只绑设备回环，经 adb forward 映射到宿主机同端口。
PORT="18430"
BASE="http://127.0.0.1:${PORT}"
# 设备内工作目录（shell 域可写；App 域 /data/data 不可达，本脚本是 shell 域验证）。
DEV_TMP="/data/local/tmp"
LIB_ROOT="${DEV_TMP}/qm-fixture"
DATA_DIR="${DEV_TMP}/qm-data"
SERVER_BIN="${DEV_TMP}/qimeng-server"
SERVER_LOG="${DEV_TMP}/qm-server.log"

# Git Bash 会把 /data/... 形态的参数改写成 Windows 路径——全局关闭路径转换；
# 本地路径需要给原生 Windows 工具（adb）时用 cygpath 显式转混排形态。
export MSYS_NO_PATHCONV=1

# 仓库根 = 本脚本（deploy/emulator-verify/）向上两级。
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# 证据目录：默认 $TEMP/qimeng-t1-evidence，可 EVID_DIR=... 覆盖；统一转 POSIX
# 形态（只被 bash 重定向使用，不传给原生工具）。
EVID_DIR="${EVID_DIR:-${TEMP:-/tmp}/qimeng-t1-evidence}"
EVID_DIR="$(cygpath -u "$EVID_DIR")"
mkdir -p "$EVID_DIR"

die() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "===== [T1] $* ====="; }

# ---------- 小工具：JSON 字段提取（sed 基线，不依赖 jq） ----------
# str_field <json段> <key>：提取 "key":"value" 字符串值（Go json.Encoder 默认
# HTML 转义，& → \u0026，故配 unescape_url 还原查询串）。
str_field() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p"; }
unescape_url() { sed 's/\\u0026/\&/g; s/\\u003c/</g; s/\\u003e/>/g'; }
# assert_absent_or_null <json段> <key> <描述>：omitempty 指针字段的"空"断言
# （无 ffprobe 降级时 durationMs/width/height/videoCodec 缺省或 null）。
assert_absent_or_null() {
  local seg="$1" key="$2" desc="$3" val
  if printf '%s' "$seg" | grep -q "\"$key\":"; then
    val=$(printf '%s' "$seg" | sed -n "s/.*\"$key\":\([^,}]*\).*/\1/p")
    [ "$val" = "null" ] || die "$desc: $key 应为空（无 ffprobe 降级），实际 $val"
  fi
  echo "    OK: $desc ($key 为空)"
}

# ---------- 步骤 0：幂等清理（上一轮残留会 409/占端口） ----------
step "0/11 idempotent cleanup (previous run leftovers)"
"$ADB" -s "$EMU_SERIAL" shell 'pkill qimeng-server' >/dev/null 2>&1 || true
"$ADB" -s "$EMU_SERIAL" forward --remove "tcp:${PORT}" >/dev/null 2>&1 || true
"$ADB" -s "$EMU_SERIAL" shell "rm -rf ${LIB_ROOT} ${DATA_DIR} ${SERVER_LOG} ${SERVER_BIN}" || true
echo "    cleaned (qm-data will be kept at end-of-run for manual inspection)"

# ---------- 步骤 1：前置检查（设备在线 + boot 完成；不负责启动模拟器） ----------
step "1/11 preflight: device online & booted (qimeng_api35t @ ${EMU_SERIAL})"
"$ADB" devices >"$EVID_DIR/01-adb-devices.txt" 2>&1
grep -q "^${EMU_SERIAL}[[:space:]]\+device$" "$EVID_DIR/01-adb-devices.txt" \
  || die "emulator ${EMU_SERIAL} (qimeng_api35t) not online. Start it from the main session, e.g.:
  powershell -Command \"Start-Process -FilePath \\\"$env:LOCALAPPDATA/Android/Sdk/emulator/emulator.exe\\\" -ArgumentList '-avd','qimeng_api35t','-port','5581','-no-snapshot-save'\""
boot_ok=""
for i in $(seq 1 90); do # 轮询上限 180s（90 次 x 2s）
  boot_ok="$("$ADB" -s "$EMU_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '[:space:]\r')" || true
  [ "$boot_ok" = "1" ] && break
  sleep 2
done
[ "$boot_ok" = "1" ] || die "sys.boot_completed != 1 within 180s on ${EMU_SERIAL}"
echo "    device online, boot completed"

# ---------- 步骤 2：构建 x86_64 服务端（Makefile target，模拟器专用产物） ----------
step "2/11 build: make server-android-amd64"
( cd "$REPO_ROOT" && make server-android-amd64 ) >"$EVID_DIR/02-build.log" 2>&1 \
  || { cat "$EVID_DIR/02-build.log"; die "build failed (see $EVID_DIR/02-build.log)"; }
REPO_ROOT_MIXED="$(cygpath -m "$REPO_ROOT")" # adb 是原生 Windows 程序，收混排路径
BIN_HOST="${REPO_ROOT_MIXED}/build/android/x86_64/qimeng-server"
[ -f "$BIN_HOST" ] || die "build artifact missing: $BIN_HOST"
echo "    built: build/android/x86_64/qimeng-server"

# ---------- 步骤 3：推送 + 可执行位 ----------
step "3/11 push binary & chmod 755"
"$ADB" -s "$EMU_SERIAL" push "$BIN_HOST" "$SERVER_BIN" >"$EVID_DIR/03-push.txt" 2>&1 \
  || die "adb push failed (see $EVID_DIR/03-push.txt)"
"$ADB" -s "$EMU_SERIAL" shell "chmod 755 ${SERVER_BIN}"
echo "    pushed to ${SERVER_BIN}"

# ---------- 步骤 4：造虚构媒体（真 mp4 排除"文件损坏"干扰变量） ----------
step "4/11 create media fixture (real mp4 via screenrecord + broken jpg)"
"$ADB" -s "$EMU_SERIAL" shell "mkdir -p ${LIB_ROOT}"
# 真 mp4：screenrecord 由设备媒体栈产出合法容器——无 ffmpeg 降级断言才能归因
# 到"缺二进制"而非"文件本身坏了"（约 3s）。
"$ADB" -s "$EMU_SERIAL" shell "screenrecord --time-limit 3 ${LIB_ROOT}/fake-video.mp4"
# 假图片：几字节非 JPEG 内容，模拟"不可解码图片走缩略图失败分支"。
"$ADB" -s "$EMU_SERIAL" shell "printf not-a-real-jpeg > ${LIB_ROOT}/fake-pic.jpg"
"$ADB" -s "$EMU_SERIAL" shell "ls -la ${LIB_ROOT}" >"$EVID_DIR/04-fixture-ls.txt" 2>&1
grep -q "fake-video.mp4" "$EVID_DIR/04-fixture-ls.txt" || die "fake-video.mp4 not created"
echo "    fixture ready (see $EVID_DIR/04-fixture-ls.txt)"

# ---------- 步骤 5：启动服务端（nohup 后台；timeout 包裹防 pty 挂住） ----------
step "5/11 start server on device (nohup background, dev auth mode)"
timeout 10 "$ADB" -s "$EMU_SERIAL" shell \
  "cd ${DEV_TMP} && QIMENG_DATA_DIR=${DATA_DIR} QIMENG_LISTEN=127.0.0.1:${PORT} QIMENG_AUTH_DEV_MODE=1 nohup ./qimeng-server > ${SERVER_LOG} 2>&1 &" \
  >"$EVID_DIR/05-server-start.txt" 2>&1 \
  || echo "(timeout wrapper exit=$? — expected when pty hangs; nohup keeps server alive, healthz poll below is the real gate)" \
     >>"$EVID_DIR/05-server-start.txt"
"$ADB" -s "$EMU_SERIAL" forward "tcp:${PORT}" "tcp:${PORT}"
echo "    server launched, adb forward tcp:${PORT} -> device tcp:${PORT}"

# ---------- 步骤 6：healthz 轮询（免鉴权探活，上限 30s） ----------
step "6/11 wait for /healthz (up to 30s)"
health_ok=0
for i in $(seq 1 30); do
  if curl -sS -m 2 "$BASE/healthz" >"$EVID_DIR/06-healthz.txt" 2>&1; then
    health_ok=1
    break
  fi
  sleep 1
done
[ "$health_ok" = "1" ] || { "$ADB" -s "$EMU_SERIAL" shell "cat ${SERVER_LOG}" >&2; die "healthz unreachable within 30s (server log above)"; }
echo "    healthz OK: $(cat "$EVID_DIR/06-healthz.txt")"

# ---------- 步骤 7：API 闭环 ----------
step "7/11 API loop: dev-login -> create library -> scan -> browse -> degradation asserts"
# 7.1 dev-login 免密签发 token（QIMENG_AUTH_DEV_MODE=1 通道）
LOGIN_RESP="$(curl -sS -X POST "$BASE/api/v1/auth/dev-login")"
printf '%s\n' "$LOGIN_RESP" >"$EVID_DIR/07-dev-login.json"
TOKEN="$(str_field "$LOGIN_RESP" token)"
[ -n "$TOKEN" ] || die "dev-login did not return a token: $LOGIN_RESP"
echo "    dev-login OK (token saved to evidence)"
AUTH="Authorization: Bearer ${TOKEN}"

# 7.2 注册库（字段名以 api/openapi.yaml LibraryCreate 为准：name + rootPath）
CREATE_RESP="$(curl -sS -X POST -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"name":"t1-fixture","rootPath":"'"${LIB_ROOT}"'"}' \
  -w '\n%{http_code}' "$BASE/api/v1/libraries")"
CREATE_CODE="$(printf '%s' "$CREATE_RESP" | tail -n 1)"
CREATE_BODY="$(printf '%s' "$CREATE_RESP" | sed '$d')"
printf '%s\n' "$CREATE_BODY" >"$EVID_DIR/08-library-create.json"
[ "$CREATE_CODE" = "201" ] || die "library create got $CREATE_CODE (expect 201): $CREATE_BODY"
LIB_ID="$(str_field "$CREATE_BODY" id)"
[ -n "$LIB_ID" ] || die "library create response has no id: $CREATE_BODY"
echo "    library created: id=${LIB_ID} root=${LIB_ROOT}"

# 7.3 触发扫描（202 = 异步已触发）。体+码合并捕获（同 7.2 模式）：不用
# curl -o 中转文件——Git Bash 下 -o 新建文件的写入偶发失败（curl exit 23，
# 实测一次），且 set -e 会让该偶发直接杀死脚本。
SCAN_RESP="$(curl -sS -X POST -H "$AUTH" -w '\n%{http_code}' \
  "$BASE/api/v1/libraries/${LIB_ID}/scan")"
SCAN_CODE="$(printf '%s' "$SCAN_RESP" | tail -n 1)"
printf '%s\n' "$SCAN_RESP" | sed '$d' >"$EVID_DIR/09-scan-accept.txt"
[ "$SCAN_CODE" = "202" ] || die "scan trigger got $SCAN_CODE (expect 202)"
echo "    scan triggered (202)"

# 7.4 轮询库扫描终态（GET /api/v1/libraries 列表里按 id 找 scanState；
#     协议无 GET /libraries/{id} 单库端点。退出判据 = 非 scanning 态且
#     （已见过 scanning 或已轮询≥3 次）——202 与状态置位间存在窗口，扫描
#     <2s 完成时首轮即见 idle，"先见 scanning"永不满足（实测白等满 120s）；
#     ≥3 次轮询（约 6s）覆盖置位窗口即可接受终态，真正的完成硬判据是
#     终态断言 + 7.5 的资产数断言。grep/curl 加 || true：set -e 下轮询循环
#     内的瞬时失败（grep 无匹配 exit 1）不允许杀死整个脚本。
seen_scanning=0
scan_state=""
for i in $(seq 1 60); do # 上限 120s（60 次 x 2s）
  LIBS="$(curl -sS -H "$AUTH" "$BASE/api/v1/libraries" || true)"
  scan_state="$(printf '%s' "$LIBS" | grep -o "\"id\":\"${LIB_ID}\"[^}]*" \
    | sed -n 's/.*"scanState":"\([^"]*\)".*/\1/p' || true)"
  [ "$scan_state" = "scanning" ] && seen_scanning=1
  if [ "$scan_state" != "scanning" ] && { [ "$seen_scanning" = "1" ] || [ "$i" -ge 3 ]; }; then break; fi
  sleep 2
done
printf '%s\n' "$LIBS" >"$EVID_DIR/10-scan-state-final.json"
[ "$scan_state" = "idle" ] || die "scan terminal state is '${scan_state}' (expect idle): $LIBS"
echo "    scan finished: scanState=idle"

# 7.5 资产浏览：≥1 项；视频项 durationMs 为空（无 ffprobe 降级）
ASSETS="$(curl -sS -H "$AUTH" "$BASE/api/v1/assets?libraryId=${LIB_ID}")"
printf '%s\n' "$ASSETS" >"$EVID_DIR/11-assets.json"
ASSET_COUNT="$(printf '%s' "$ASSETS" | grep -o '"id":"' | wc -l | tr -d '[:space:]')"
[ "$ASSET_COUNT" -ge 1 ] || die "assets list is empty after scan: $ASSETS"
VIDEO_SEG="$(printf '%s' "$ASSETS" | grep -o '{[^{}]*"mediaType":"video"[^{}]*}' | head -n 1)"
[ -n "$VIDEO_SEG" ] || die "no video item in assets list: $ASSETS"
VIDEO_ID="$(str_field "$VIDEO_SEG" id)"
THUMB_URL="$(str_field "$VIDEO_SEG" thumbUrl | unescape_url)"
assert_absent_or_null "$VIDEO_SEG" durationMs "video summary durationMs"
echo "    assets: ${ASSET_COUNT} item(s); video id=${VIDEO_ID}"

# 7.6 视频详情：width/height/videoCodec 为空（探测失败留空的降级口径）
DETAIL="$(curl -sS -H "$AUTH" "$BASE/api/v1/assets/${VIDEO_ID}")"
printf '%s\n' "$DETAIL" >"$EVID_DIR/12-asset-detail.json"
assert_absent_or_null "$DETAIL" width "video detail width"
assert_absent_or_null "$DETAIL" height "video detail height"
assert_absent_or_null "$DETAIL" videoCodec "video detail videoCodec"

# 7.7 缩略图 404 占位（THUMBNAIL_FAILED——无 ffmpeg 降级的行为证据）。
# 体+码合并捕获（同 7.2/7.3 模式，见 7.3 的 -o 偶发 exit 23 注记）。
[ -n "$THUMB_URL" ] || die "video item has no thumbUrl: $VIDEO_SEG"
THUMB_RESP="$(curl -sS -w '\n%{http_code}' "$BASE${THUMB_URL}")"
THUMB_CODE="$(printf '%s' "$THUMB_RESP" | tail -n 1)"
printf '%s' "$THUMB_RESP" | sed '$d' >"$EVID_DIR/13-thumb-response.json"
[ "$THUMB_CODE" = "404" ] || die "thumbnail got HTTP $THUMB_CODE (expect 404 placeholder): $(cat "$EVID_DIR/13-thumb-response.json")"
grep -q "THUMBNAIL_FAILED" "$EVID_DIR/13-thumb-response.json" \
  || die "thumbnail 404 body missing THUMBNAIL_FAILED: $(cat "$EVID_DIR/13-thumb-response.json")"
echo "    thumbnail: 404 + THUMBNAIL_FAILED (degraded, as designed without ffmpeg)"

# 7.8 系统状态（sysmon 输出存证据：CPU 核数/内存供主会话与设备 /proc 对照）
SYS_RESP="$(curl -sS -H "$AUTH" "$BASE/api/v1/system/status")"
printf '%s\n' "$SYS_RESP" >"$EVID_DIR/14-system-status.json"
grep -q "memTotalBytes" "$EVID_DIR/14-system-status.json" \
  || die "system/status response missing memTotalBytes: $SYS_RESP"
echo "    system status saved (memTotalBytes/perCore for /proc cross-check)"

# ---------- 步骤 8：SQLite WAL 三件套核对（modernc WAL 行为） ----------
step "8/11 SQLite WAL check: qimeng.db / -wal / -shm exist"
QM_LS="$("$ADB" -s "$EMU_SERIAL" shell "ls -la ${DATA_DIR}/" | tr -d '\r')"
printf '%s\n' "$QM_LS" >"$EVID_DIR/15-qm-data-ls.txt"
for f in qimeng.db qimeng.db-wal qimeng.db-shm; do
  grep -q " ${f}$" "$EVID_DIR/15-qm-data-ls.txt" || die "WAL member missing in ${DATA_DIR}: $f (see $EVID_DIR/15-qm-data-ls.txt)"
done
echo "    WAL trio present (db/-wal/-shm)"

# ---------- 步骤 9：服务端日志取证 + 启动自检断言（本批新功能行为证据） ----------
step "9/11 server log: startup self-check Warn expected (no ffmpeg on device)"
"$ADB" -s "$EMU_SERIAL" shell "cat ${SERVER_LOG}" | tr -d '\r' >"$EVID_DIR/16-qm-server.log"
grep -q "自检未通过" "$EVID_DIR/16-qm-server.log" \
  || die "server log lacks '自检未通过' Warn line (startup self-check evidence): $(head -c 2000 "$EVID_DIR/16-qm-server.log")"
echo "    startup self-check Warn found in log (CheckBinaries behavior evidence)"

# ---------- 步骤 10：收尾（杀进程/撤转发/清 fixture；保留 qm-data 供人工复查） ----------
step "10/11 cleanup: kill server, remove forward, wipe fixture (keep qm-data on device)"
"$ADB" -s "$EMU_SERIAL" shell 'pkill qimeng-server' >/dev/null 2>&1 || true
"$ADB" -s "$EMU_SERIAL" forward --remove "tcp:${PORT}" >/dev/null 2>&1 || true
"$ADB" -s "$EMU_SERIAL" shell "rm -rf ${LIB_ROOT}"
# 设备端保留 ${DATA_DIR}（含 qimeng.db/-wal/-shm）与 ${SERVER_LOG} 供人工复查；
# 下一轮运行由步骤 0 的幂等清理回收（同根目录重复注册会 409，必须先清库）。
echo "    cleaned; qm-data & qm-server.log kept on device for manual inspection"

# ---------- 步骤 11：汇总 ----------
step "11/11 ALL PASS — evidence in: $EVID_DIR"
ls -1 "$EVID_DIR"
