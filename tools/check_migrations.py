"""对指定 Git 基线核验历史 SQL migration：只加不改不删，包含工作区改动。"""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def check_migrations(root: Path, base: str) -> list[str]:
    # 不执行 shell；先核实基线，缺失时失败而非静默跳过保护。
    commit = subprocess.check_output(
        ['git', 'rev-parse', '--verify', f'{base}^{{commit}}'], cwd=root).decode().strip()
    names = subprocess.check_output(
        ['git', 'ls-tree', '-r', '--name-only', commit, '--', 'server/migrations'], cwd=root).decode('utf-8').splitlines()
    errors = []
    for name in names:
        if not name.endswith('.sql'):
            continue
        # 通过 Git 工作树 diff 比较，尊重仓库行尾归一化；不把 CRLF 当修改。
        changed = subprocess.run(
            ['git', 'diff', '--quiet', commit, '--', name], cwd=root, check=False)
        if changed.returncode == 1:
            errors.append(f'历史 migration 不可修改/删除/改名：{name}')
        elif changed.returncode != 0:
            raise RuntimeError(f'git diff failed: {name}')
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='HEAD', help='PR 使用 base SHA；本机默认 HEAD，包含暂存/工作区变更')
    args = parser.parse_args()
    errors = check_migrations(ROOT, args.base)
    for error in errors:
        print('ERROR: ' + error)
    print(f'Migration immutability: {len(errors)} error(s)')
    return bool(errors)


if __name__ == '__main__':
    raise SystemExit(main())
