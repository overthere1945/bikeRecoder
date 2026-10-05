import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":core"))

    implementation(project(":third_party:brouter-util"))
    implementation(project(":third_party:brouter-codec"))
    implementation(project(":third_party:brouter-expressions"))
    implementation(project(":third_party:brouter-mapaccess"))
    implementation(project(":third_party:brouter-core"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

val segmentsDirPath: String = rootProject.layout.buildDirectory.dir("brouter-segments").get().asFile.path

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs BRouter engine smoke tests tagged \"integration\" (downloads real segment data)."

    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath

    useJUnitPlatform {
        includeTags("integration")
    }

    systemProperty("segmentsDir", segmentsDirPath)
}
