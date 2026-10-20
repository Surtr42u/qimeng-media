#!/usr/bin/env python3
"""仓库文档门禁：内联本地链接、ADR 索引、入口路径、归档和改动影响。

选型四问：通用 Markdown linter 能检查排版，不能检查本仓 ADR/归档/同步责任；
本工具只用标准库检查已采用的内联链接语法，不实现完整 Markdown 解析器。
引入引用式链接/MDX 时复查成熟解析库；统一工具具备这些仓库规则时整件替换。
语义是否准确仍由实现、测试与交付说明核实，不能用本工具证明。
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
RECENT_ENTRIES = 30
DOC_SUFFIXES = {'.md', '.txt'}
IMPACT_RULES = (
    ('api/', ('docs/api/',), 'API 专题及 sdk.lock'),
    ('server/internal/recommend/', ('docs/domain/recommendation.md',), '推荐规则'),
    ('server/internal/stats/', ('docs/domain/statistics.md',), '统计口径'),
    ('server/internal/sourcematcher/', ('docs/domain/matching.md',), '匹配规则'),
    ('server/internal/author', ('docs/domain/authors.md', 'docs/api/authors.md'), '作者规则'),
    ('server/internal/filing/', ('docs/domain/files.md', 'docs/SECURITY.md'), '文件安全'),
    ('server/internal/thumbnail/', ('docs/domain/thumbnails.md',), '缩略图规则'),
    ('server/internal/httpapi/', ('docs/api/', 'docs/SECURITY.md'), 'API 用法/安全'),
    ('server/internal/store/', ('docs/domain/', 'docs/ARCHITECTURE.md'), '数据口径/架构'),
    ('server/migrations/', ('docs/domain/', 'docs/ARCHITECTURE.md'), '迁移与数据口径'),
    ('android/', ('android/README.md', 'docs/GUIDE_UI.md', 'docs/domain/', 'docs/SECURITY.md'), 'Android/交互/领域'),
    ('web/', ('web/README.md', 'docs/GUIDE_UI.md', 'docs/api/', 'docs/domain/'), 'Web/交互/领域'),
    ('desktop/', ('desktop/README.md', 'docs/GUIDE_UI.md'), '桌面契约'),
    ('deploy/', ('deploy/README.md', 'docs/SECURITY.md', 'docs/RESTORE.md'), '部署/恢复'),
    ('.github/', ('docs/ARCHITECTURE.md', 'docs/SECURITY.md', 'tools/README.md'), '工程门禁'),
    ('tools/', ('tools/README.md', 'AI_README_FIRST.md'), '维护工具'),
    ('server/', ('server/README.md', 'docs/'), '服务端说明（含未单列模块）'),
)


def git(*args: str) -> str:
    return subprocess.check_output(['git', *args], cwd=ROOT).decode('utf-8')


def visible_markdown(text: str) -> str:
    return re.sub(r'^\s*(`{3,}|~{3,}).*?^\s*\1\s*$', '', text, flags=re.M | re.S)


def anchors(text: str) -> set[str]:
    found = set(re.findall(r'<a\s+(?:id|name)=["\']([^"\']+)', text))
    seen: dict[str, int] = {}
    for heading in re.findall(r'^#{1,6} (.+)$', visible_markdown(text), re.M):
        heading = re.sub(r'\[([^\]]+)\]\([^)]*\)', r'\1', heading)
        name = re.sub(r'[^\w\- ]', '', heading.replace('`', '').lower()).replace(' ', '-')
        count = seen.get(name, 0)
        seen[name] = count + 1
        found.add(name if count == 0 else f'{name}-{count}')
    return found


def check_links(root: Path, names: list[str]) -> list[str]:
    errors = []
    for name in names:
        path = root / name
        text = visible_markdown(path.read_text(encoding='utf-8-sig'))
        for link in re.finditer(r'(?<!!)\[[^\]\n]+\]\(([^)\n]+)\)', text):
            target = link[1].strip().strip('<>')
            if re.match(r'^[a-zA-Z][a-zA-Z\d+.-]*:', target):
                continue
            dest, _, fragment = unquote(target).partition('#')
            resolved = (path.parent / dest).resolve() if dest else path
            if not resolved.exists():
                errors.append(f'{name}: 链接目标不存在：{target}')
            elif fragment and resolved.suffix == '.md':
                if fragment not in anchors(resolved.read_text(encoding='utf-8-sig')):
                    errors.append(f'{name}: 章节不存在：{target}')
    return errors


def has_no_impact_reason(body: str) -> bool:
    return bool(re.search(r'文档影响[：:]\s*无[；;，,]\s*原因[：:]\s*[^\n<]{8,}', body))


def check_impact(changed: list[str], body: str) -> tuple[list[str], list[str]]:
    hints, errors = [], []
    for prefix, documents, label in IMPACT_RULES:
        sources = [p for p in changed if p.startswith(prefix) and Path(p).suffix not in DOC_SUFFIXES]
        if not sources:
            continue
        hints.append(f'{label}: ' + ', '.join(documents))
        covered = any(p.startswith(doc) if doc.endswith('/') else p == doc
                      for p in changed for doc in documents)
        if not covered and not has_no_impact_reason(body):
            errors.append(f'{label}: 未更新相关文档；确无语义影响时在 PR 写“文档影响：无；原因：具体依据”。CHANGELOG 单独更新不算专题同步。')
    return hints, errors


def check_structure(root: Path) -> list[str]:
    errors = []
    index = (root / 'docs/adr/INDEX.md').read_text(encoding='utf-8')
    numbers = []
    for path in (root / 'docs/adr').glob('[0-9][0-9][0-9][0-9]-*.md'):
        numbers.append(int(path.name[:4]))
        if f']({path.name})' not in index:
            errors.append(f'ADR 索引缺失正文链接：{path.name}')
    if len(set(numbers)) != len(numbers):
        errors.append('ADR 编号重复')
    if sorted(numbers) != list(range(1, max(numbers, default=0) + 1)):
        errors.append('ADR 编号不连续')
    changelog = (root / 'docs/CHANGELOG.md').read_text(encoding='utf-8')
    count = len(re.findall(r'^## (?!AI 署名约定).+$', changelog, re.M))
    if count > RECENT_ENTRIES:
        errors.append(f'CHANGELOG 有 {count} 笔，超过 {RECENT_ENTRIES}；完整归档并更新 history/INDEX')
    entry = (root / 'AGENTS.md').read_text(encoding='utf-8')
    for route in re.findall(r'`([^`]+\.(?:md|yaml|txt))`', entry):
        if not (root / route).is_file():
            errors.append(f'AGENTS 路由不存在：{route}')
    return errors


def check_workflows(root: Path) -> list[str]:
    """只检查本仓约定的 Actions 引用与顶层权限，不替代 YAML/actionlint。"""
    errors = []
    for path in (root / '.github/workflows').glob('*.yml'):
        text = path.read_text(encoding='utf-8')
        if not re.search(r'^permissions:\s*\n  contents: read\s*$', text, re.M):
            errors.append(f'{path.name}: 缺少顶层 contents: read 权限基线')
        for ref in re.findall(r'^\s*(?:-\s*)?uses:\s*([^\s#]+)', text, re.M):
            if not ref.startswith('./') and not re.fullmatch(r'[^@]+@[0-9a-f]{40}', ref):
                errors.append(f'{path.name}: Action 必须固定完整提交 SHA：{ref}')
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', help='检查 base..HEAD 的文档同步影响；CI 用 PR base SHA')
    parser.add_argument('--event-path', type=Path, help='只把 GitHub event JSON 当数据读取')
    args = parser.parse_args()
    names = sorted(set(git('ls-files').splitlines() + git('ls-files', '--others', '--exclude-standard').splitlines()))
    docs = [p for p in names if (p.endswith('.md') or p == 'llms.txt') and (ROOT / p).is_file()]
    errors = check_links(ROOT, docs) + check_structure(ROOT) + check_workflows(ROOT)
    if args.base:
        changed = git('diff', '--name-only', args.base, 'HEAD').splitlines()
        event = json.loads(args.event_path.read_text(encoding='utf-8')) if args.event_path else {}
        body = (event.get('pull_request') or {}).get('body') or ''
        hints, impact_errors = check_impact(changed, body)
        for hint in hints:
            print('文档影响候选：' + hint)
        errors += impact_errors
    for error in errors:
        print('ERROR: ' + error)
    print(f'文档检查：{len(docs)} 个文件，{len(errors)} 项错误；不替代语义复核。')
    return bool(errors)


if __name__ == '__main__':
    raise SystemExit(main())
