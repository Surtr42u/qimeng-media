// :core:data —— Repository 接口 + 实现（UI -> ViewModel -> Repository -> SDK 链路的中间层）。
// M4-0 空壳起步：首个 Repository（AuthRepository/ServerConfigDataSource）随 M4-1 落地。
// convention 插件（build-logic，NIA 范式）提供：android library + Kotlin Android + Java/Kotlin 17 +
// compileSdk/minSdk + Hilt/KSP（含 hilt 依赖）。
import com.google.devtools.ksp.gradle.KspExtension

plugins {
    alias(libs.plugins.qimeng.android.library)
    alias(libs.plugins.qimeng.android.hilt)
}

android {
    namespace = "media.qimeng.app.core.data"

    testOptions {
        // M4-4 行为队列单测（ViewEventQueueTest）在 JVM 走 android.jar stub：
        // 队列内的 android.util.Log 调用在 stub 上默认抛「not mocked」，此处放行为返回默认值。
        // 仅作用于本模块单元测试，不影响生产代码与其他模块。
        unitTests.isReturnDefaultValues = true
    }
}

// Room schema 导出（M4-4）：KSP 插件由 convention 内部 apply（本模块无类型安全 accessor），
// 取官方等价的 KSP arg 通道，产物 schemas/*.json 随版本入库（取舍记录见文件头 Room 依赖注释）。
// 注意（2026-09-13 S3 批，AGP 9.2.1）：此调用必须在 android{} 块外（project 层）——
// AGP 9 起 CommonExtension 继承 ExtensionAware，块内裸 configure<T> 会绑定到 android 扩展的
// 内部容器（报「Extension of type KspExtension does not exist」），语义与 AGP 8 时代等价、仅位置平移。
configure<KspExtension> {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:network"))

    // Flow/协程主源码直用（core:network 的同款依赖不传递到本模块编译类路径）
    implementation(libs.kotlinx.coroutines.android)

    // 客户端本地偏好（搜索历史/网格列数/缓存档位）：DataStore 白名单依赖，与 :core:network 共用同一版本收口
    implementation(libs.androidx.datastore.preferences)

    // Coil ImageLoader 全局单例组装（M4-6 C5）：磁盘缓存档位读自本模块 DataStore，
    // 组装必须与档位仓库同模块（依赖方向：core:data -> core:network 单向，network 无法反向依赖本模块）。
    // GIF 解码器（coil-gif，同家族白名单）。
    // coil-network-okhttp（2026-09-13 BUG-A 修复）：ImageLoader 显式装配 OkHttp 取图器
    // （自定义读超时口径），本模块需引用 OkHttpNetworkFetcherFactory → 编译期显式声明；
    // :app 侧经 :core:ui 的 api 传递进运行时 classpath，ServiceLoader 默认注册仍在（被
    // 显式组件先行覆盖，见 CoilModule 注释），此处 implementation 不改变对外依赖面。
    implementation(libs.coil.core)
    implementation(libs.coil.gif)
    // 视频帧解码器（coil-video，同家族白名单）：内置相册选择器（2026-09-25 拍板）网格直载
    // MediaStore 视频 content URI 出首帧缩略图（CoilModule 显式装配）。
    implementation(libs.coil.video)
    implementation(libs.coil.network.okhttp)

    // 上传队列（M4-5）：WorkManager 白名单依赖 + @HiltWorker（androidx.hilt 同族接线）。
    // 官方来源与版本论证见 libs.versions.toml 的 work 版本注释。
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // 行为上报离线队列（M4-4）：Room 白名单依赖（ADR-0014）。2.8 线起 room-ktx 已并入
    // room-runtime（挂起 DAO / 事务开箱即用），勿再单独引 room-ktx；版本论证见 libs.versions.toml。
    // schema 导出：Room 官方两条通道（Gradle 插件 / KSP arg）二选一，此处取 KSP arg——
    // 免新增 androidx.room Gradle 插件 classpath（AGP 版本协商成本），产物同为 schemas/*.json 随版本入库。
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // 回前台补传触发（M4-4）：ProcessLifecycleOwner ON_START 监听，版本与 lifecycle 同源收口。
    implementation(libs.androidx.lifecycle.process)

    // 上传响应/错误体解析（M4-5）：moshi 反射（SDK 生成物同款 KotlinJsonAdapterFactory，单版本原则）
    implementation(libs.moshi.kotlin)

    // 登录流程走真实生成 SDK + JDK HttpServer 打全链路（okhttp 仅为测试内构造客户端）
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
}
