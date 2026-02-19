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

package dev.everydaythings.filament;

class DesktopPlatform extends Platform {

    @Override
    void log(String message) {
        System.out.println("Filament: " + message);
    }

    @Override
    void warn(String message) {
        System.err.println("Filament: " + message);
    }

    @Override
    boolean validateStreamSource(Object object) {
        return false;
    }

    @Override
    boolean validateSurface(Object object) {
        // On desktop, surfaces are raw native window pointers (Long).
        // Most SwapChain creation goes through createSwapChainFromRawPointer,
        // so this is rarely called directly.
        return object instanceof Long;
    }

    @Override
    boolean validateSharedContext(Object object) {
        return object instanceof Long;
    }

    @Override
    long getSharedContextNativeHandle(Object sharedContext) {
        if (sharedContext instanceof Long) {
            return (Long) sharedContext;
        }
        return 0;
    }
}
