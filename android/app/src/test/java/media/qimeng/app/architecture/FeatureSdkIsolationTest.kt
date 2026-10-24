package media.qimeng.app.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * feature 模块源依赖守卫（2026-10-24 SDK 隔离批，任务书 Q2）：feature → core 单向、
 * UI → ViewModel → Repository → SDK 链路里生成 SDK 类型只允许出现在 core:data 映射
 * 边缘。本测试扫描全部 feature 模块生产源码（src/main），出现 media.qimeng.sdk 引用
 * 即失败——把各 feature build.gradle 的「零 :sdk 引用」注释变成可执行门禁，防回流。
 *
 * 边界声明（与 Web 端 AST 门禁同口径）：只锁定直接 import/全限定引用的语法边界，
 * 不证明传递类路径纯度；feature 模块对 :sdk 的 Gradle 依赖约束由各自 build.gradle
 * 维护。文本扫描足够覆盖 Kotlin 源码引用 SDK 的全部形态（import 或全限定名），
 * 不引入 Kotlin 编译器解析依赖。
 */
class FeatureSdkIsolationTest {

    @Test
    fun `feature 生产源码不得引用生成 SDK`() {
        val featureRoot = locateFeatureRoot()
        val modules = featureRoot.listFiles { file -> file.isDirectory }
        assertTrue("feature 根目录下未发现任何模块：$featureRoot", !modules.isNullOrEmpty())
        val violations = modules!!
            .sortedBy { it.name }
            .flatMap { module ->
                scanMainSources(File(module, "src/main"))
                    .map { violation -> "${module.name}/${violation}" }
            }
        assertTrue(
            "feature 生产源码出现 SDK 引用（应改为消费 core:model 域模型，映射收口 core:data）：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /** 只扫 .kt/.java 生产源码，逐行匹配 import 声明或全限定引用两种形态 */
    private fun scanMainSources(dir: File): List<String> =
        dir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) ->
                        line.contains("media.qimeng.sdk.") || line.contains("import media.qimeng.sdk")
                    }
                    .map { (index, _) -> "${file.relativeTo(dir.parentFile.parentFile)}:${index + 1}" }
            }
            .toList()

    /** 单测工作目录是模块目录（android/app），向上找含 feature/manage 的 android 根 */
    private fun locateFeatureRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".", ".").absoluteFile
        repeat(5) {
            val root = requireNotNull(dir) { "user.dir 解析失败" }
            val candidate = File(root, "feature")
            if (candidate.isDirectory && File(candidate, "manage").isDirectory) return candidate
            dir = root.parentFile
        }
        error("未能定位 android/feature 根（user.dir=${System.getProperty("user.dir")}）")
    }
}
