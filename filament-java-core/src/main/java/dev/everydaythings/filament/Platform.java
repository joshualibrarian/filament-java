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


abstract class Platform {
    private static Platform mCurrentPlatform = null;

    static boolean isAndroid() {
        return "The Android Project".equalsIgnoreCase(System.getProperty("java.vendor"));
    }

    static boolean isWindows() {
        return System.getProperty("os.name").contains("Windows");
    }

    static boolean isMacOS() {
        return System.getProperty("os.name").contains("Mac OS X");
    }

    static boolean isLinux() {
        // Android reports "Linux" as the OS name
        return System.getProperty("os.name").contains("Linux") && !isAndroid();
    }

    
    static Platform get() {
        if (mCurrentPlatform == null) {
            //noinspection EmptyCatchBlock
            try {
                mCurrentPlatform = new DesktopPlatform();
            } catch (Exception e) {
            }
            if (mCurrentPlatform == null) {
                mCurrentPlatform  = new UnknownPlatform();
            }
        }
        return mCurrentPlatform;
    }

    Platform() { }

    abstract void log(String message);
    abstract void warn(String message);

    abstract boolean validateStreamSource(Object object);
    abstract boolean validateSurface(Object object);
    abstract boolean validateSharedContext(Object object);
    abstract long getSharedContextNativeHandle(Object sharedContext);

    private static class UnknownPlatform extends Platform {
        @Override
        void log(String message) {
            System.out.println(message);
        }

        @Override
        void warn(String message) {
            System.out.println(message);
        }

        @Override
        boolean validateStreamSource(Object object) {
            return false;
        }

        @Override
        boolean validateSurface(Object object) {
            return false;
        }

        @Override
        boolean validateSharedContext(Object object) {
            return false;
        }

        @Override
        long getSharedContextNativeHandle(Object sharedContext) {
            return 0;
        }
    }
}
