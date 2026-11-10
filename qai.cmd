@echo off
rem 绮梦影库 AI 工具平台短入口：qai task author.discover / qai run media.batch ...
rem 只做参数转发，不读取 token、不自动启动服务、不打开浏览器。
setlocal
python -X utf8 "%~dp0tools\ai_maintenance\cli.py" %*
