plugins {
    `java-library`
    `maven-publish`
}

description = "MSDF text rendering for filament-java"

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
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-freetype")
    implementation("org.lwjgl:lwjgl-msdfgen")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-freetype::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-msdfgen::$lwjglNatives")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "filament-java-text"
        }
    }
}
