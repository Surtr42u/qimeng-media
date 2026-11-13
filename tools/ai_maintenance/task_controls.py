"""Trusted executor grants and session budgets; never infer authorization from confirm."""
from __future__ import annotations

import hashlib
import json
import math
import os
import threading
import time
import tempfile
from contextlib import contextmanager
from pathlib import Path

from . import errors, pathguard


def _failure(code, message, *, details=None):
    return errors.ToolError(code, message, category="permission" if code.startswith("AUTH") else "budget",
                            details=details, recovery="由可信执行端核对授权/任务预算；保留已有结果，不循环重试。")


def _number(env, name, default, maximum):
    try:
        value = int(env.get(name, str(default)))
    except (TypeError, ValueError):
        raise errors.configuration_error(name + " 必须是整数") from None
    if not 1 <= value <= maximum:
        raise errors.configuration_error(name + f" 必须在 1..{maximum}")
    return value


def authorize(spec, payload, env):
    """An explicit permission is a standing delegation; an optional grant narrows it per task.

    The file is configured by the executor, never supplied through tool arguments. Existing
    Go scope, resource versions, local roots and plan validation remain mandatory.
    """
    path = env.get("QIMENG_AI_AUTHORIZATION_FILE")
    if not spec.side_effects or not path:
        return
    try:
        grant_path = Path(path)
        if grant_path.stat().st_size > 64 * 1024:
            raise ValueError()
        grant = json.loads(grant_path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        raise errors.configuration_error("无法读取有效的 AI 任务授权文件") from None
    if (not isinstance(grant, dict) or grant.get("version") != 1
            or set(grant) - {"version", "apiBase", "expiresAt", "allow"}):
        raise errors.configuration_error("AI 任务授权文件版本无效")
    expiry = grant.get("expiresAt")
    if (isinstance(expiry, bool) or not isinstance(expiry, (int, float))
            or not math.isfinite(expiry) or expiry <= time.time()):
        raise _failure("AUTHORIZATION_EXPIRED", "任务授权已过期或缺少有效期")
    if str(grant.get("apiBase", "")).rstrip("/") != str(env.get("QIMENG_API_BASE", "")).rstrip("/"):
        raise _failure("AUTHORIZATION_MISMATCH", "任务授权不属于当前服务实例")
    entries = grant.get("allow")
    if not isinstance(entries, list):
        raise errors.configuration_error("AI 任务授权 allow 必须是数组")
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) - {"tool", "arguments"}:
            raise errors.configuration_error("AI 任务授权条目字段无效")
        if entry.get("tool") != spec.id:
            continue
        arguments = entry.get("arguments", {})
        if not isinstance(arguments, dict):
            raise errors.configuration_error("AI 任务授权 arguments 必须是对象")
        matches = True
        for path, expected in arguments.items():
            value = payload
            for part in path.split("."):
                if not isinstance(value, dict) or part not in value:
                    matches = False
                    break
                value = value[part]
            if not matches or value != expected:
                matches = False
                break
        if matches:
            return
    raise _failure("AUTHORIZATION_MISMATCH", "当前工具或参数超出任务授权范围",
                   details={"tool": spec.id})


class SessionBudget:
    """Bound calls/time and repeated unchanged requests in one executor session.

    No model usage is guessed: actual model tokens/cost belong to the host. CLI recipe
    calls share RuntimeConfig; separate CLI processes are separate sessions, explicitly
    not a durable account-wide or Go worker budget.
    """
    def __init__(self, env):
        self.max_calls = _number(env, "QIMENG_AI_MAX_CALLS", 100, 10000)
        self.max_seconds = _number(env, "QIMENG_AI_MAX_SECONDS", 900, 86400)
        self.max_repeats = _number(env, "QIMENG_AI_MAX_UNCHANGED", 3, 100)
        self.started = time.monotonic()
        self.state = {"startedAt": time.time(), "calls": 0, "observations": {}}
        self.lock = threading.Lock()
        self.path = None
        task_id = env.get("QIMENG_AI_TASK_ID")
        if task_id:
            if not env.get("QIMENG_AI_STATE_DIR"):
                raise errors.configuration_error("持久任务预算需要 QIMENG_AI_STATE_DIR")
            identity = hashlib.sha256(json.dumps([task_id, env.get("QIMENG_API_BASE", "")],
                                                  sort_keys=True).encode()).hexdigest()
            self.path = Path(env["QIMENG_AI_STATE_DIR"]) / "budgets" / (identity + ".json")
            if self.path.parent.exists() and pathguard.is_linkish(self.path.parent):
                raise errors.configuration_error("任务预算目录不能是链接")
            self.path.parent.mkdir(parents=True, exist_ok=True)

    @contextmanager
    def _state(self):
        with self.lock:
            if self.path is None:
                yield self.state
                return
            # Small executor counters, not another business-task database. The OS lock
            # covers read/modify/atomic-publish across CLI processes and MCP sessions.
            lock_path = self.path.with_suffix(".lock")
            if any(path.exists() and pathguard.is_linkish(path) for path in (lock_path, self.path)):
                raise _failure("TASK_BUDGET_UNAVAILABLE", "任务预算文件不能是链接，未执行操作")
            with lock_path.open("a+b") as handle:
                handle.seek(0, os.SEEK_END)
                if handle.tell() == 0:
                    handle.write(b"0")
                    handle.flush()
                deadline = time.monotonic() + 2
                while True:
                    try:
                        handle.seek(0)
                        if os.name == "nt":
                            import msvcrt
                            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                        else:
                            import fcntl
                            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                        break
                    except OSError:
                        if time.monotonic() >= deadline:
                            raise _failure("TASK_BUDGET_UNAVAILABLE", "任务预算被占用，未执行操作") from None
                        time.sleep(.02)
                try:
                    state = self.state
                    if self.path.exists():
                        if self.path.stat().st_size > 8 * 1024 * 1024:
                            raise ValueError("budget too large")
                        state = json.loads(self.path.read_text(encoding="utf-8"))
                        if (not isinstance(state, dict) or type(state.get("calls")) is not int
                                or state["calls"] < 0
                                or not isinstance(state.get("observations"), dict)
                                or not isinstance(state.get("startedAt"), (int, float))
                                or not math.isfinite(state["startedAt"])):
                            raise ValueError("invalid budget")
                        for observation in state["observations"].values():
                            if (not isinstance(observation, list) or len(observation) != 2
                                    or not isinstance(observation[0], str)
                                    or type(observation[1]) is not int or observation[1] < 0):
                                raise ValueError("invalid budget observation")
                    yield state
                    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=self.path.parent,
                                                     prefix="budget-", delete=False) as temporary:
                        json.dump(state, temporary, separators=(",", ":"))
                        temporary.flush()
                        os.fsync(temporary.fileno())
                    os.replace(temporary.name, self.path)
                except (OSError, ValueError, TypeError):
                    raise _failure("TASK_BUDGET_UNAVAILABLE", "任务预算记录不可用，须先核对已执行结果") from None
                finally:
                    handle.seek(0)
                    if os.name == "nt":
                        import msvcrt
                        msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                    else:
                        import fcntl
                        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)

    def reserve(self, tool, payload):
        request = hashlib.sha256(json.dumps([tool, payload], ensure_ascii=False,
                                            sort_keys=True, default=lambda _: "<non-json>").encode()).hexdigest()
        with self._state() as state:
            elapsed = (time.time() - state["startedAt"]) if self.path else (time.monotonic() - self.started)
            if state["calls"] >= self.max_calls or elapsed >= self.max_seconds:
                raise _failure("TASK_BUDGET_EXHAUSTED", "任务调用数或时间预算已耗尽",
                               details={"calls": state["calls"], "maxCalls": self.max_calls})
            _, unchanged = state["observations"].get(request, (None, 0))
            if unchanged >= self.max_repeats:
                raise _failure("TASK_NO_PROGRESS", "相同请求连续未产生新结果，已停止重复调用")
            state["calls"] += 1
        return request

    def complete(self, request, result):
        digest = hashlib.sha256(json.dumps(result, ensure_ascii=False, sort_keys=True,
                                           default=lambda _: "<non-json>").encode()).hexdigest()
        with self._state() as state:
            previous, count = state["observations"].get(request, (None, 0))
            state["observations"][request] = (digest, count + 1 if digest == previous else 1)

    def report(self):
        with self._state() as state:
            return {"calls": state["calls"], "maxCalls": self.max_calls,
                    "maxSeconds": self.max_seconds, "maxUnchanged": self.max_repeats,
                    "scope": "task" if self.path else "executor-session", "modelUsage": "host-not-reported"}
