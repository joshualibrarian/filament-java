cmake_minimum_required(VERSION 3.22)
project(filament-jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --- JNI ---
find_package(JNI REQUIRED)

# --- Filament SDK ---
# Set FILAMENT_SDK_DIR to the root of an extracted Filament desktop release.
# Example: cmake -DFILAMENT_SDK_DIR=/path/to/filament-sdk
if(NOT DEFINED FILAMENT_SDK_DIR)
    message(FATAL_ERROR "FILAMENT_SDK_DIR is not set. Point it to an extracted Filament desktop release.")
endif()

set(FILAMENT_INCLUDE_DIR "${FILAMENT_SDK_DIR}/include")
set(FILAMENT_LIB_DIR "${FILAMENT_SDK_DIR}/lib/x86_64")

# --- Import Filament static libraries ---
function(import_filament_lib NAME)
    add_library(${NAME} STATIC IMPORTED)
    set_target_properties(${NAME} PROPERTIES IMPORTED_LOCATION
            "${FILAMENT_LIB_DIR}/lib${NAME}.a")
endfunction()

import_filament_lib(filament)
import_filament_lib(filament-generatePrefilterMipmap)
import_filament_lib(backend)
import_filament_lib(filaflat)
import_filament_lib(filabridge)
import_filament_lib(ibl-lite)
import_filament_lib(utils)
import_filament_lib(geometry)
import_filament_lib(perfetto)
import_filament_lib(abseil)
import_filament_lib(zstd)
import_filament_lib(smol-v)
import_filament_lib(bluevk)
import_filament_lib(bluegl)

# --- JNI source files ---
set(JNI_SOURCES
    BufferObject.cpp
    Camera.cpp
    Colors.cpp
    ColorGrading.cpp
    Engine.cpp
    EntityManager.cpp
    Fence.cpp
    Filament.cpp
    IndexBuffer.cpp
    IndirectLight.cpp
    LightManager.cpp
    Material.cpp
    MaterialInstance.cpp
    MathUtils.cpp
    MorphTargetBuffer.cpp
    NativeSurface.cpp
    RenderableManager.cpp
    Renderer.cpp
    RenderTarget.cpp
    Scene.cpp
    SkinningBuffer.cpp
    SkyBox.cpp
    Stream.cpp
    SurfaceOrientation.cpp
    SwapChain.cpp
    Texture.cpp
    TextureSampler.cpp
    ToneMapper.cpp
    TransformManager.cpp
    VertexBuffer.cpp
    View.cpp
    # Desktop native window stub
    nativewindow/Desktop.cpp
    # Common utilities
    common/CallbackUtils.cpp
    common/NioUtils.cpp
    common/VirtualMachineEnv.cpp
)

# --- Build the shared library ---
add_library(filament-jni SHARED ${JNI_SOURCES})

target_include_directories(filament-jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}             # for "common/..." includes from JNI .cpp files
    ${CMAKE_CURRENT_SOURCE_DIR}/common      # for CallbackUtils.cpp's #include "CallbackUtils.h"
    ${CMAKE_CURRENT_SOURCE_DIR}/include     # for private/backend/VirtualMachineEnv.h
    ${FILAMENT_INCLUDE_DIR}                 # Filament public headers
    ${JNI_INCLUDE_DIRS}                     # JNI headers
)

# Link order matters for static libraries
target_link_libraries(filament-jni PRIVATE
    filament-generatePrefilterMipmap
    filament
    backend
    filaflat
    filabridge
    ibl-lite
    utils
    geometry
    perfetto
    abseil
    zstd
    bluevk
    smol-v
    bluegl
    # System libraries (Linux)
    pthread
    dl
    # Filament SDK is built with Clang/libc++
    c++
    c++abi
)
