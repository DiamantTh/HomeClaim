import org.gradle.api.initialization.resolve.RepositoriesMode

rootProject.name = "HomeClaim"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://jitpack.io")
        maven("https://maven.enginehub.org/repo/") // WorldEdit/FAWE
    }
    versionCatalogs {
        create("libs")
    }
}

include(
    "homeclaim-core",
    "homeclaim-api-client",
    "homeclaim-api",
    "homeclaim-ux",
    "homeclaim-webux",
    "homeclaim-liftlink",
    "homeclaim-platform-paper"
)
