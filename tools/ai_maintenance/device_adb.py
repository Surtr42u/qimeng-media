"""ADB 设备原语：推送/拉取/目录核对（唯一实现，供 recipe 与本地脚本复用）。

安全边界（AGENTS「Android 必须显式指定设备」）：
- 设备序列号只从 `QIMENG_ADB_SERIAL` 读取，**没有默认值**；未配置直接拒绝，
  绝不回落 `adb devices` 的第一个设备，也绝不操作 `emulator-5554` 之类的默认模拟器。
- `adb` 路径可由 `QIMENG_ADB` 覆盖，否则只用 PATH 中的 adb。
- 远程路径一律使用 POSIX 形式；文件按单个 `adb push` 推送并回读字节数，
  避免目录 push 在部分设备上挂起，也避免 Windows 反斜杠被当作文件名。
- 不打印、不返回 token；本模块只处理设备文件，不触碰业务数据库。
"""
from __future__ import annotations

import os
import posixpath
import shlex
import shutil
import subprocess

from . import pathguard

ADB_PRIMITIVE_VERSION = "1.0.0"
FORBIDDEN_SERIALS = {"emulator-5554"}
_CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


class AdbError(RuntimeError):
    def __init__(self, message: str, *, code: str = "ADB_ERROR", details=None):
        super().__init__(message)
        self.code = code
        self.details = details or {}


def adb_path(env=None) -> str:
    override = (env or {}).get("QIMENG_ADB")
    if override:
        return str(override)
    found = shutil.which("adb")
    if not found:
        raise AdbError("未找到 adb：设置 QIMENG_ADB 或把 platform-tools 加入 PATH", code="ADB_NOT_FOUND")
    return found


def serial(env=None) -> str:
    value = ((env or {}).get("QIMENG_ADB_SERIAL") or "").strip()
    if not value:
        raise AdbError(
            "未配置 QIMENG_ADB_SERIAL：本工具不默认选择设备，必须显式指定目标真机序列号",
            code="ADB_SERIAL_REQUIRED",
        )
    if value in FORBIDDEN_SERIALS:
        raise AdbError(
            "拒绝操作默认模拟器 " + value + "：请显式指定用户真机序列号",
            code="ADB_SERIAL_FORBIDDEN",
        )
    return value


def _run(argv, *, timeout: float = 120.0) -> str:
    try:
        completed = subprocess.run(
            argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=timeout, check=False, creationflags=_CREATE_NO_WINDOW,
        )
    except subprocess.TimeoutExpired as exc:
        raise AdbError("adb 命令超时", code="ADB_TIMEOUT", details={"seconds": timeout}) from exc
    except OSError as exc:
        raise AdbError("adb 无法执行：" + type(exc).__name__, code="ADB_EXEC_FAILED") from exc
    if completed.returncode != 0:
        message = (completed.stderr or completed.stdout or b"").decode("utf-8", "replace").strip()
        raise AdbError("adb 命令失败：" + (message[:300] or "unknown"), code="ADB_COMMAND_FAILED",
                       details={"returncode": completed.returncode})
    return completed.stdout.decode("utf-8", "replace")


def shell(command: str, *, env=None, timeout: float = 120.0) -> str:
    return _run([adb_path(env), "-s", serial(env), "shell", command], timeout=timeout)


def device_info(env=None) -> dict:
    output = shell("getprop ro.product.model; getprop ro.build.version.release", env=env)
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    return {"model": lines[0] if lines else "", "android": lines[1] if len(lines) > 1 else ""}


def _quote_remote(path: str) -> str:
    # adb shell 命令走远端 sh；这里统一用 POSIX 引号，保留空格但不解释通配符。
    return shlex.quote(path)


def remote_size(path: str, *, env=None) -> int | None:
    output = shell("stat -c %s " + _quote_remote(path) + " 2>/dev/null", env=env)
    text = output.strip()
    if not text.isdigit():
        return None
    return int(text)


def ensure_dir(path: str, *, env=None) -> str:
    normalized = posixpath.normpath(path)
    shell("mkdir -p " + _quote_remote(normalized), env=env)
    return normalized


def push_file(local_path: str, remote_dir: str, *, env=None, timeout: float = 300.0) -> dict:
    local = os.path.abspath(local_path)
    if env is not None:
        # 推送源是读操作：调用方传入授权环境时再复核一次读写根。
        local = str(pathguard.resolve_local_path(local, env=env, must_exist=True,
                                                 kind="本地推送源", mode="read"))
    if not os.path.isfile(local):
        raise AdbError("本地文件不存在：" + local, code="ADB_LOCAL_MISSING")
    remote_dir = ensure_dir(remote_dir, env=env)
    name = os.path.basename(local)
    remote_path = posixpath.join(remote_dir, name)
    _run([adb_path(env), "-s", serial(env), "push", local, remote_path], timeout=timeout)
    expected = os.path.getsize(local)
    actual = remote_size(remote_path, env=env)
    if actual != expected:
        raise AdbError(
            "推送后字节数不一致",
            code="ADB_SIZE_MISMATCH",
            details={"remote": remote_path, "expected": expected, "actual": actual},
        )
    return {"local": local, "remote": remote_path, "bytes": expected}


def push_many(items, remote_dir: str, *, env=None, timeout: float = 300.0) -> dict:
    """items: [(local_path, remote_name)] 或 [(local_path, None)]；逐个推送并回读。"""
    pushed = []
    failed = []
    total = 0
    for local_path, remote_name in items:
        local = os.path.abspath(local_path)
        if env is not None:
            local = str(pathguard.resolve_local_path(local, env=env, must_exist=True,
                                                     kind="本地推送源", mode="read"))
        target_dir = remote_dir
        target_name = remote_name or os.path.basename(local)
        remote_path = posixpath.join(target_dir, target_name)
        try:
            if target_name != os.path.basename(local):
                ensure_dir(target_dir, env=env)
                _run([adb_path(env), "-s", serial(env), "push", local, remote_path], timeout=timeout)
                expected = os.path.getsize(local)
                actual = remote_size(remote_path, env=env)
                if actual != expected:
                    raise AdbError("推送后字节数不一致", code="ADB_SIZE_MISMATCH",
                                   details={"remote": remote_path, "expected": expected,
                                            "actual": actual})
                pushed.append({"local": local, "remote": remote_path, "bytes": expected})
                total += expected
            else:
                result = push_file(local, target_dir, env=env, timeout=timeout)
                pushed.append(result)
                total += result["bytes"]
        except AdbError as exc:
            failed.append({"local": local, "remote": remote_path, "code": exc.code,
                           "message": str(exc)})
    return {"pushed": pushed, "failed": failed, "bytes": total,
            "status": "ok" if not failed else "partial"}


def pull_file(remote_path: str, local_path: str, *, env=None, timeout: float = 300.0) -> dict:
    if env is not None:
        # 本地落盘是写操作：必须落在显式写授权根内。
        local = str(pathguard.resolve_local_path(local_path, env=env, must_exist=False,
                                                 kind="本地拉取目标", mode="write"))
    else:
        local = os.path.abspath(local_path)
    os.makedirs(os.path.dirname(local), exist_ok=True)
    _run([adb_path(env), "-s", serial(env), "pull", remote_path, local], timeout=timeout)
    if not os.path.isfile(local):
        raise AdbError("拉取后本地文件不存在", code="ADB_PULL_MISSING")
    return {"remote": remote_path, "local": local, "bytes": os.path.getsize(local)}


def move_remote(source: str, target: str, *, env=None) -> dict:
    source = posixpath.normpath(source)
    target = posixpath.normpath(target)
    shell("mv " + _quote_remote(source) + " " + _quote_remote(target), env=env)
    return {"source": source, "target": target}
