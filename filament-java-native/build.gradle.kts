plugins {
    `java-library`
    `maven-publish`
}

description = "JNI C++ bridge for filament-java, built with CMake"

dependencies {
    api(project(":filament-java-core"))
}

// --- CMake integration ---

val osName: String = System.getProperty("os.name").lowercase()
val osArch: String = System.getProperty("os.arch").lowercase()

val nativePlatform: String = when {
    osName.contains("linux") -> "linux"
    osName.contains("mac") || osName.contains("darwin") -> "macos"
    osName.contains("win") -> "windows"
    else -> "unknown"
}

val nativeArch: String = when {
    osArch.contains("aarch64") || osArch.contains("arm64") -> "arm64"
    osArch.contains("amd64") || osArch.contains("x86_64") -> "x64"
    else -> osArch
}

val cmakeBuildDir = layout.buildDirectory.dir("cmake")
val cmakeSourceDir = layout.projectDirectory.dir("src/main/cpp")
val nativeLibDir = layout.buildDirectory.dir("natives/$nativePlatform-$nativeArch")

val filamentSdkDir = rootProject.layout.projectDirectory.dir("filament-sdk").asFile.absolutePath
val hasSdk = file(filamentSdkDir).exists()

val cmakeConfigure by tasks.registering(Exec::class) {
    group = "native"
    description = "Configure the CMake build for the JNI native library"
    workingDir(cmakeBuildDir)
    doFirst { cmakeBuildDir.get().asFile.mkdirs() }
    commandLine(
        "cmake",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DFILAMENT_SDK_DIR=$filamentSdkDir",
        cmakeSourceDir.asFile.absolutePath
    )
    enabled = hasSdk
}

val cmakeBuild by tasks.registering(Exec::class) {
    group = "native"
    description = "Build the JNI native library using CMake"
    dependsOn(cmakeConfigure)
    workingDir(cmakeBuildDir)
    commandLine("cmake", "--build", ".", "--config", "Release", "--parallel")
    enabled = hasSdk
}

tasks.named<Jar>("jar") {
    dependsOn(cmakeBuild)
    from(nativeLibDir) {
        into("natives/$nativePlatform-$nativeArch")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "filament-java-native"
        }
    }
}
