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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lpdf.reader"
        minSdk = 33
        targetSdk = 35
        versionCode = 120
        versionName = "1.2.0"

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
    }

    lint {
        // AGP 8.7.3 自带的 NonNullableMutableLiveDataDetector 在 JDK 21 上会抛
        // IncompatibleClassChangeError（lint 侧缺陷，非工程代码问题），故关闭该条
        disable += "NullSafeMutableLiveData"
        // 不再让 lint 阻断 release 打包；需要时用 ./gradlew :app:lint 单独跑
        checkReleaseBuilds = false
        abortOnError = false
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
    implementation(libs.pdfbox.android)
    implementation(libs.okhttp)
    implementation("androidx.documentfile:documentfile:1.0.1")
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
