pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "ValDroid"
include(":app")
// Native Unity player (experimental). Built only when asked for: the Gradle property nativeEngine=true
// (-PnativeEngine=true, ORG_GRADLE_PROJECT_nativeEngine=true on CI) or nativeEngine=true in this
// machine's local.properties. Its Unity binaries come from tools/unity-native/prepare_unity_module.sh
// into the git-ignored unity/prepared; without them the module is left out too.
val nativeEngine = providers.gradleProperty("nativeEngine").orNull
    ?: file("local.properties").takeIf { it.isFile }?.let { f ->
        java.util.Properties().apply { f.inputStream().use { load(it) } }.getProperty("nativeEngine")
    }
if (nativeEngine == "true" && file("unity/prepared").isDirectory) {
    include(":unity")
}
 