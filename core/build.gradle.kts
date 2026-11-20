plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// Pure-Kotlin metronome model and transport. No UI, no platform APIs, so it
// compiles for the JVM (desktop + Android) and for Kotlin/Native desktops.
kotlin {
    jvm()
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
