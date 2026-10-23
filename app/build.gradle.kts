import java.util.Properties as JavaProperties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 签名配置：
 * 密钥库文件与口令一律不提交仓库，按以下优先级读取：
 *   1. 环境变量 LPdf_KEYSTORE_PATH / LPdf_KEYSTORE_PASSWORD / LPdf_KEY_ALIAS / LPdf_KEY_PASSWORD
 *   2. 本地 keystore.properties（已在 .gitignore 中忽略）
 * 两者都缺失时 release 走未签名产出，CI 或本地首次构建不会因此失败。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = JavaProperties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun signingValue(envKey: String, propKey: String): String? =
    (System.getenv(envKey) as String?)?.takeIf { it.isNotEmpty() }
        ?: keystoreProps.getProperty(propKey)?.takeIf { it.isNotEmpty() }

val releaseStorePath = signingValue("LPdf_KEYSTORE_PATH", "storeFile")
val releaseStorePassword = signingValue("LPdf_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("LPdf_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("LPdf_KEY_PASSWORD", "keyPassword")

val hasReleaseSigning = releaseStorePath != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null &&
    rootProject.file(releaseStorePath).exists()

android {
    namespace = "com.lpdf.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lpdf.reader"
        minSdk = 33
        targetSdk = 35
        versionCode = 182
        versionName = "1.8.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有密钥则签名，无密钥则产出未签名包（保持原行为，不打断构建）
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // 纯 JVM 单测（不带 @RunWith(RobolectricTestRunner)）里 android.util.Log 默认
        // 抛 "not mocked"。返回默认值让日志调用变成 no-op，测试只断言返回值。
        unitTests.isReturnDefaultValues = true
    }

    // 测试 JVM 必须跑在 JDK 21 及以上。
    // 依赖 com.kyant:backdrop 打成 class 65（Java 21），而 Gradle 默认 JVM 落到
    // JDK 20 时，Robolectric 测试会以 UnsupportedClassVersionError 全红 ——
    // 报错看着像 "backdrop 类坏了"，实际只是测试 JVM 版本太低。
    tasks.withType<Test>().configureEach {
        javaLauncher.set(
            javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        )
    }

    lint {
        // ---------- 已知在本工程工具链上会崩溃的检测器 ----------
        // 这两条都是 lint / Compose lint 自身的缺陷（detector 与 Kotlin 分析 API 版本不匹配，
        // 抛 IncompatibleClassChangeError），不是工程代码问题。关掉它们是为了让**其余**
        // 几百条检查能真正跑起来 —— 比起「全都关掉」，这是损失最小的选择。
        //
        // 1) NullSafeMutableLiveData：AGP 8.7.3 自带检测器在 JDK 21 上抛 IncompatibleClassChangeError
        disable += "NullSafeMutableLiveData"
        // 2) Compose runtime lint 的三个检测器：
        //    Compose BOM 2025.04.01 的 lint 是按 Kotlin 1.x 分析 API 编译的
        //    （KaFunctionCall / KaSimpleVariableAccessCall 当时是**接口**），
        //    到 Kotlin 2.1 这些类型变成了类，加载时直接 IncompatibleClassChangeError。
        //    受影响的是所有依赖 KaCallInfoKt / KaSessionProvider 的实现：
        //      - AutoboxingStateCreation（KaCallInfoKt）
        //      - RememberInComposition（KaCallInfoKt，被 FrequentlyChangingValue 复用）
        //      - ComposableStateFlowValue（KaSessionProvider，issue id 为 StateFlowValueCalledInComposition）
        //      - MutableCollectionMutableState（KaSessionProvider）
        //    这些检测器的功能我们可以用代码评审替代，代价可接受；
        //    真正会漏的是「无意义装箱 / 误用 mutableStateOf(mutableListOf)」这类风格问题，不影响行为。
        //    根本修复要等 Compose 发布适配 Kotlin 2.1 的 lint 包，届时删掉这四行即可。
        disable += "AutoboxingStateCreation"
        disable += "RememberInComposition"
        disable += "FrequentlyChangingValue"
        disable += "MutableCollectionMutableState"
        // 注意 ComposableStateFlowValueDetector 的 issue id 是 StateFlowValueCalledInComposition，
        // 不是检测器类名 —— 写类名会被 lint 报 UnknownIssueId 且**不生效**。
        disable += "StateFlowValueCalledInComposition"

        // ---------- 已知可容忍的问题（有意识放行） ----------
        // 项目未做国际化（v2.0.0 才做多语言），中文硬编码是既定状态，
        // 逐条报出来只会淹没真正的问题；国际化立项时一并移除这条。
        disable += "HardcodedText"

        // 注意：这里**不能**设 abortOnError = false。
        // 那等于「Lint 跑完也不管结果」，与本条检查的初衷相反。
        // Lint 由 CI 单独跑（见 .github/workflows/build.yml 的 lint job），
        // 日常 assemble 不再触发，避免拖慢装机流程。
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.pdfium.android)
    // PDFBox 自 v1.8.0 起重新引入，承担一切「对象级改写」（元数据、页面操作、后续工具箱）。
    // v1.7.1 曾整体删除，当时量到 -7.26 MB；其中约 4 MB 其实来自传递依赖 BouncyCastle
    // 的抗量子密码参数表（SIKE / PICNIC），我们完全用不上 —— 所以下面必须 exclude。
    // 只做读写对象 / 页面操作时不需要 BC；加密与签名（v1.11.0）再单独引入，且优先只引 bcprov。
    //
    // pdfium 与 PDFBox 的分工（见 PROGRESS.md 附录 A.7）：
    //   pdfium  → 渲染、取尺寸、取文本、取书签（只读链路）
    //   PDFBox  → 对象改写，落盘只走 PDDocument.save()
    // 反例：PDFBox 改完再让 pdfium saveAsCopy，会把内存里的旧快照整体写出、覆盖改动。
    implementation(libs.pdfbox.android) {
        exclude(group = "org.bouncycastle")
    }
    implementation(libs.okhttp)
    implementation(libs.androidx.documentfile)
    implementation(libs.liquid.glass)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
