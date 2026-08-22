# qimeng-media 一键命令
# 约定：所有常用操作必须一条命令完成，禁止让 AI/用户记忆长命令。
# M0 期间逐个补全实现，未实现的命令先输出"未实现，见 PROJECT_PLAN M0"。

.PHONY: help sdk server-run server-test web-dev web-test docker-build lint

help: ## 显示全部命令
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

sdk: ## 从 api/openapi.yaml 生成三端 SDK（Go 接口层 + TS + Kotlin）
	@echo "TODO(M0): oapi-codegen + @hey-api/openapi-ts + openapi-generator kotlin"

server-run: ## 本地运行服务端（读取 config/local.yaml）
	@echo "TODO(M0): go run ./server/cmd/qimeng"

server-test: ## 服务端全部测试
	@echo "TODO(M0): go test ./..."

web-dev: ## Web 开发服务器
	@echo "TODO(M0): npm --prefix web run dev"

web-test: ## Web 检查与测试
	@echo "TODO(M0): npm --prefix web run check"

docker-build: ## 构建双架构镜像（amd64+arm64，含 ffmpeg）
	@echo "TODO(M5): docker buildx build --platform linux/amd64,linux/arm64"

lint: ## 全部静态检查（Go vet + golangci-lint + TS）
	@echo "TODO(M0)"
