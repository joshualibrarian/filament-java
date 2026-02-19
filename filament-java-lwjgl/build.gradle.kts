plugins {
    `java-library`
    `maven-publish`
}

description = "GLFW/LWJGL windowing integration for filament-java"

val lwjglVersion: String by project

val lwjglNatives: String = run {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    when {
        osName.contains("linux") && (osArch == "amd64" || osArch == "x86_64") -> "natives-linux"
        osName.contains("linux") && (osArch == "aarch64" || osArch == "arm64") -> "natives-linux-arm64"
        (osName.contains("mac") || osName.contains("darwin")) && (osArch == "aarch64" || osArch == "arm64") -> "natives-macos-arm64"
        (osName.contains("mac") || osName.contains("darwin")) -> "natives-macos"
        osName.contains("win") && (osArch == "amd64" || osArch == "x86_64") -> "natives-windows"
        osName.contains("win") && (osArch == "aarch64" || osArch == "arm64") -> "natives-windows-arm64"
        else -> throw GradleException("Unsupported platform: $osName / $osArch")
    }
}

dependencies {
    api(project(":filament-java-core"))

    api(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    api("org.lwjgl:lwjgl")
    api("org.lwjgl:lwjgl-glfw")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "filament-java-lwjgl"
        }
    }
}
