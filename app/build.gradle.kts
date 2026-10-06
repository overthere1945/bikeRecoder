plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

val verMajor = 1
val verMinor = 0
val verPatch = 0

val secretsFile = rootProject.file("secrets.json")
val kakaoRestApiKey: String = if (secretsFile.exists()) {
    @Suppress("UNCHECKED_CAST")
    val parsed = groovy.json.JsonSlurper().parse(secretsFile) as Map<String, Any?>
    (parsed["kakaoRestApiKey"] as? String) ?: ""
} else {
    ""
}

room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.cowork.bikerecoder"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cowork.bikerecoder"
        minSdk = 30
        targetSdk = 37
        versionCode = verMajor * 10000 + verMinor * 100 + verPatch
        versionName = "$verMajor.$verMinor.$verPatch"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "KAKAO_REST_API_KEY", "\"$kakaoRestApiKey\"")
    }

    buildTypes {
        debug {
            versionNameSuffix = "-dev"
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        // The navigation scenario fixtures (GeoJSON routes + GPX tracks) are shared with the JVM tests.
        getByName("test").resources.directories.add("src/androidTest/assets")
    }
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":routing-brouter"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.maplibre)
    implementation(libs.play.services.location)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.mockwebserver.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
