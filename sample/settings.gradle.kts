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

// A separate build from the plugin's. The root settings never include it, so the plugin build,
// its tests and Detekt do not see it. Build it with `./gradlew -p sample :app:installDebug`.
rootProject.name = "spock-sample"
include(":app")
