pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 中国内地访问 Google Maven 较慢时的备用镜像（官方源优先）
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "FitnessButler"
include(":app")
