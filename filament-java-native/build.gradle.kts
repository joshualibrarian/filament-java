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

// Map Gradle arch names to Filament SDK lib directory names
val filamentArch: String = when (nativeArch) {
    "arm64" -> "arm64"
    "x64" -> "x86_64"
    else -> nativeArch
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
        "-DFILAMENT_ARCH=$filamentArch",
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

val copyNativeLib by tasks.registering(Copy::class) {
    group = "native"
    description = "Copy built native library to JAR packaging directory"
    dependsOn(cmakeBuild)
    from(cmakeBuildDir) {
        include("*.so", "*.dylib", "*.dll")
    }
    into(nativeLibDir)
    enabled = hasSdk
}

tasks.named<Jar>("jar") {
    dependsOn(copyNativeLib)
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
