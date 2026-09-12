# ===== Moshi 反射序列化（KotlinJsonAdapterFactory，无 codegen）=====
# 依据: Moshi README「R8/ProGuard」——反射序列化的类必须由使用方 keep；
# 本工程 SDK 数据类不挂 @JsonClass（仅部分嵌套 enum 挂），不能按注解收敛，按包 keep。
-keep class media.qimeng.sdk.models.** { *; }

# SDK 包外唯一走 KotlinJsonAdapterFactory 的模型：DTO 嵌套于同文件顶层 object
# UploadApiBodies（AssetUploader.kt L158；L183 UploadResultDto / L186 ApiErrorDto），
# 编译类名 UploadApiBodies$UploadResultDto / $ApiErrorDto。
-keep class media.qimeng.app.core.data.upload.UploadApiBodies$* { *; }

# ===== 以下由依赖制品内嵌 consumer 规则自动覆盖（本机缓存制品核实，勿重复手写）=====
# moshi-1.15.2.jar META-INF/proguard/moshi.pro / kotlin-reflect-2.3.21.jar r8-from-1.6.0/kotlin-reflect.pro
# （含 kotlin.Metadata keep）/ hilt-android-2.58.aar / hilt-work-1.3.0.aar / room-2.8.4
