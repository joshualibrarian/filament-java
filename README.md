# filament-java

Desktop JVM bindings for [Google Filament](https://github.com/google/filament), a physically-based rendering (PBR) engine.

Filament is a high-performance C++ rendering engine with Vulkan, Metal, and OpenGL backends. It had official desktop Java bindings through v1.9.10, but Google removed them in v1.11.0 (July 2021). This project resurrects and maintains those bindings so any JVM application can use Filament for 3D rendering.

| Platform | Backend | Status |
|----------|---------|--------|
| Linux    | Vulkan, OpenGL | Working |
| macOS    | Metal, Vulkan  | CI builds, untested |
| Windows  | Vulkan, OpenGL | CI builds, untested |

## Quick Start

```java
import dev.everydaythings.filament.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWNativeX11.*;

public class Minimal {
    public static void main(String[] args) {
        // Create a GLFW window
        glfwInit();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        long window = glfwCreateWindow(800, 600, "Filament", 0, 0);

        // Initialize Filament
        Filament.init();
        Engine engine = new Engine.Builder()
                .backend(Engine.Backend.VULKAN)
                .build();

        // Create rendering objects
        SwapChain swapChain = engine.createSwapChainFromRawPointer(
                glfwGetX11Window(window), 0);
        Renderer renderer = engine.createRenderer();
        View view = engine.createView();
        Scene scene = engine.createScene();
        Camera camera = engine.createCamera(
                engine.getEntityManager().create());

        view.setScene(scene);
        view.setCamera(camera);
        view.setViewport(new Viewport(0, 0, 800, 600));

        // Render loop
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            if (renderer.beginFrame(swapChain, System.nanoTime())) {
                renderer.render(view);
                renderer.endFrame();
            }
        }

        // Cleanup
        engine.destroyRenderer(renderer);
        engine.destroyView(view);
        engine.destroyScene(scene);
        engine.destroyCameraComponent(camera.getEntity());
        engine.destroySwapChain(swapChain);
        engine.destroy();
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
```

## Modules

| Module | Description |
|--------|-------------|
| `filament-java-core` | Java API — 40 wrapper classes (Engine, Renderer, Scene, View, Camera, Material, etc.) |
| `filament-java-native` | JNI C++ bridge + native library (`libfilament-jni.so` / `.dylib` / `.dll`) |
| `filament-java-gltfio` | glTF 2.0 model loading (AssetLoader, ResourceLoader, Animator) |
| `filament-java-text` | MSDF text rendering (MsdfAtlas, MsdfFontManager, MsdfTextRenderer) |
| `filament-java-lwjgl` | GLFW windowing integration via LWJGL |

## Examples

**hello-triangle** — Colored triangle with a custom material:

```bash
./gradlew :examples:hello-triangle:run
```

**hello-gltf** — Load and render a glTF model with lighting and animation:

```bash
./gradlew :examples:hello-gltf:run
```

**hello-camera** — Interactive orbit camera around a glTF model (left-drag orbit, right-drag pan, scroll zoom):

```bash
./gradlew :examples:hello-camera:run
```

**hello-picking** — Object selection via `View.pick()` — click on colored cubes to highlight them:

```bash
./gradlew :examples:hello-picking:run
```

## Building from Source

### Prerequisites

- Java 21+
- CMake 3.22+
- Filament SDK (downloaded below)
- Linux only: `sudo apt install libc++-dev libc++abi-dev`

### Steps

1. **Download the Filament SDK** (v1.69.2):

```bash
# Linux
curl -LO https://github.com/google/filament/releases/download/v1.69.2/filament-v1.69.2-linux.tgz
mkdir -p filament-sdk && tar xzf filament-v1.69.2-linux.tgz -C filament-sdk --strip-components=1

# macOS
curl -LO https://github.com/google/filament/releases/download/v1.69.2/filament-v1.69.2-mac.tgz
mkdir -p filament-sdk && tar xzf filament-v1.69.2-mac.tgz -C filament-sdk --strip-components=1

# Windows (PowerShell)
Invoke-WebRequest -Uri https://github.com/google/filament/releases/download/v1.69.2/filament-v1.69.2-windows.tgz -OutFile filament-sdk.tgz
mkdir filament-sdk; tar xzf filament-sdk.tgz -C filament-sdk --strip-components=1
```

2. **Build:**

```bash
./gradlew build
```

This compiles the Java modules and builds the native library via CMake. The native `.so`/`.dylib`/`.dll` is automatically bundled into the `filament-java-native` JAR.

3. **Run an example:**

```bash
./gradlew :examples:hello-triangle:run
```

### Material Compilation

Material source files (`.mat`) are compiled to binary (`.filamat`) using `matc` from the Filament SDK:

```bash
./filament-sdk/bin/matc -p desktop -a vulkan -a opengl -o output.filamat input.mat
```

## Architecture

The Java classes are thin JNI wrappers — each holds a `long nativeObject` pointer to a C++ Filament object. The JNI C++ layer forwards calls directly to Filament's C++ API. Windowing is handled by GLFW via LWJGL, keeping the core free of AWT/Swing/JavaFX dependencies.

```
Your Application
    |
    +-- filament-java-core      (Java API)
    +-- filament-java-gltfio    (glTF loading, optional)
    +-- filament-java-text      (MSDF text rendering, optional)
    +-- filament-java-lwjgl     (GLFW windowing)
    +-- filament-java-native    (JNI bridge -> libfilament-jni -> Filament C++)
            |
            +-- Filament Engine (Vulkan / Metal / OpenGL)
```

The JNI sources originate from Filament's Android bindings (`android/filament-android/src/main/cpp/`), which are platform-agnostic — they use only `<jni.h>` and Filament headers with zero Android NDK calls.

## Status

This project is in early development. The core rendering API works on Linux with Vulkan. glTF model loading and MSDF text rendering work. macOS and Windows builds are set up in CI but not yet runtime-tested.

Contributions and bug reports are welcome.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

Filament is developed by Google and licensed under Apache 2.0. This project is an independent, community-maintained fork of the desktop Java bindings.
