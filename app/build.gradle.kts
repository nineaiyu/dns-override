import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// 签名配置：三级回退，保证「clone 即可构建」
//
//  1. keystore.properties（本地，已在 .gitignore 中）
//  2. 环境变量 / -P 参数（CI，见 .github/workflows/release.yml）
//  3. debug keystore（无配置时回退，仅用于本地构建，切勿用于分发）
// ---------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

/** 按 keystore.properties → 环境变量 → Gradle 属性 的顺序取值。 */
fun secretOf(propKey: String): String? =
    keystoreProps.getProperty(propKey)
        ?: System.getenv(propKey)
        ?: (project.findProperty(propKey) as String?)

android {
    namespace = "com.dnsoverride.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dnsoverride.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"

        // 应用只内置中文文案，裁剪其余语言资源以减小体积
        resourceConfigurations += listOf("zh")

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = secretOf("DNSOVERRIDE_STORE_FILE")
            val storePwd = secretOf("DNSOVERRIDE_STORE_PASSWORD")
            val keyAliasValue = secretOf("DNSOVERRIDE_KEY_ALIAS")
            val keyPwd = secretOf("DNSOVERRIDE_KEY_PASSWORD")

            if (storeFilePath != null && storePwd != null && keyAliasValue != null && keyPwd != null) {
                storeFile = file(storeFilePath)
                storePassword = storePwd
                keyAlias = keyAliasValue
                keyPassword = keyPwd
            } else {
                // 无自定义 keystore 时用 debug keystore 给 release 签名，保证开箱即用。
                // 该签名**不可**用于公开发布，仅供本地验证。
                logger.lifecycle("未找到签名配置，release 将回退使用 debug keystore（仅供本地验证）")
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                storeFile = rootProject.file("../.android/debug.keystore")
                    .takeIf { it.exists() }
                    ?: file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
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
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // debug 默认用 debug keystore，无需额外配置
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    lint {
        // 策略：历史遗留问题记录在 lint-baseline.xml 中（逐步偿还），
        // 任何**新增**的 error 都会让构建失败，防止无障碍 / i18n / 正确性劣化。
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        // 依赖版本更新交给 Dependabot，lint 不重复告警
        disable += setOf("GradleDependency")
        // 生成 HTML 报告便于人工排查
        htmlReport = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
