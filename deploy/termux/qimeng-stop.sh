#!/data/data/com.termux/files/usr/bin/bash
# qimeng-stop.sh —— 优雅停止启梦服务端。
#
# 顺序：SIGTERM（优雅退出）→ 最多等 10 秒 → 仍在则 SIGKILL 兜底 → 释放唤醒锁。
#
# SIGTERM 等待 10s 的对齐依据（server/cmd/qimeng/main.go）：服务端用
# signal.NotifyContext 捕获 SIGTERM 后，在 shutdownTimeout=10s 内先执行 HTTP
# Shutdown，再按 缩略图工作池 → 事件总线 → 数据库连接 的顺序收尾，保证在途
# 缩略图任务排空、库文件完整落盘；超时强杀可能留下未写完的 WAL（可恢复损伤，
# 但应尽量避免）。等待上限与服务端 shutdownTimeout 同值（拍板决策 3）。
#
# 注意：本脚本不停看门狗（不在职责内）。若 qimeng-watchdog.sh 正在后台运行，
# 它会把服务端重新拉起——主动停机请先停看门狗（操作顺序见 README「停止与看门狗」）。

# ---- 常量（与 qimeng-start.sh 内嵌同一份并互指；不抽公共文件=拍板决策 8）----
BIN_DIR="$HOME/.qimeng/bin"
SERVER="$BIN_DIR/qimeng-server"
# 端口口径同 qimeng-start.sh：18430，与任务T T3 的 App 端
# ServerConfigDataSource 本机模式预设互指，单值不改。
PORT=18430

WAIT_SECONDS=10   # SIGTERM 优雅退出等待上限（秒）

PIDS="$(pgrep -f "^$SERVER$")"
if [ -z "$PIDS" ]; then
    echo "[信息] 服务端未在运行，无需停止。"
    # 未在运行 ≠ 没拿过唤醒锁：服务端若崩溃退出，start 拿的 wake-lock 仍在
    # 持有（耗电）——清场语义下无条件补一次解锁（幂等，未持有时无副作用）。
    if command -v termux-wake-unlock >/dev/null 2>&1; then
        termux-wake-unlock >/dev/null 2>&1 || true
    fi
    exit 0
fi

echo "[信息] 向服务端（PID: $(echo "$PIDS" | tr '\n' ' ')）发送 SIGTERM，等待优雅退出（最多 ${WAIT_SECONDS}s）..."
kill $PIDS 2>/dev/null

i=0
while [ "$i" -lt "$WAIT_SECONDS" ] && pgrep -f "^$SERVER$" >/dev/null 2>&1; do
    sleep 1
    i=$((i + 1))
done

if pgrep -f "^$SERVER$" >/dev/null 2>&1; then
    echo "[警告] ${WAIT_SECONDS}s 内未退出，SIGKILL 强制结束。"
    pkill -9 -f "^$SERVER$" 2>/dev/null || true
fi

# 释放唤醒锁（Termux:API 可选组件，缺失或失败不阻断）
if command -v termux-wake-unlock >/dev/null 2>&1; then
    termux-wake-unlock >/dev/null 2>&1 || true
fi

echo "[完成] 服务端已停止。"
