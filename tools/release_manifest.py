"""核对本机服务端产物身份并写交付清单；不停止服务、不安装、不接触数据库。"""
import argparse
import json
from pathlib import Path
import subprocess

from cold_backup import digest, files, regular

ROOT = Path(__file__).resolve().parents[1]


def git(*args: str) -> str:
    return subprocess.check_output(['git', *args], cwd=ROOT).decode('utf-8').strip()


def validate_identity(identity: dict, version: str, revision: str, dirty: bool, development: bool) -> None:
    if identity.get('version') != version or identity.get('revision') != revision:
        raise ValueError('二进制版本/提交不匹配；拒绝把旧产物标为当前交付')
    if identity.get('modified') is not dirty:
        raise ValueError('工作区与二进制 dirty 标记不一致；请重新构建')
    if dirty and not development:
        raise ValueError('正式交付必须为干净检出；开发核对须显式 --development，不能作为正式发布')


def inventory(path: Path) -> list[dict]:
    if path.is_dir():
        return [{'file': p.relative_to(path).as_posix(), 'sha256': digest(p), 'bytes': p.stat().st_size} for p in files(path)]
    regular(path)
    return [{'file': path.name, 'sha256': digest(path), 'bytes': path.stat().st_size}]


def prepare(server: Path, web: Path | None, apk: Path | None, output: Path, development: bool) -> dict:
    if output.exists() or output.is_symlink():
        raise ValueError('交付清单目标已存在；用新文件保留历史，不覆盖旧交付记录')
    regular(server)
    # 只执行本次显式指定的本机产物 -version；该入口在加载配置/打开 DB 前退出。
    before = digest(server)
    identity = json.loads(subprocess.check_output([str(server.resolve()), '-version'], timeout=15))
    if digest(server) != before:
        raise ValueError('核对期间二进制发生变化')
    revision = git('rev-parse', 'HEAD')
    dirty = bool(git('status', '--porcelain', '--untracked-files=normal'))
    version = (ROOT / 'server/internal/buildinfo/version.txt').read_text(encoding='utf-8').strip()
    validate_identity(identity, version, revision, dirty, development)
    components = {'server': inventory(server)}
    # 这些哈希只确认输入产物，不能证明 APK 签名/内嵌 Go 或 Web 的构建来源；
    # 对应检查仍由 AI 按平台交付流程完成，清单不伪称它们同源已验收。
    if web is not None:
        if not (web / 'index.html').is_file():
            raise ValueError('Web 目录缺少 index.html')
        components['web'] = inventory(web)
    if apk is not None:
        components['apk'] = inventory(apk)
    migrations = list((ROOT / 'server/migrations').glob('*.up.sql'))
    schema = max(int(p.name.split('_', 1)[0]) for p in migrations)
    manifest = {
        'formatVersion': 1, 'kind': 'development' if development else 'release',
        'serverBuild': identity, 'schemaVersion': schema,
        'openapiSha256': digest(ROOT / 'api/openapi.yaml'),
        'sdkLockSha256': digest(ROOT / 'api/sdk.lock'),
        'androidVersionCode': int((ROOT / 'server/internal/buildinfo/android-version-code.txt').read_text().strip()),
        'components': components, 'deployment': 'not-performed',
        'rollback': 'Restore the stopped old DataDir and media copy with its matching binary; never open upgraded schema with an older binary.',
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True, help='本机可执行服务端产物；不支持执行其他平台 ELF')
    parser.add_argument('--web', type=Path)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--output', type=Path, required=True, help='存忽略的 build/ 或本地交付目录；不记录本机绝对路径')
    parser.add_argument('--development', action='store_true')
    args = parser.parse_args()
    result = prepare(args.server, args.web, args.apk, args.output, args.development)
    print(f'Manifest: {result["kind"]}, server={result["serverBuild"]["version"]}, schema={result["schemaVersion"]}; deployment not performed')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
