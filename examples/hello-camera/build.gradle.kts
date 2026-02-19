plugins {
    application
}

description = "Interactive orbit camera example using filament-java"

dependencies {
    implementation(project(":filament-java-core"))
    implementation(project(":filament-java-native"))
    implementation(project(":filament-java-lwjgl"))
    implementation(project(":filament-java-gltfio"))
}

application {
    mainClass.set("dev.everydaythings.filament.examples.HelloCamera")
}
