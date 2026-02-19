plugins {
    application
}

description = "Hello Triangle example using filament-java with GLFW/LWJGL"

dependencies {
    implementation(project(":filament-java-core"))
    implementation(project(":filament-java-native"))
    implementation(project(":filament-java-lwjgl"))
}

application {
    mainClass.set("dev.everydaythings.filament.examples.HelloTriangle")
}

tasks.named<JavaExec>("run") {
    // Add the native library build directory to java.library.path
    val nativeBuildDir = project(":filament-java-native").layout.buildDirectory.dir("cmake")
    jvmArgs("-Djava.library.path=${nativeBuildDir.get().asFile.absolutePath}")
}
