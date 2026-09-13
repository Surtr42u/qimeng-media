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

.PHONY: help sdk sdk-validate sdk-go sdk-ts sdk-kotlin sdk-lock app-build app-test app-lint server-run server-test server-android-arm64 server-android-amd64 web-build web-dev web-test docker-build lint

help: ## 显示全部命令
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

sdk: sdk-validate sdk-go sdk-ts sdk-kotlin ## 从 api/openapi.yaml 生成三端 SDK（Go 接口层 + TS + Kotlin）
	@echo "sdk: all done (validate -> go -> ts -> kotlin)"
	@# sdk-lock 放 recipe 末尾调用而非并列 prerequisite：make -j 下同级
	@# prerequisite 执行顺序不保证，锁必须等三端生成物全部就绪后再算。
	@$(MAKE) --no-print-directory sdk-lock

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

# sdk-lock：三端 SDK 生成物指纹锁（#10 清欠，api/sdk.lock）。重算算法：
# 逐文件 tr -d '\r'（LF 归一化）后 sha256，按相对路径排序输出 `<hash>  <path>`。
# 为什么 LF 归一化：Kotlin 生成器是 Java 系工具，Windows 上可能产出 CRLF
# 行尾，与本仓库 ubuntu CI 的重算结果逐字节比对会假阳性；归一化后指纹只
# 反映内容、不反映行尾。
# 为什么排除：android/sdk/.openapi-generator/ 是生成器运行元数据（含机器/
# 时间相关字段，逐次生成不稳定）；android/sdk/build/ 是 Gradle 构建产物，
# 不是生成物源码。两者都不代表"生成物内容"。
# 与 ADR-0009 的关系：指纹入库 != 产物入库——生成物仍不入库（.gitignore），
# api/sdk.lock 只记录"与当前 api/openapi.yaml 对应的三端生成物指纹"，用于
# 在 CI 证明重新生成的结果与已提交锁一致（生成器版本漂移或协议改动未
# 同步即在此拦截，见 ci.yml sdk-chain）。
# 同步责任（铁律 1）：协议侧改动（openapi.yaml）或 make sdk 重新生成后，
# 必须同 commit 更新 api/sdk.lock。
sdk-lock: ## 重算三端 SDK 生成物指纹并写入 api/sdk.lock
	@{ \
		find server/internal/httpapi/gen -maxdepth 1 -type f -name '*.gen.go'; \
		find web/src/api/generated -type f \( -name '*.js' -o -name '*.ts' \); \
		find android/sdk -type f -not -path '*/.openapi-generator/*' -not -path '*/build/*' -not -path '*/src/test/*'; \
	} | LC_ALL=C sort \
	| while IFS= read -r f; do \
		printf '%s  %s\n' "$$(tr -d '\r' < "$$f" | sha256sum | cut -d' ' -f1)" "$$f"; \
	done > api/sdk.lock
	@echo "sdk-lock: api/sdk.lock written ($$(wc -l < api/sdk.lock) entries)"
	@# src/test 排除依据（2026-09-13）：生成器对 *Test.kt 的输出跨平台不稳定——CI/Linux 与
	@# Windows 对同一 yaml 各自确定但互不相同（CI 34762202974 实证：18 处漂移全部落在生成
	@# 测试文件，主源码/Go/TS 零漂移），而锁的守护目标是「被三端消费的 API 面」；生成测试
	@# 本身的回归由 CI Android job 的 test 步骤兜底。

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

# Android 服务端交叉编译（M6 单机形态，ADR-0015）。产物投放口径：
#   arm64-v8a → 真机：Termux 形态投放 $HOME/.qimeng/bin/qimeng-server（任务T T2）；
#               App 内嵌形态改名 libqimeng.so 进 jniLibs/arm64-v8a/（任务T T6）。
#   x86_64    → 仅模拟器（x86_64 系统镜像）shell 域验证，deploy/emulator-verify/ 消费。
# arm64 纯静态零 cgo（POC 实证）；amd64 是 Go 硬限制必须 cgo 外链（android/amd64
# requires external linking），借 NDK clang 交叉链——cgo 代码量为零（net/os.user 外链），
# modernc sqlite 仍是纯 Go，见 ../m6-poc/POC-RESULT.md 步骤②.2。
ANDROID_NDK_HOME ?= <AndroidSdk>/ndk/28.2.13676358
# 注意 windows-x86_64/.cmd 是 Windows 宿主三元组：非 Windows 宿主跑 server-android-amd64
# 需按本机 NDK prebuilt 目录改写（arm64 target 不依赖 NDK，跨宿主无此问题）。
NDK_X64_CLANG := $(ANDROID_NDK_HOME)/toolchains/llvm/prebuilt/windows-x86_64/bin/x86_64-linux-android35-clang.cmd

server-android-arm64: ## 交叉编译 Android arm64 服务端（真机投放；build/android/arm64-v8a/qimeng-server）
	cd server && GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -o ../build/android/arm64-v8a/qimeng-server ./cmd/qimeng

server-android-amd64: ## 交叉编译 Android x86_64 服务端（模拟器验证专用；build/android/x86_64/qimeng-server）
	cd server && GOOS=android GOARCH=amd64 CGO_ENABLED=1 CC="$(NDK_X64_CLANG)" go build -o ../build/android/x86_64/qimeng-server ./cmd/qimeng

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
