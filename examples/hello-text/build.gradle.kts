plugins {
    application
}

description = "Text rendering showcase using filament-java-text"

dependencies {
    implementation(project(":filament-java-core"))
    implementation(project(":filament-java-native"))
    implementation(project(":filament-java-lwjgl"))
    implementation(project(":filament-java-text"))
}

application {
    mainClass.set("dev.everydaythings.filament.examples.HelloText")
}
