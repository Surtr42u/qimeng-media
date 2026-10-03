#!/data/data/com.termux/files/usr/bin/bash
# qimeng-start.sh —— 启梦媒体库 Termux 单机形态（M6 形态 A）服务端启动脚本
#
# 职责：环境自检 → nohup 拉起 qimeng-server → healthz 就绪确认 → 通知栏提示。
# 配套：qimeng-watchdog.sh（巡检拉回）、qimeng-stop.sh（优雅停止）、README.md（使用手册）。
#
# 退出码约定：
#   0 = 已在运行 / 启动成功
#   1 = 不在 Termux 环境
#   2 = 存储授权失败
#   3 = 服务端二进制缺失
#   4 = 15 秒内 healthz 未就绪

# ---- 常量（qimeng-stop.sh / qimeng-watchdog.sh 各自内嵌同一份，注释互指；
# ---- 不抽公共文件是拍板决策 8：Termux 投放讲单文件自包含，优先于消重）----
BIN_DIR="$HOME/.qimeng/bin"       # 二进制与脚本目录（Termux 私有目录，exec/文件锁语义可靠）
DATA_DIR="$HOME/.qimeng/data"     # 数据库等状态目录
LOG="$HOME/.qimeng/server.log"    # 服务端日志（>10MB 轮转为 .old，仅保留一代）
# 端口口径（本批定稿）：18430——与任务T T3 的 App 端 ServerConfigDataSource
# 本机模式预设互指，单值不改；确需变更必须两端同步。
PORT=18430
SERVER="$BIN_DIR/qimeng-server"   # 来源：PC 仓库根 make server-android-arm64
                                  # 产物 build/android/arm64-v8a/qimeng-server

# ---- 兜底 1：Termux 环境自检 ----
if [ -z "$PREFIX" ] || ! command -v termux-wake-lock >/dev/null 2>&1; then
    echo "[错误] 未检测到 Termux 环境（\$PREFIX 或 termux-wake-lock 不可用）。"
    echo "       本脚本只能在 Android 的 Termux 里运行，安装方式见 README.md。"
    exit 1
fi

# ---- 兜底 2：存储授权引导（媒体根 ~/storage/shared 依赖 termux-setup-storage）----
if [ ! -d "$HOME/storage/shared" ]; then
    echo "[提示] 尚未授权存储访问，现在弹出系统授权框，请在手机上点「允许」..."
    termux-setup-storage
    sleep 3   # 授权框是异步弹出的系统 UI，给 3 秒操作时间后复查
    if [ ! -d "$HOME/storage/shared" ]; then
        echo "[错误] 存储授权未完成（~/storage/shared 仍不存在）。"
        echo "       可稍后在 Termux 里手动执行 termux-setup-storage 后重试。"
        exit 2
    fi
fi

# ---- 兜底 3：二进制自检 + ffmpeg 双来路（互斥三选一）----
if [ ! -x "$SERVER" ]; then
    echo "[错误] 服务端二进制不可执行：$SERVER"
    echo "       请在 PC 的仓库根执行 make server-android-arm64，"
    echo "       把产物 build/android/arm64-v8a/qimeng-server 传入手机放到上述路径并 chmod +x，"
    echo "       操作细节见 README.md「投放服务端二进制」。"
    exit 3
fi

# pgrep 匹配用 ^路径$ 锚定（-f 全命令行）：服务端命令行就是纯路径（无参数），
# 锚定可排除"命令行恰好含该路径文本"的无关进程（如正在传输/校验该文件的命令）。
if [ -x "$BIN_DIR/ffmpeg" ] && [ -x "$BIN_DIR/ffprobe" ]; then
    # 来路一：投放版成品（ffmpeg 与 ffprobe 成对投放——半投放按缺失处理，见下）
    export PATH="$BIN_DIR:$PATH"
    echo "[信息] ffmpeg：使用投放版（$BIN_DIR 已前置到 PATH）"
elif { [ -x "$BIN_DIR/ffmpeg" ] || [ -x "$BIN_DIR/ffprobe" ]; } \
    && ! { command -v ffmpeg >/dev/null 2>&1 && command -v ffprobe >/dev/null 2>&1; }; then
    # 半投放态：bin/ 下只有其一且 PATH 无补——探测/缩略图必有缺口，按降级警示
    echo "[警告] $BIN_DIR 下 ffmpeg/ffprobe 只投放了其一（需成对），PATH 也无补齐来源，"
    echo "       探测/缩略图将降级运行；请补齐另一个或都撤走（见 README「ffmpeg 两条来路」）。"
elif command -v ffmpeg >/dev/null 2>&1 && command -v ffprobe >/dev/null 2>&1; then
    # 来路二：Termux pkg 安装版
    echo "[信息] ffmpeg：使用 Termux pkg 版（$(command -v ffmpeg)）"
else
    # 拍板决策 5：脚本不代装 ffmpeg，只检测提示，安装决策留给用户
    echo "[警告] 未检测到 ffmpeg/ffprobe，服务端将降级启动："
    echo "       缩略图 404 占位、媒体元数据留空、下次扫描自动重探（服务端内建行为，不影响运行）。"
    echo "       补装方式见 README.md「ffmpeg 两条来路」。"
fi

# ---- 兜底 4：防重复拉起 ----
if pgrep -f "^$SERVER$" >/dev/null 2>&1; then
    echo "[信息] 服务端已在运行（127.0.0.1:$PORT），本次不重复启动。"
    exit 0
fi

mkdir -p "$DATA_DIR"

# CPU 唤醒锁：防手机休眠冻结服务端（失败不阻断启动，只降保活能力）
termux-wake-lock >/dev/null 2>&1 || echo "[警告] termux-wake-lock 获取失败，休眠保活能力受限。"

# 日志轮转：>10MB 轮转为 .old，仅保留一代（防日志吃光手机存储）
if [ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 10485760 ]; then
    mv -f "$LOG" "$LOG.old"
fi

echo "[信息] 启动服务端（监听 127.0.0.1:$PORT，日志 $LOG）..."
# dev 免密（2026-09-14 加）：App 登录页无「首次设密码」对接（只实现 login/dev-login，
# 见 android App 端登录流）。监听 127.0.0.1 只挡住局域网访问，但 Android 的 loopback
# 是全设备共享——同机其他 App 仍可到达本端口、POST /auth/dev-login 免密取得 token
# （dev 模式风险与缓解路径见 SECURITY.md「已知安全边界」）。生产化（NAS/外网）时必须
# 去掉本行（SECURITY.md「开发模式」）。
# QIMENG_WEB_STATIC_DIR（2026-09-14 加）：指向随二进制投放的正式 Web 管理界面（SPA）；
# 目录缺失时服务端自动回退内嵌 M1 验收页（原行为不变），目录存在即提供完整管理界面。
QIMENG_LISTEN="127.0.0.1:$PORT" QIMENG_DATA_DIR="$DATA_DIR" QIMENG_AUTH_DEV_MODE="1" \
    QIMENG_WEB_STATIC_DIR="$HOME/.qimeng/web/dist" \
    nohup "$SERVER" >> "$LOG" 2>&1 &

# healthz 探测（双路）：curl 优先；Termux 全新安装的 bootstrap 不含 curl
# （pkg install curl 才有），无 curl 时走 bash 内建 /dev/tcp 发裸 HTTP——
# 两者都在，说明机器上有 curl；后者纯 bash 零外部依赖，永远可用。
healthz_ok() {
    if command -v curl >/dev/null 2>&1; then
        curl -sf "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1
    else
        # 裸 HTTP/1.0 请求读状态行（"HTTP/1.1 200 OK"）——只判就绪不解析 body。
        timeout 3 bash -c \
            "exec 3<>/dev/tcp/127.0.0.1/$PORT && printf 'GET /healthz HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n' >&3 && head -n 1 <&3" \
            2>/dev/null | grep -q ' 200 '
    fi
}

# healthz 就绪轮询：最多 15 秒（/healthz 是服务端根路径运维探针别名）
i=0
while [ "$i" -lt 15 ]; do
    if healthz_ok; then
        echo "[成功] 服务端已就绪：http://127.0.0.1:$PORT"
        # 通知栏提示运行中（Termux:API 可选组件，缺失或失败都不阻断）
        if command -v termux-notification >/dev/null 2>&1; then
            termux-notification --title "启梦媒体库" \
                --content "服务端运行中：127.0.0.1:$PORT" >/dev/null 2>&1 || true
        fi
        exit 0
    fi
    sleep 1
    i=$((i + 1))
done

echo "[失败] 服务端 15 秒内未就绪，最近 50 行日志如下："
tail -n 50 "$LOG" 2>/dev/null || echo "（日志文件不存在）"
exit 4
