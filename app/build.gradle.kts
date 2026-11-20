import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm("desktop")
    android {
        namespace = "glick.ui"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
        }
        named("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "glick.MainKt"
        // A concurrent collector keeps garbage-collection pauses well under one
        // audio buffer, so drawing the UI cannot make the click stutter.
        jvmArgs += listOf("-XX:+UseZGC", "-XX:+IgnoreUnrecognizedVMOptions", "-XX:+ZGenerational")
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi)
            packageName = "glick"
            packageVersion = providers.gradleProperty("glick.version").get()
            description = "Metronome with polyrhythms, subdivisions and practice timers"
            vendor = "traxrobustus"
            licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
            modules("java.desktop")

            val icons = rootProject.layout.projectDirectory.dir("packaging/icons")
            linux {
                iconFile.set(icons.file("glick.png"))
                shortcut = true
                menuGroup = "AudioVideo"
                appCategory = "sound"
                debMaintainer = "traxrobustus@users.noreply.github.com"
            }
            windows {
                iconFile.set(icons.file("glick.ico"))
                shortcut = true
                menu = true
                menuGroup = "glick"
                perUserInstall = true
                dirChooser = true
                // Fixed so that a newer installer upgrades an older install in place.
                upgradeUuid = "6f2c1f4e-8a3b-4d2a-9c57-0b7e5a1d4c3f"
            }
        }
    }
}
