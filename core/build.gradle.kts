import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// Pure-Kotlin metronome model and transport. No UI, no platform APIs, so it
// compiles for the JVM (desktop + Android) and for Kotlin/Native desktops.
kotlin {
    // Java 17 bytecode, so the same classes also run on Android.
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    linuxX64()
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
