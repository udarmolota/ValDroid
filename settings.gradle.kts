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
// Native Unity player (experimental). Its Unity binaries come from
// tools/unity-native/prepare_unity_module.sh; without them the module is left out of the build.
if (file("unity/prepared").isDirectory) {
    include(":unity")
}
 