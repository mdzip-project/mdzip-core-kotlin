import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.dokka") version "2.0.0"
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("ru.vyarus.animalsniffer") version "2.0.1"
}

group = property("GROUP") as String
version = property("VERSION_NAME") as String

dependencies {
    // Only kotlin-stdlib at runtime (added by the Kotlin plugin).
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")

    // Checks the compiled library against the Android 5.0 (API 21) API surface.
    signature("net.sf.androidscents.signature:android-api-level-21:5.0.1_r2@signature")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // Compile against the Java 8 class library even though the build runs on a newer JDK.
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

animalsniffer {
    // Only the library must run on Android; tests run on the build JDK.
    sourceSets = listOf(project.sourceSets["main"])
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Lets the tests write sample archives for `mdz validate` (see README).
    System.getenv("MDZIP_OUT")?.let { environment("MDZIP_OUT", it) }
}

tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to "org.mdzip.core",
            "Implementation-Title" to "mdzip-core",
            "Implementation-Version" to project.version,
        )
    }
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = true))
    publishToMavenCentral()
    signAllPublications()
    // Coordinates (GROUP, POM_ARTIFACT_ID, VERSION_NAME) and the rest of the POM
    // (name, description, license, SCM, developer) come from gradle.properties.
}
