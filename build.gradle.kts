import org.jetbrains.changelog.markdownToHTML

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.10.5"
    id("org.jetbrains.changelog") version "2.2.1"
}

val pluginGroup: String by project
val pluginName: String by project
val pluginVersion: String by project
val pluginSinceBuild: String by project
val androidStudioVersion: String by project

group = pluginGroup
version = pluginVersion

kotlin {
    jvmToolchain(17)
}

// Configure project's dependencies
repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        androidStudio(androidStudioVersion)

        // org.jetbrains.android is bundled with Android Studio
        bundledPlugin("org.jetbrains.android")

        // Other bundled plugins
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.gradle")

        instrumentationTools()
        pluginVerifier()
        zipSigner()
    }

    implementation("org.jooq:joor:0.9.15")

    testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
    testImplementation("io.mockk:mockk:1.13.9")
}

intellijPlatform {
    instrumentCode = false
    buildSearchableOptions = false

    pluginConfiguration {
        name = pluginName
        version = pluginVersion

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        changeNotes = providers.provider {
            with(changelog) {
                renderItem(
                    getOrNull(pluginVersion) ?: getLatest(),
                    org.jetbrains.changelog.Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = provider { null }   // open-ended: supports all future builds
        }

        vendor {
            name = "Spock Adb"
            email = "ahmed.wahdan@outlook.com"
            url = "https://github.com/WahdanZ"
        }
    }

    signing {
        certificateChain = environment("CERTIFICATE_CHAIN")
        privateKey = environment("PRIVATE_KEY")
        password = environment("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = environment("PUBLISH_TOKEN")
        channels = properties("pluginVersion").map {
            listOf(it.split('-').getOrElse(1) { "default" }.split('.').first())
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    version = pluginVersion
    groups.set(emptyList())
    repositoryUrl = "https://github.com/WahdanZ/SpockAdb"
}
