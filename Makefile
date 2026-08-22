# qimeng-media 一键命令
# 约定：所有常用操作必须一条命令完成，禁止让 AI/用户记忆长命令。
# M0 期间逐个补全实现，未实现的命令先输出"未实现，见 PROJECT_PLAN M0"。
# 注意：运行时输出一律 ASCII——Windows 原生 make.exe 向控制台写入时按本地代码页
# 转码，UTF-8 中文 echo 会乱码；中文只写在注释里（文件本身是 UTF-8，无此问题）。

# Windows Git Bash 下 Go 不在默认 PATH，统一补上（其他平台该目录不存在，无副作用）
export PATH := /c/Program Files/Go/bin:$(PATH)

# go install 的默认安装目录；oapi-codegen 不存在时 sdk-go 会自动安装（当前锁定 v2.8.0）
GOBIN_DIR ?= $(HOME)/go/bin

# 协议校验工具与生成器版本（协议宪法 api/openapi.yaml 的三端生成链，见 AI_README_FIRST「协议先行」）
REDOCLY := npx -y @redocly/cli
OAPI_CODEGEN_VERSION := v2.8.0

.PHONY: help sdk sdk-validate sdk-go sdk-ts sdk-kotlin server-run server-test web-dev web-test docker-build lint

help: ## 显示全部命令
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

sdk: sdk-validate sdk-go sdk-ts sdk-kotlin ## 从 api/openapi.yaml 生成三端 SDK（Go 接口层 + TS + Kotlin）
	@echo "sdk: all done (validate -> go -> ts -> kotlin)"

sdk-validate:
	@echo "==> [1/4] validate api/openapi.yaml (redocly; struct errors fail the build)"
	@if $(REDOCLY) lint api/openapi.yaml > /tmp/redocly-sdk.log 2>&1; then \
		echo "    spec OK"; \
	else \
		echo "    spec has errors (full detail: make lint):"; \
		grep -E "Error was generated|Validation failed" /tmp/redocly-sdk.log | head -8; \
		exit 1; \
	fi

sdk-go:
	@echo "==> [2/4] generate Go server interface (oapi-codegen $(OAPI_CODEGEN_VERSION) -> server/internal/httpapi/gen/)"
	@if [ ! -x "$(GOBIN_DIR)/oapi-codegen" ] && [ ! -x "$(GOBIN_DIR)/oapi-codegen.exe" ]; then \
		echo "    first run: installing oapi-codegen..."; \
		go install github.com/oapi-codegen/oapi-codegen/v2/cmd/oapi-codegen@$(OAPI_CODEGEN_VERSION); \
	fi
	cd server && "$(GOBIN_DIR)/oapi-codegen" -config internal/httpapi/gen/oapi-codegen.yaml ../api/openapi.yaml
	cd server && go build ./... && echo "    go build ./... OK"

sdk-ts:
	@echo "==> [3/4] generate TS client (@hey-api/openapi-ts 0.99.0 -> web/src/api/generated/)"
	@test -d web/node_modules || npm --prefix web install
	cd web && npx openapi-ts -f src/api/openapi-ts.config.ts

sdk-kotlin:
	@echo "==> [4/4] generate Kotlin SDK (openapi-generator)"
	@# Java 优先取 PATH；否则回退仓库旁的免安装 JDK（升级 JDK 时同步改此路径，见 ../dev-tools/TOOLCHAIN_GUIDE.md）
	@if command -v java >/dev/null 2>&1; then \
		npx -y @openapitools/openapi-generator-cli generate -g kotlin \
			-i api/openapi.yaml -o android/sdk \
			--additional-properties=packageName=media.qimeng.sdk; \
	elif [ -x "../dev-tools/jdk17/jdk-17.0.20.1+1/bin/java.exe" ]; then \
		echo "    using bundled JDK (../dev-tools/jdk17)"; \
		JAVA_HOME="../dev-tools/jdk17/jdk-17.0.20.1+1" PATH="../dev-tools/jdk17/jdk-17.0.20.1+1/bin:$$PATH" \
		npx -y @openapitools/openapi-generator-cli generate -g kotlin \
			-i api/openapi.yaml -o android/sdk \
			--additional-properties=packageName=media.qimeng.sdk; \
	else \
		echo "    Java not found (PATH or ../dev-tools/jdk17): Kotlin SDK generation SKIPPED"; \
	fi

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

lint: ## 全部静态检查（openapi 协议 + Go vet + golangci-lint + TS）
	@echo "==> openapi spec lint (redocly; errors fail the build)"
	$(REDOCLY) lint api/openapi.yaml
	@echo "==> Go vet / golangci-lint / TS: TODO(M0)"
