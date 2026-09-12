#!/data/data/com.termux/files/usr/bin/bash
# qimeng-watchdog.sh —— 启梦服务端看门狗：周期巡检，服务端进程不在则拉回；
# 连续拉起失败按指数退避拉长巡检间隔（30s 起步、每次 ×2、上限 300s），
# 防"起不来还死循环重试烧电"（拍板决策 2）。
#
# 用法（与 start 一样放后台跑）：
#   nohup bash ~/.qimeng/bin/qimeng-watchdog.sh >/dev/null 2>&1 &
# 或在 ~/.termux/boot/ 开机脚本里与 start 一起双拉（示例见 README.md）。
#
# 注意：主动停机请先停本看门狗，否则它会把服务端重新拉起（README「停止与看门狗」）。
# 失败计数只存于本进程内存（watchdog 自身被杀则重启从头计数，可接受——蓝本定稿）。

# ---- 常量（与 qimeng-start.sh 内嵌同一份并互指；不抽公共文件=拍板决策 8）----
BIN_DIR="$HOME/.qimeng/bin"
SERVER="$BIN_DIR/qimeng-server"
# 端口口径同 qimeng-start.sh：18430，与任务T T3 的 App 端
# ServerConfigDataSource 本机模式预设互指，单值不改。
PORT=18430
START_SCRIPT="$BIN_DIR/qimeng-start.sh"

BASE_INTERVAL=30   # 巡检起步间隔（秒）
MAX_INTERVAL=300   # 退避上限（秒）

fail_count=0
interval=$BASE_INTERVAL

while true; do
    if pgrep -f "^$SERVER$" >/dev/null 2>&1; then
        # 进程健在：连续失败计数清零、退避重置
        if [ "$fail_count" -gt 0 ]; then
            echo "[watchdog] 服务端已恢复，退避重置为 ${BASE_INTERVAL}s。"
        fi
        fail_count=0
        interval=$BASE_INTERVAL
    else
        echo "[watchdog] 服务端不在运行，尝试拉起..."
        # start 自带防重复/环境自检/healthz 验证；不关心其退出码，
        # 拉起成败以 start 返回后的进程存在性为准（对蓝本"进无进程分支即 +1"
        # 的微调：拉起成功立即清零退避，避免无谓等待一整轮，意图不变）
        bash "$START_SCRIPT" >/dev/null 2>&1 || true
        if pgrep -f "^$SERVER$" >/dev/null 2>&1; then
            fail_count=0
            interval=$BASE_INTERVAL
            echo "[watchdog] 拉起成功。"
        else
            fail_count=$((fail_count + 1))
            echo "[watchdog] 拉起失败（连续第 $fail_count 次），${interval}s 后重试。"
        fi
    fi
    sleep "$interval"
    # 退避翻倍放 sleep 之后：失败重试等待序列 30→60→120→240→上限 300（首值
    # 30 由循环顶部初值保证实际生效），且启动时立即首巡检不等一个间隔。
    if [ "$fail_count" -gt 0 ]; then
        interval=$((interval * 2))
        if [ "$interval" -gt "$MAX_INTERVAL" ]; then
            interval=$MAX_INTERVAL
        fi
    fi
done
