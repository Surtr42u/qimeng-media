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
# Kotlin 生成期枚举项改名（官方 --enum-name-mappings，线上值不变）：协议 sort 枚举含合法值
# "name"（DOMAIN_RULES §3 排序键），生成器直接产出枚举项 name 与 kotlin.Enum.name 冲突、无法编译。
# 仅改 Kotlin 标识符为 nameValue，@Json(name="name") 与请求线上值保持 "name"。
KOTLIN_ENUM_NAME_MAPPINGS := name=nameValue

# :sdk 工程侧构建脚本（android/sdk/sdk.gradle）：生成器自带 build.gradle 的 wrapper{} DSL 在
# Gradle 8 已移除（禁手改生成物），故由本步骤随生成一起落盘，settings.gradle.kts 经
# buildFileName 指向它。依赖面 = 生成源码的真实 import（moshi + okhttp3）。
# 注意：内容必须纯 ASCII——Windows 原生 make.exe 经环境变量传递时按本地代码页转码，
# 非 ASCII 字符会乱码且可能吞换行（中文说明写在 settings.gradle.kts，Gradle 按 UTF-8 读取）。
define SDK_GRADLE_FILE
// Build script for the generated :sdk module (written by `make sdk` / sdk-kotlin step; DO NOT
// hand-edit - edit SDK_GRADLE_FILE in the root Makefile instead). The generator's own
// build.gradle uses the Gradle-7-era wrapper{} DSL (removed in Gradle 8) and pins its own
// Kotlin 2.4.0 buildscript; see settings.gradle.kts (buildFileName wiring) for the rationale.
// Dependency set mirrors the real imports of the generated sources (moshi + okhttp3); versions
// come from gradle/libs.versions.toml of the main build.
plugins {
    id 'org.jetbrains.kotlin.jvm'
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// The generator ships tests based on the discontinued kotlintest 3.4.2 (2019); they are not part
// of this build - SDK behavior is locked by the protocol (api/openapi.yaml is the single source
// of truth) and by unit tests in the consuming modules. Empty test srcDirs avoid stray compilation.
sourceSets {
    test {
        kotlin.setSrcDirs([])
        java.setSrcDirs([])
    }
}

dependencies {
    // api so :core:network gets the same okhttp/moshi versions as the SDK (single-version rule);
    // okhttp AuthInterceptor lands in M4-1.
    api libs.kotlin.reflect
    api libs.moshi.kotlin
    api libs.moshi.adapters
    api libs.okhttp
}
endef
export SDK_GRADLE_FILE

# golangci-lint（配置在 server/.golangci.yml，模块边界 depguard 强制见 ARCHITECTURE §5）。
# 版本锁定 v2.13.1，与 .github/workflows/ci.yml 的 golangci-lint-action 一致。首次安装：
#   go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.13.1
GOLANGCI_LINT ?= $(GOBIN_DIR)/golangci-lint

.PHONY: help sdk sdk-validate sdk-go sdk-ts sdk-kotlin app-build app-test app-lint server-run server-test web-build web-dev web-test docker-build lint

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
			--additional-properties=packageName=media.qimeng.sdk \
			--enum-name-mappings $(KOTLIN_ENUM_NAME_MAPPINGS); \
	elif [ -x "../dev-tools/jdk17/jdk-17.0.20.1+1/bin/java.exe" ]; then \
		echo "    using bundled JDK (../dev-tools/jdk17)"; \
		JAVA_HOME="../dev-tools/jdk17/jdk-17.0.20.1+1" PATH="../dev-tools/jdk17/jdk-17.0.20.1+1/bin:$$PATH" \
		npx -y @openapitools/openapi-generator-cli generate -g kotlin \
			-i api/openapi.yaml -o android/sdk \
			--additional-properties=packageName=media.qimeng.sdk \
			--enum-name-mappings $(KOTLIN_ENUM_NAME_MAPPINGS); \
	else \
		echo "    Java not found (PATH or ../dev-tools/jdk17): Kotlin SDK generation SKIPPED"; \
	fi
	@# :sdk 工程侧构建脚本（Gradle 8 兼容；内容见文件头注释）
	@echo "$$SDK_GRADLE_FILE" > android/sdk/sdk.gradle
	@echo "    android/sdk/sdk.gradle written (engineering build script for :sdk)"

# Android 客户端（M4，ADR-0014 Compose 重建）：走 wrapper，禁止依赖本机全局 gradle。
# 前置：android/sdk 生成物存在（干净 checkout 先跑 make sdk；缺 :sdk 的报错一律重跑 make sdk，
# 禁止手改生成物）。命令行构建需 JAVA_HOME 指向 JDK 17+（本机=Android Studio jbr，见 HANDOVER_APP）。
app-build: ## Android Debug 构建（android/app/build/outputs/apk/debug/app-debug.apk）
	cd android && ./gradlew assembleDebug

# :core:model 是纯 JVM kotlin("jvm") 模块，测试任务叫 test（没有 testDebugUnitTest 变体），
# 只跑 testDebugUnitTest 覆盖不到它——故显式追加 :core:model:test（A-S1 补账）。
app-test: ## Android 单元测试（Android 模块 testDebugUnitTest + :core:model 纯 JVM test）
	cd android && ./gradlew testDebugUnitTest :core:model:test

app-lint: ## Android Lint（全部模块 lintDebug，error 即失败）
	cd android && ./gradlew lintDebug

server-run: ## 本地运行服务端（:8420；自定义配置直接 go run ./server/cmd/qimeng -config <yaml>）
	cd server && go run ./cmd/qimeng

server-test: ## 服务端全部测试（-race 由 CI 跑；本机 Windows 无 gcc 编译器）
	cd server && go test ./... -count=1

web-build: ## 构建 Web 前端产物（web/dist）——服务端 SPA 托管依赖此产物（默认 web.static_dir=../web/dist，见 server/internal/config）；未构建时服务端回退内嵌验收页，页面功能不完整但服务不挂
	npm --prefix web run build

web-dev: ## Web 开发服务器（vite dev）
	npm --prefix web run dev

web-test: ## Web 检查（tsc 类型检查 + oxlint；package.json 无独立 check script，组合 build+lint）
	@test -d web/node_modules || npm --prefix web install
	npm --prefix web run build
	npm --prefix web run lint

docker-build: ## 双架构镜像（amd64+arm64，含 ffmpeg）——未实现
	@echo "TODO(M5): image delivery not implemented yet; see PROJECT_PLAN M5 (docker buildx build --platform linux/amd64,linux/arm64)"

lint: ## 全部静态检查（openapi 协议 + Go gofmt/golangci-lint + TS）
	@echo "==> openapi spec lint (redocly; errors fail the build)"
	$(REDOCLY) lint api/openapi.yaml
	@echo "==> Go gofmt（格式不一致即失败）"
	@cd server && test -z "$$(gofmt -l .)" || (echo "gofmt required:" && gofmt -l . && exit 1)
	@echo "==> Go golangci-lint (server; config server/.golangci.yml)"
	cd server && "$(GOLANGCI_LINT)" run
	@echo "==> TS check (web)"
	npm --prefix web run build
	npm --prefix web run lint
