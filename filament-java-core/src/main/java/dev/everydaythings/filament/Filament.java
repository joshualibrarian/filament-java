/*
 * Copyright (C) 2017 The Android Open Source Project
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

package dev.everydaythings.filament;

public class Filament {
    private static boolean sLoaded = false;

    static {
        Platform.get();
        loadNativeLibrary();
    }

    private Filament() {
    }

    @SuppressWarnings("unused")
    public static void init() {
        // Triggers the static initializer above.
    }

    private static synchronized void loadNativeLibrary() {
        if (sLoaded) return;
        try {
            System.loadLibrary("filament-jni");
            sLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            // If not on java.library.path, try the NativeLoader from the native module.
            // This class is loaded via reflection so that filament-java-core has no
            // compile-time dependency on filament-java-native.
            try {
                Class<?> loader = Class.forName("dev.everydaythings.filament.loader.NativeLoader");
                loader.getMethod("load").invoke(null);
                sLoaded = true;
            } catch (Exception ex) {
                throw new UnsatisfiedLinkError(
                    "Failed to load filament-jni native library. " +
                    "Ensure filament-java-native is on the classpath, or set java.library.path. " +
                    "Original error: " + e.getMessage());
            }
        }
    }
}
