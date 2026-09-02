pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// ---------------------------------------------------------------------------
// 可选：国内 Maven 镜像加速。
//
// 默认关闭——镜像地址在 GitHub Actions / 海外网络下不可达或极慢，
// 因此仓库中**不内置**任何第三方镜像源，保证任何人 clone 即用。
//
// 需要加速时二选一：
//   export DNSOVERRIDE_MAVEN_MIRROR=true
//   ./gradlew -PDNSOVERRIDE_MAVEN_MIRROR=true assembleDebug
// ---------------------------------------------------------------------------
val useMavenMirror =
    System.getenv("DNSOVERRIDE_MAVEN_MIRROR")?.toBoolean() == true ||
        startParameter.projectProperties["DNSOVERRIDE_MAVEN_MIRROR"]?.toBoolean() == true

if (useMavenMirror) {
    pluginManagement.repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
    dependencyResolutionManagement.repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
    }
}

rootProject.name = "dns-override"
include(":app")
