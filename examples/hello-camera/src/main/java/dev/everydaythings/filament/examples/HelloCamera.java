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

package dev.everydaythings.filament.examples;

import dev.everydaythings.filament.*;
import dev.everydaythings.filament.gltfio.*;

import org.lwjgl.glfw.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWNativeX11.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Interactive orbit camera around a glTF model.
 *
 * Controls:
 *   Left-drag:  Orbit (rotate around model)
 *   Right-drag: Pan (shift look target)
 *   Scroll:     Zoom (change distance)
 */
public class HelloCamera {

    private static final int WINDOW_WIDTH = 1024;
    private static final int WINDOW_HEIGHT = 768;

    // Orbit camera state (spherical coordinates)
    private static double theta = Math.PI / 4;    // azimuth angle
    private static double phi = Math.PI / 6;      // elevation angle
    private static double radius = 3.0;           // distance from target
    private static double targetX = 0, targetY = 0, targetZ = 0;

    // Mouse interaction state
    private static double lastMouseX, lastMouseY;
    private static boolean leftDragging = false;
    private static boolean rightDragging = false;

    public static void main(String[] args) throws Exception {
        GLFWErrorCallback.createPrint(System.err).set();
        glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11);

        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);

        long window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT,
                "filament-java — Interactive Camera", NULL, NULL);
        if (window == NULL) {
            glfwTerminate();
            throw new RuntimeException("Failed to create GLFW window");
        }

        long nativeWindow = glfwGetX11Window(window);

        // Setup mouse callbacks
        setupMouseCallbacks(window);

        // Initialize Filament + gltfio
        Gltfio.init();

        Engine engine = new Engine.Builder().backend(Engine.Backend.VULKAN).build();
        if (engine == null) {
            engine = new Engine.Builder().backend(Engine.Backend.OPENGL).build();
        }

        SwapChain swapChain = engine.createSwapChainFromRawPointer(nativeWindow, 0);
        Renderer renderer = engine.createRenderer();
        Scene scene = engine.createScene();
        View view = engine.createView();
        Camera camera = engine.createCamera(engine.getEntityManager().create());

        view.setScene(scene);
        view.setCamera(camera);
        view.setViewport(new Viewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT));

        // Lighting
        int sunlight = EntityManager.get().create();
        new LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 1.0f, 1.0f)
                .intensity(100000.0f)
                .direction(-0.5f, -1.0f, -0.5f)
                .castShadows(true)
                .build(engine, sunlight);
        scene.addEntity(sunlight);

        IndirectLight ibl = new IndirectLight.Builder()
                .intensity(30000.0f)
                .build(engine);
        scene.setIndirectLight(ibl);

        // Load glTF model
        UbershaderProvider materialProvider = new UbershaderProvider(engine);
        AssetLoader assetLoader = new AssetLoader(engine, materialProvider,
                engine.getEntityManager());

        byte[] glbData = loadResource("models/Box.glb");
        ByteBuffer glbBuffer = ByteBuffer.allocateDirect(glbData.length)
                .order(ByteOrder.nativeOrder());
        glbBuffer.put(glbData);
        glbBuffer.flip();

        FilamentAsset asset = assetLoader.createAsset(glbBuffer);
        if (asset == null) {
            throw new RuntimeException("Failed to load glTF asset");
        }

        ResourceLoader resourceLoader = new ResourceLoader(engine);
        resourceLoader.loadResources(asset);
        asset.releaseSourceData();

        int[] entities = asset.getEntities();
        for (int entity : entities) {
            scene.addEntity(entity);
        }
        scene.addEntity(asset.getRoot());

        // Fit camera to model
        Box boundingBox = asset.getBoundingBox();
        float[] center = boundingBox.getCenter();
        float[] halfExtent = boundingBox.getHalfExtent();
        float maxExtent = Math.max(halfExtent[0], Math.max(halfExtent[1], halfExtent[2]));

        targetX = center[0];
        targetY = center[1];
        targetZ = center[2];
        radius = maxExtent * 3.5;

        double aspect = (double) WINDOW_WIDTH / WINDOW_HEIGHT;
        camera.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL);

        // Clear color
        Renderer.ClearOptions clearOptions = new Renderer.ClearOptions();
        clearOptions.clearColor = new float[]{0.2f, 0.2f, 0.25f, 1.0f};
        clearOptions.clear = true;
        clearOptions.discard = true;
        renderer.setClearOptions(clearOptions);

        System.out.println("Interactive camera demo.");
        System.out.println("  Left-drag:  Orbit");
        System.out.println("  Right-drag: Pan");
        System.out.println("  Scroll:     Zoom");

        // Animation setup
        Animator animator = asset.getInstance().getAnimator();
        int animationCount = animator.getAnimationCount();
        long startTime = System.nanoTime();

        // Render loop
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();

            // Update animations
            if (animationCount > 0) {
                float elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0f;
                float duration = animator.getAnimationDuration(0);
                float time = elapsed % duration;
                animator.applyAnimation(0, time);
                animator.updateBoneMatrices();
            }

            // Update camera from orbit parameters
            updateCamera(camera);

            if (renderer.beginFrame(swapChain, System.nanoTime())) {
                renderer.render(view);
                renderer.endFrame();
            }
        }

        // Cleanup
        resourceLoader.destroy();
        assetLoader.destroyAsset(asset);
        materialProvider.destroy();
        assetLoader.destroy();

        engine.destroyEntity(sunlight);
        engine.destroyIndirectLight(ibl);
        engine.destroyRenderer(renderer);
        engine.destroyView(view);
        engine.destroyScene(scene);
        engine.destroyCameraComponent(camera.getEntity());
        engine.destroySwapChain(swapChain);
        engine.destroy();

        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private static void setupMouseCallbacks(long window) {
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            double[] xpos = new double[1], ypos = new double[1];
            glfwGetCursorPos(win, xpos, ypos);
            lastMouseX = xpos[0];
            lastMouseY = ypos[0];

            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                leftDragging = (action == GLFW_PRESS);
            } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                rightDragging = (action == GLFW_PRESS);
            }
        });

        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            double dx = xpos - lastMouseX;
            double dy = ypos - lastMouseY;
            lastMouseX = xpos;
            lastMouseY = ypos;

            if (leftDragging) {
                // Orbit: rotate around target
                theta -= dx * 0.01;
                phi += dy * 0.01;
                // Clamp phi to avoid flipping
                phi = Math.max(-Math.PI / 2 + 0.01, Math.min(Math.PI / 2 - 0.01, phi));
            } else if (rightDragging) {
                // Pan: shift target in camera-local XY plane
                double panSpeed = radius * 0.002;
                // Camera right vector (perpendicular to view direction in XZ plane)
                double rightX = Math.cos(theta - Math.PI / 2);
                double rightZ = Math.sin(theta - Math.PI / 2);
                // Camera up is always world Y for simplicity
                targetX += rightX * dx * panSpeed;
                targetZ += rightZ * dx * panSpeed;
                targetY += dy * panSpeed;
            }
        });

        glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
            radius *= (1.0 - yoffset * 0.1);
            radius = Math.max(0.1, radius);
        });
    }

    private static void updateCamera(Camera camera) {
        // Spherical to Cartesian
        double eyeX = targetX + radius * Math.cos(phi) * Math.sin(theta);
        double eyeY = targetY + radius * Math.sin(phi);
        double eyeZ = targetZ + radius * Math.cos(phi) * Math.cos(theta);

        camera.lookAt(
                eyeX, eyeY, eyeZ,
                targetX, targetY, targetZ,
                0.0, 1.0, 0.0
        );
    }

    private static byte[] loadResource(String path) throws IOException {
        try (InputStream in = HelloCamera.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Resource not found: " + path);
            }
            return in.readAllBytes();
        }
    }
}
