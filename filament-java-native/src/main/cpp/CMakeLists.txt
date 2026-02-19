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

# Detect library directory based on platform and architecture
if(NOT DEFINED FILAMENT_ARCH)
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64")
        set(FILAMENT_ARCH "arm64")
    elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|amd64|AMD64|x64")
        set(FILAMENT_ARCH "x86_64")
    else()
        message(FATAL_ERROR "Unsupported architecture: ${CMAKE_SYSTEM_PROCESSOR}")
    endif()
endif()

# Windows SDK has CRT variant subdirectories (mt, md, mtd, mdd)
if(WIN32)
    set(FILAMENT_LIB_DIR "${FILAMENT_SDK_DIR}/lib/${FILAMENT_ARCH}/mt")
else()
    set(FILAMENT_LIB_DIR "${FILAMENT_SDK_DIR}/lib/${FILAMENT_ARCH}")
endif()

# --- Import Filament static libraries ---
function(import_filament_lib NAME)
    add_library(${NAME} STATIC IMPORTED)
    if(WIN32)
        set_target_properties(${NAME} PROPERTIES IMPORTED_LOCATION
                "${FILAMENT_LIB_DIR}/${NAME}.lib")
    else()
        set_target_properties(${NAME} PROPERTIES IMPORTED_LOCATION
                "${FILAMENT_LIB_DIR}/lib${NAME}.a")
    endif()
endfunction()

# Core Filament libraries
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

# gltfio libraries
import_filament_lib(gltfio)
import_filament_lib(gltfio_core)
import_filament_lib(stb)
import_filament_lib(uberarchive)
import_filament_lib(uberzlib)
import_filament_lib(dracodec)
import_filament_lib(basis_transcoder)
import_filament_lib(ktxreader)
import_filament_lib(mikktspace)

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
    # gltfio JNI sources
    gltfio/AssetLoader.cpp
    gltfio/FilamentAsset.cpp
    gltfio/FilamentInstance.cpp
    gltfio/Animator.cpp
    gltfio/ResourceLoader.cpp
    gltfio/UbershaderProvider.cpp
    gltfio/MaterialKey.cpp
)

# --- Build the shared library ---
add_library(filament-jni SHARED ${JNI_SOURCES})

target_include_directories(filament-jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}             # for "common/..." includes from JNI .cpp files
    ${CMAKE_CURRENT_SOURCE_DIR}/common      # for CallbackUtils.cpp's #include "CallbackUtils.h"
    ${CMAKE_CURRENT_SOURCE_DIR}/include     # for private/backend/VirtualMachineEnv.h
    ${CMAKE_CURRENT_SOURCE_DIR}/gltfio      # for MaterialKey.h
    ${FILAMENT_INCLUDE_DIR}                 # Filament public headers
    ${JNI_INCLUDE_DIRS}                     # JNI headers
)

# Link order matters for static libraries.
# GNU ld needs --start-group/--end-group to resolve circular deps.
# macOS ld64 and MSVC handle circular deps automatically.
set(FILAMENT_STATIC_LIBS
    # gltfio
    gltfio
    gltfio_core
    uberarchive
    # Core Filament
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
    # gltfio dependency libraries
    stb
    uberzlib
    dracodec
    basis_transcoder
    ktxreader
    mikktspace
)

if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    target_link_libraries(filament-jni PRIVATE
        -Wl,--start-group ${FILAMENT_STATIC_LIBS} -Wl,--end-group)
else()
    target_link_libraries(filament-jni PRIVATE ${FILAMENT_STATIC_LIBS})
endif()

# Platform-specific system libraries
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    target_link_libraries(filament-jni PRIVATE
        pthread
        dl
        c++
        c++abi
    )
elseif(CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    target_link_libraries(filament-jni PRIVATE
        c++
        "-framework Cocoa"
        "-framework Metal"
        "-framework CoreVideo"
        "-framework IOKit"
        "-framework IOSurface"
        "-framework QuartzCore"
    )
elseif(WIN32)
    target_link_libraries(filament-jni PRIVATE
        shlwapi
        user32
        gdi32
        opengl32
        Dwmapi
    )
endif()
