import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library") version "8.10.1"
    id("org.jetbrains.kotlin.android") version "2.1.21"
}

android {
    namespace = "org.mdzip.core.androidtest"
    compileSdk = 35

    defaultConfig {
        // The oldest Android version the library supports (5.0).
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // The spec fixtures, read on the device as test assets (the same files the JVM tests use).
    sourceSets["androidTest"].assets.srcDir("../src/test/resources/fixtures")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    // Substituted with the library in the parent folder by includeBuild in settings.gradle.kts.
    implementation("org.mdzip:mdzip-core:0.1.0")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
