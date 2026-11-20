import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// The release key never lives in the repository. It is read from Gradle
// properties (for example ~/.gradle/gradle.properties) or, in CI, from
// environment variables of the same names.
fun signingValue(name: String): String? =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull?.takeIf { it.isNotBlank() }

val keystoreFile = signingValue("GLICK_KEYSTORE_FILE")

android {
    namespace = "glick.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.traxrobustus.glick"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("glick.versionCode").get().toInt()
        versionName = providers.gradleProperty("glick.version").get()
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = signingValue("GLICK_KEYSTORE_PASSWORD")
                keyAlias = signingValue("GLICK_KEY_ALIAS")
                keyPassword = signingValue("GLICK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Without a release key the build still produces an installable APK,
            // signed with the debug key; it cannot be uploaded to Google Play.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":app"))
    implementation(libs.androidx.activity.compose)
}
