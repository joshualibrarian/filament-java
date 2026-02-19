/*
 * Copyright (C) 2019 The Android Open Source Project
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


final class Asserts {
    private Asserts() {
    }

    
    static float[] assertMat3f(float[] out) {
        if (out == null) out = new float[9];
        else if (out.length < 9) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 9");
        }
        return out;
    }

    static void assertMat3fIn(float[] in) {
        if (in.length < 9) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 9");
        }
    }

    
    static double[] assertMat4d(double[] out) {
        if (out == null) out = new double[16];
        else if (out.length < 16) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 16");
        }
        return out;
    }

    static void assertMat4dIn(double[] in) {
        if (in.length < 16) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 16");
        }
    }

    
    static float[] assertMat4f(float[] out) {
        if (out == null) out = new float[16];
        else if (out.length < 16) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 16");
        }
        return out;
    }

    
    static double[] assertMat4(double[] out) {
        if (out == null) out = new double[16];
        else if (out.length < 16) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 16");
        }
        return out;
    }

    static void assertMat4fIn(float[] in) {
        if (in.length < 16) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 16");
        }
    }

    static void assertMat4In(double[] in) {
        if (in.length < 16) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 16");
        }
    }

    
    static float[] assertFloat3(float[] out) {
        if (out == null) out = new float[3];
        else if (out.length < 3) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 3");
        }
        return out;
    }

    static void assertFloat3In(float[] out) {
        if (out.length < 3) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 3");
        }
    }

    
    static float[] assertFloat2(float[] out) {
        if (out == null) out = new float[2];
        else if (out.length < 2) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 2");
        }
        return out;
    }

    
    static float[] assertFloat4(float[] out) {
        if (out == null) out = new float[4];
        else if (out.length < 4) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 4");
        }
        return out;
    }

    static void assertFloat4In(float[] out) {
        if (out.length < 4) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 4");
        }
    }

    static double[] assertDouble4(double[] out) {
        if (out == null) out = new double[4];
        else if (out.length < 4) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 4");
        }
        return out;
    }

    static void assertDouble4In(double[] in) {
        if (in.length < 4) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 4");
        }
    }

    
    static double[] assertDouble2(double[] out) {
        if (out == null) out = new double[2];
        else if (out.length < 2) {
            throw new ArrayIndexOutOfBoundsException("Array length must be at least 2");
        }
        return out;
    }
}
