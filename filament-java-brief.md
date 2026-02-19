# filament-java — Desktop JVM Bindings for Google Filament

## What This Is

An independent library providing Java/JVM bindings for [Google Filament](https://github.com/google/filament), the physically-based rendering engine. Filament is a C++ engine with excellent desktop support (Vulkan, Metal, OpenGL) but its only official Java bindings target Android. This project resurrects and maintains desktop Java bindings so any JVM application can use Filament for 3D rendering.

**Filament officially had desktop Java bindings from ~2018 through v1.9.10.** They were removed in v1.11.0 (July 2021) because Google didn't want the maintenance burden — not because they were broken. The old code at git tag `v1.9.10` in `java/filament/` is the starting blueprint.

## Why This Exists

This library is the rendering foundation for [Common Graph](https://github.com/anthropics/common-graph), a paradigm-replacing system where everything is an Item. Common Graph has a declarative `@Scene` DSL that describes both 2D and 3D content. The rendering stack is:

```
@Scene DSL --> SceneCompiler
    |-- TuiSceneRenderer  (JLine terminal -- text only)
    |-- CliSceneRenderer   (basic CLI -- text only)
    |-- FilamentSceneRenderer (this library)
            |-- Skia for 2D surface rasterization (via Skija)
            |-- Filament for 3D scene, PBR, compositing
            |-- GLFW for windowing + input (via LWJGL)
```

2D UI surfaces (text, containers, layout primitives) render to Skia canvases, which become textures on Filament geometry. 3D spatial content renders directly through Filament. This is the same architectural pattern as Flutter/Chrome.

However, **this library is independent of Common Graph**. It's a general-purpose `filament-java` library that anyone can use to render 3D content from the JVM.

## Technical Context

### What exists in Filament today (android/)

The Android Java bindings live in `android/filament-android/` and consist of:

- **~41 Java classes** in `com.google.android.filament` — thin wrappers holding a `long mNativeObject` pointer to the C++ object, with `private static native` JNI methods.
- **~31 JNI C++ files** in `android/filament-android/src/main/cpp/` — each implements the native methods for one Java class.
- **1 platform-specific file** — `nativewindow/Android.cpp` which calls `ANativeWindow_fromSurface()` to convert an `android.view.Surface` to a native window handle.

Critical insight: **the JNI C++ files are platform-agnostic.** They use only `<jni.h>` and Filament C++ headers. Zero Android NDK calls. The 41 Java classes also have zero Android imports besides `@NonNull`/`@Nullable` annotations (compile-time only).

The entire Android coupling is isolated to:
1. `getNativeWindow()` — one function in `nativewindow/Android.cpp`
2. `AndroidPlatform.java` — one class that imports Android GL/Surface types
3. `UiHelper.java` — Android SurfaceView/TextureView lifecycle management
4. `@androidx.annotation.NonNull/Nullable` — trivially replaced or removed

### What existed in Filament v1.9.10 (java/filament/)

The old desktop module contained:
- `DesktopPlatform.java` — validated `java.awt.Canvas` as surface type
- `FilamentCanvas.java` — AWT Canvas that created a SwapChain from native window handle
- `FilamentPanel.java` — Swing JPanel using headless render + readPixels (GPU readback path)
- `nativewindow/Linux.cpp` — extracted X11 drawable via JAWT
- `nativewindow/Win32.cpp` — extracted HWND via JAWT
- `nativewindow/Darwin.mm` — extracted NSView via JAWT
- `CMakeLists.txt` — built `libfilament-jni.so/.dylib/.dll` + `filament-java.jar`

### Desktop rendering backends (all mature, actively maintained)

| Platform | Default | Available |
|----------|---------|-----------|
| Linux    | Vulkan  | Vulkan, OpenGL (GLX/EGL), headless (OSMesa) |
| Windows  | Vulkan  | Vulkan, OpenGL (WGL) |
| macOS    | Metal   | Metal, Vulkan, OpenGL (Cocoa GL) |

### Desktop native libraries

Filament ships pre-built desktop archives in every GitHub release:
- `filament-<version>-linux.tgz` (~52 MB)
- `filament-<version>-mac.tgz` (~45 MB)
- `filament-<version>-windows.tgz` (~747 MB)

These contain static libraries (`.a`, `.lib`), headers, and CLI tools (`matc`, `cmgen`, etc.).

## Architecture

### Core Design

```
filament-java (this library)
|
|-- filament-java-core/        Java API classes (Engine, Renderer, Scene, etc.)
|-- filament-java-native/      JNI C++ bridge + CMake build
|   |-- src/cpp/               JNI implementations (from Filament android/ sources)
|   |-- src/cpp/nativewindow/  Platform-specific native window extraction
|   |   |-- Glfw.cpp           GLFW-based (primary path, via LWJGL)
|   |   |-- Linux.cpp          X11/Wayland direct (optional)
|   |   |-- Win32.cpp          HWND direct (optional)
|   |   |-- Darwin.mm          NSView direct (optional)
|   |-- CMakeLists.txt         Builds libfilament-jni for each platform
|-- filament-java-lwjgl/       Optional GLFW/LWJGL integration module
```

### Java API Surface (Minimum Viable)

These 16 classes have zero Android dependencies and are the minimum for rendering:

| Class | Purpose |
|-------|---------|
| `Engine` | Create/destroy, backend selection |
| `Renderer` | Frame lifecycle (beginFrame/endFrame/render) |
| `View` | Viewport, camera, scene binding |
| `Scene` | Entity management |
| `Camera` | Projection, transforms |
| `SwapChain` | Rendering target |
| `Entity` + `EntityManager` | ECS identifiers |
| `RenderableManager` | Geometry + material binding |
| `TransformManager` | Scene graph transforms |
| `Material` + `MaterialInstance` | PBR shading |
| `VertexBuffer` + `IndexBuffer` | Geometry data |
| `Texture` | Texture data |
| `LightManager` | Direct + spot + point lights |
| `IndirectLight` + `Skybox` | Image-based lighting, environment |

Full API is ~41 classes; the rest (ColorGrading, RenderTarget, Fence, Stream, etc.) can be added incrementally.

### Window / SwapChain Strategy

**Primary path: GLFW via LWJGL.** LWJGL provides mature GLFW bindings. GLFW handles window creation, input events, and exposes native window handles (`glfwGetWin32Window()`, `glfwGetX11Window()`, `glfwGetCocoaWindow()`). The native handle feeds into `Engine.createSwapChain()`.

This avoids any AWT/Swing/JavaFX dependency. Input events come from GLFW callbacks, which the consuming application (Common Graph) maps to its own input model.

### What NOT to do

- **Don't use AWT/JAWT** — the old v1.9.10 approach used JAWT to extract native window handles from AWT Canvas. AWT is legacy. GLFW is simpler and more performant.
- **Don't use JavaFX** — we're explicitly replacing it.
- **Don't wrap Filament's sample app framework** — `filamentapp` is a C++ convenience layer using SDL2. Build the Java integration from the engine API directly.

## Roadmap

### Phase 1: Build System + Native Library (Weeks 1-2)

- Set up Gradle multi-module project (Java side) + CMake (native side)
- Download Filament desktop release archives (or build from source)
- Copy the ~31 JNI C++ files from Filament's `android/filament-android/src/main/cpp/`
- Write `CMakeLists.txt` that compiles them against Filament headers + JNI headers, links Filament static libs
- Target Linux first (Vulkan backend), then macOS (Metal), then Windows
- Output: `libfilament-jni.so` that loads into a JVM process

### Phase 2: Java API + Hello Triangle (Weeks 2-3)

- Copy the ~16 minimum viable Java classes from Filament's Android bindings
- Strip `@androidx.annotation` annotations
- Replace `AndroidPlatform` with `DesktopPlatform` (GLFW-based)
- Replace `System.loadLibrary("filament-jni")` with platform-aware native library loading (extract from JAR resources or locate on library path)
- Write GLFW integration: create window, extract native handle, create SwapChain
- **Milestone: render a colored triangle in a GLFW window from pure Java**

### Phase 3: Full API + Material Support (Weeks 3-5)

- Add remaining API classes (ColorGrading, RenderTarget, Fence, MorphTargetBuffer, SkinningBuffer, etc.)
- Integrate `matc` (material compiler) — either as a build-time tool or runtime invocation
- Load `.filamat` pre-compiled materials
- **Milestone: render a lit, textured, PBR sphere with environment lighting**

### Phase 4: Publishing + CI (Weeks 5-6)

- CI pipeline that builds native libraries for Linux (x86_64, aarch64), macOS (x86_64, aarch64), Windows (x86_64)
- Package as Maven artifacts: `filament-java-core` (API JAR), `filament-java-natives-linux-x64` (native .so), etc.
- Platform-aware dependency resolution (Gradle variant-aware, or classifier-based)
- **Milestone: `implementation 'dev.everydaythings:filament-java:0.1.0'` in any Gradle project just works**

### Phase 5: Tracking Upstream (Ongoing)

- The JNI layer is thin wrappers — when Filament adds a method to e.g. `Engine`, you add one `native` declaration in Java and one ~5-line C++ function
- Monitor Filament releases for API changes
- Periodically re-sync the JNI layer against the latest Filament version

## Key References

- **Filament repo:** https://github.com/google/filament
- **Last version with desktop Java (v1.9.10):** https://github.com/google/filament/tree/v1.9.10/java/filament/
- **Android JNI sources (current):** https://github.com/google/filament/tree/main/android/filament-android/src/main/cpp/
- **Android Java sources (current):** https://github.com/google/filament/tree/main/android/filament-android/src/main/java/com/google/android/filament/
- **Filament C++ API docs:** https://google.github.io/filament/
- **Filament material system:** https://google.github.io/filament/Materials.html
- **LWJGL (GLFW bindings):** https://www.lwjgl.org/
- **Skija (Skia Java bindings, for future 2D integration):** https://github.com/ABI-Laboratory/skija / https://github.com/ABI-Laboratory/skija (JetBrains maintains this)

## Build Commands (Planned)

```bash
./gradlew build                    # Build everything
./gradlew :core:compileJava       # Compile Java API
./gradlew :native:cmakeBuild      # Build native JNI library
./gradlew :example:run            # Run hello-triangle example
./gradlew publishToMavenLocal     # Install to local Maven repo
```

## Tech Stack

- **Java 21** (via Gradle toolchain)
- **CMake** for native library build
- **Filament** desktop release archives (static libs + headers)
- **LWJGL 3** for GLFW windowing + input
- **JUnit 5** for testing
- **Gradle** with multi-module layout

## Conventions

- Package: `dev.everydaythings.filament` (or similar — not `com.google.android.filament`)
- The Java classes are thin wrappers: each holds a `long nativeObject`, JNI methods take it as first arg
- All native resources must be explicitly destroyed (Engine.destroy(), etc.) — follow Filament's C++ ownership model
- No Android dependencies whatsoever
- No AWT/Swing/JavaFX dependencies in core — windowing integration is a separate module
