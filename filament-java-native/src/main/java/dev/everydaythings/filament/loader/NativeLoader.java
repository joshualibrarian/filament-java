/*
 * Copyright (C) 2024 filament-java contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.everydaythings.filament.loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads the filament-jni native library from JAR resources or the file system.
 */
public final class NativeLoader {

    private static boolean sLoaded = false;

    private NativeLoader() {
    }

    /**
     * Loads the filament-jni native library. First tries java.library.path,
     * then extracts from JAR resources to a temp directory.
     */
    public static synchronized void load() {
        if (sLoaded) return;

        // Try java.library.path first
        try {
            System.loadLibrary("filament-jni");
            sLoaded = true;
            return;
        } catch (UnsatisfiedLinkError ignored) {
        }

        // Extract from JAR resources
        String platform = detectPlatform();
        String arch = detectArch();
        String libName = mapLibraryName("filament-jni");
        String resourcePath = "natives/" + platform + "-" + arch + "/" + libName;

        try (InputStream in = NativeLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new UnsatisfiedLinkError(
                    "Native library not found in JAR at: " + resourcePath);
            }

            Path tempDir = Files.createTempDirectory("filament-jni");
            Path tempLib = tempDir.resolve(libName);
            Files.copy(in, tempLib, StandardCopyOption.REPLACE_EXISTING);
            tempLib.toFile().deleteOnExit();
            tempDir.toFile().deleteOnExit();

            System.load(tempLib.toAbsolutePath().toString());
            sLoaded = true;
        } catch (IOException e) {
            throw new UnsatisfiedLinkError(
                "Failed to extract native library: " + e.getMessage());
        }
    }

    private static String detectPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux")) return "linux";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("win")) return "windows";
        throw new UnsatisfiedLinkError("Unsupported OS: " + os);
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("amd64") || arch.contains("x86_64")) return "x64";
        if (arch.contains("aarch64") || arch.contains("arm64")) return "arm64";
        throw new UnsatisfiedLinkError("Unsupported architecture: " + arch);
    }

    private static String mapLibraryName(String name) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return name + ".dll";
        if (os.contains("mac") || os.contains("darwin")) return "lib" + name + ".dylib";
        return "lib" + name + ".so";
    }
}
