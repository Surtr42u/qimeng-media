"""校验 glib 上游源码快照及 Cargo 实际采用的本地安全补丁。"""

import hashlib
import json
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def check_patch(root: Path) -> list[str]:
    vendor = root / 'desktop/vendor/glib'
    manifest = json.loads((vendor.parent / 'glib-source.json').read_text(encoding='utf-8'))
    expected = manifest['files']
    # Cargo.lock 为本项目新增的上游优化测试锁；其他文件必须与经核实的快照一致。
    allowed = set(expected) | {'Cargo.lock'}
    actual = {p.relative_to(vendor).as_posix() for p in vendor.rglob('*') if p.is_file()
              and 'target' not in p.relative_to(vendor).parts}
    errors = []
    if actual != allowed:
        errors.append(f'glib 快照文件集合变化：缺少 {sorted(allowed - actual)}；新增 {sorted(actual - allowed)}')
    for name, digest in expected.items():
        path = vendor / name
        if not path.resolve().is_relative_to(vendor.resolve()):
            errors.append(f'glib 快照路径越界：{name}')
        elif path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            errors.append(f'glib 快照内容变化：{name}')
    cargo_root = root / 'desktop/src-tauri'
    cargo = tomllib.loads((cargo_root / 'Cargo.toml').read_text(encoding='utf-8'))
    override = cargo.get('patch', {}).get('crates-io', {}).get('glib', {})
    if (cargo_root / override.get('path', '')).resolve() != vendor.resolve():
        errors.append('桌面 Cargo 未使用本地 glib 补丁')
    lock = tomllib.loads((cargo_root / 'Cargo.lock').read_text(encoding='utf-8'))
    glib = [p for p in lock['package'] if p['name'] == 'glib']
    if len(glib) != 1 or glib[0]['version'] != manifest['version'] or 'source' in glib[0]:
        errors.append('桌面锁文件仍含注册表 glib 或未使用唯一的本地补丁版本')
    return errors


if __name__ == '__main__':
    failures = check_patch(ROOT)
    for failure in failures:
        print(f'ERROR: {failure}')
    print(f'glib 源码与依赖来源检查：{len(failures)} 项错误')
    raise SystemExit(bool(failures))
