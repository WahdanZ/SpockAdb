import org.jetbrains.changelog.markdownToHTML

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.10.5"
    id("org.jetbrains.changelog") version "2.2.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
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

    compilerOptions {
        // Without this, Kotlin emits "compatibility bridge" overrides in classes that
        // implement platform Kotlin interfaces (e.g. ToolWindowFactory). Those bridges
        // `invokespecial` every default method the interface had at *compile* time, so a
        // plugin compiled against 251 and run on 231 dies with NoSuchMethodError on
        // ToolWindowFactory.manage / isApplicableAsync — in AdbDrawerViewer, the plugin's
        // entry point. Verified with Plugin Verifier against AI-231; see docs/COMPATIBILITY.md.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
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

        // The Plugin Verifier CLI. Without this declaration `verifyPlugin` only works when
        // the jar happens to already be in the Gradle cache, so it passes locally and on a
        // warm CI runner, then fails on a cold one with "executable not found".
        pluginVerifier()
    }

    implementation("org.jooq:joor:0.9.15")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.9")

    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("detekt-config.yml"))
    baseline = file("detekt-baseline.xml")
    // Type resolution is not wired up: it would require the full IntelliJ Platform
    // classpath and roughly triples analysis time for little extra signal here.
    ignoreFailures = false
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required = true
        xml.required = true
        sarif.required = false
        md.required = false
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

intellijPlatform {
    instrumentCode = true
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

    pluginVerification {
        // Problems that are understood and handled at runtime, each justified in the file.
        ignoredProblemsFile = file("verifier-ignored-problems.txt")

        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.NOT_DYNAMIC,
        )

        ides {
            // Oldest supported Android Studio (Hedgehog, build AI-231) — the sinceBuild floor.
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.AndroidStudio, "2023.1.1.28")
            // A mid-range Android Studio, to catch breakage between the two ends.
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.AndroidStudio, "2024.2.1.12")
            // Current stable Android Studio — the compile target.
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.AndroidStudio, "2025.1.1.14")
            // IntelliJ IDEA with the bundled Android plugin. The plugin no longer declares
            // com.intellij.modules.androidstudio, so IDEA is a supported target and must be
            // verified rather than assumed.
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2023.1.5")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
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

}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    version = pluginVersion
    groups.set(emptyList())
    repositoryUrl = "https://github.com/WahdanZ/SpockAdb"
}
