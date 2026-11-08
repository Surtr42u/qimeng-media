"""受控填充作者公开档案（兼容入口）。

实现已迁入 tools/ai_maintenance/profile_import.py（唯一写入算法）；本脚本只
保留旧命令行参数、退出码、输出类别与环境变量兼容映射。旧入口默认离线
dry-run，显式 --apply 才经正式 API 写入。

兼容说明：--base-url 缺省时依次读取 QIMENG_BASE_URL（弃用，打印一次提示、
不回显值）与 QIMENG_API_BASE；token 仍只从 QIMENG_API_TOKEN 读取。
"""
from __future__ import annotations

import sys
from pathlib import Path

try:
    from ai_maintenance import profile_import as _core
except ImportError:  # 直接从仓库根以其他方式导入时兜底定位 tools/
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from ai_maintenance import profile_import as _core

# 兼容旧测试/调用方引用的名字；全部来自统一实现，不复制算法。
ImportFailure = _core.ImportFailure
API = _core.API
NoRedirect = _core.NoRedirect
REQUEST_TIMEOUT_SECONDS = _core.REQUEST_TIMEOUT_SECONDS
MAX_REPORT_BYTES = _core.MAX_REPORT_BYTES
read_json = _core.read_json
public_url = _core.public_url
plan_records = _core.plan_records
validate_base_url = _core.validate_base_url
pristine = _core.pristine
apply_plan = _core.apply_plan


def main(argv=None):
    """转发统一实现；api_factory=API 便于旧测试替换 importer.API。"""
    return _core.run_cli(argv, api_factory=API)


if __name__ == "__main__":
    sys.exit(main())
