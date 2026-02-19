plugins {
    application
}

description = "Object picking example using filament-java"

dependencies {
    implementation(project(":filament-java-core"))
    implementation(project(":filament-java-native"))
    implementation(project(":filament-java-lwjgl"))
}

application {
    mainClass.set("dev.everydaythings.filament.examples.HelloPicking")
}
