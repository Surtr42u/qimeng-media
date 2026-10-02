#!/usr/bin/env bash
# assert-apk-embedded.sh -- APK 内嵌三件套完整性断言（CI 门禁，2026-10-02 手搓
# 治理批新增，第四道门禁族之三的姊妹断言）。
#
# 为什么：内嵌形态（ADR-0015 形态 B）的可装机产物 = APK 内必须随包
# lib/arm64-v8a/{libqimeng.so,libffmpeg_cli.so,libffprobe_cli.so}（jniLibs 目录
# 不入 git，装配链断一环 = 出一个"本机模式静默不可用"的包，App 侧只会在运行时
# 以通知兜底）。本断言让"忘了装配/装配失败"在 CI 就红，而不是真机装机后才发现。
#
# 用法: bash deploy/embedded/assert-apk-embedded.sh <path-to.apk>
# 退出码：0 = 三件套齐全；1 = 任一缺失（error 行列出缺失项）。
# 依赖：unzip（ubuntu runner / 多数发行版自带；Windows 本机用 PowerShell
#       Expand-Archive 或直接信任 CI）。

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "usage: $0 <path-to.apk>" >&2
    exit 1
fi
APK="$1"
if [ ! -f "$APK" ]; then
    echo "APK 不存在: $APK" >&2
    exit 1
fi

LISTING="$(unzip -l "$APK")"

missing=0
for lib in libqimeng.so libffmpeg_cli.so libffprobe_cli.so; do
    entry="lib/arm64-v8a/$lib"
    line="$(grep -F "$entry" <<<"$LISTING" || true)"
    if [ -z "$line" ]; then
        echo "::error::内嵌三件套缺失：$entry 不在 APK 内（jniLibs 装配链断裂——检查 make app-embedded-arm64 等价链是否跑全，见 deploy/embedded/README.md）"
        missing=1
    else
        echo "[ok  ] $entry"
    fi
done

if [ "$missing" -ne 0 ]; then
    exit 1
fi
echo "done: 内嵌三件套完整性断言通过 ($APK)"
