pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "bikeRecoder"
include(":app")
include(":core")
include(":routing-brouter")

include(":third_party:brouter-util")
project(":third_party:brouter-util").projectDir = file("third_party/brouter/brouter-util")
include(":third_party:brouter-codec")
project(":third_party:brouter-codec").projectDir = file("third_party/brouter/brouter-codec")
include(":third_party:brouter-expressions")
project(":third_party:brouter-expressions").projectDir = file("third_party/brouter/brouter-expressions")
include(":third_party:brouter-mapaccess")
project(":third_party:brouter-mapaccess").projectDir = file("third_party/brouter/brouter-mapaccess")
include(":third_party:brouter-core")
project(":third_party:brouter-core").projectDir = file("third_party/brouter/brouter-core")
