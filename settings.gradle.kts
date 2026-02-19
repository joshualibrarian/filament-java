pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "filament-java"

include("filament-java-core")
include("filament-java-native")
include("filament-java-lwjgl")
include("filament-java-gltfio")
include("examples:hello-triangle")
include("examples:hello-gltf")
include("examples:hello-camera")
include("examples:hello-picking")
