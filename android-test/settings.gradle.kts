// Runs the library's checks on Android devices and emulators. See README.md in this folder.
// This is a separate build so the library itself stays a plain Kotlin/JVM project.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mdzip-core-android-test"

// Builds org.mdzip:mdzip-core from the parent folder, so the tests always use the current code.
includeBuild("..")
