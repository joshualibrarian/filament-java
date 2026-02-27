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
import dev.everydaythings.filament.text.*;

import org.lwjgl.glfw.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWNativeX11.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Showcases TextMeshBuilder with three surface types:
 * flat text, cylindrical wrap, and spherical wrap.
 *
 * Controls:
 *   Right-drag: Orbit camera
 *   Scroll:     Zoom
 */
public class HelloText {

    private static final int WINDOW_WIDTH = 1024;
    private static final int WINDOW_HEIGHT = 768;

    // Orbit camera state
    private static double theta = 0;
    private static double phi = Math.PI / 8;
    private static double radius = 7.0;
    private static double lastMouseX, lastMouseY;
    private static boolean rightDragging = false;

    public static void main(String[] args) {
        // --- GLFW init ---
        GLFWErrorCallback.createPrint(System.err).set();
        glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11);

        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);

        long window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT,
                "hello-text — TextMeshBuilder showcase", NULL, NULL);
        if (window == NULL) {
            glfwTerminate();
            throw new RuntimeException("Failed to create GLFW window");
        }

        long nativeWindow = glfwGetX11Window(window);

        // Mouse callbacks for orbit camera
        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                rightDragging = (action == GLFW_PRESS);
                double[] mx = new double[1], my = new double[1];
                glfwGetCursorPos(w, mx, my);
                lastMouseX = mx[0];
                lastMouseY = my[0];
            }
        });

        glfwSetCursorPosCallback(window, (w, xpos, ypos) -> {
            if (rightDragging) {
                double dx = xpos - lastMouseX;
                double dy = ypos - lastMouseY;
                theta -= dx * 0.005;
                phi = Math.max(-Math.PI / 2 + 0.01,
                        Math.min(Math.PI / 2 - 0.01, phi + dy * 0.005));
                lastMouseX = xpos;
                lastMouseY = ypos;
            }
        });

        glfwSetScrollCallback(window, (w, xoffset, yoffset) -> {
            radius = Math.max(2.0, Math.min(20.0, radius - yoffset * 0.5));
        });

        // --- Filament init ---
        Filament.init();

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

        // --- Text setup ---
        MsdfTextRenderer textRenderer = new MsdfTextRenderer(engine);
        MsdfFontManager fontManager = new MsdfFontManager(engine);
        fontManager.loadDefaultFont();

        float fontSize = 0.4f;

        // 1. Flat text on XY plane, positioned above
        TextMesh flatMesh = new TextMeshBuilder(engine, textRenderer)
                .text("Hello, Filament!")
                .fontManager(fontManager)
                .fontSize(fontSize)
                .color(0xFFFFFFFF) // white
                .surface(new FlatTextSurface(
                        new float[]{-1.5f, 1.8f, 0f},
                        new float[]{1, 0, 0},
                        new float[]{0, 1, 0}))
                .build();
        scene.addEntity(flatMesh.entity());

        // 2. Cylindrical text wrapping around a vertical cylinder at origin
        TextMesh cylinderMesh = new TextMeshBuilder(engine, textRenderer)
                .text("Text wraps around a cylinder!")
                .fontManager(fontManager)
                .fontSize(fontSize)
                .color(0xFF00FFFF) // cyan (ARGB)
                .surface(new CylindricalTextSurface(
                        new float[]{0, 0, 0},     // center
                        new float[]{0, 1, 0},     // axis (vertical)
                        1.5f,                      // radius
                        new float[]{0, 0, 1}))    // start facing +Z (toward camera)
                .build();
        scene.addEntity(cylinderMesh.entity());

        // 3. Spherical text on a sphere below
        TextMesh sphereMesh = new TextMeshBuilder(engine, textRenderer)
                .text("On a sphere!")
                .fontManager(fontManager)
                .fontSize(fontSize)
                .color(0xFFFFFF00) // yellow (ARGB)
                .surface(new SphericalTextSurface(
                        new float[]{0, -2.2f, 0}, // center
                        1.2f,                      // radius
                        0.0f,                      // latitude (equator)
                        0.0f))                     // start longitude
                .build();
        scene.addEntity(sphereMesh.entity());

        // --- Render loop ---
        Renderer.ClearOptions clearOptions = new Renderer.ClearOptions();
        clearOptions.clearColor = new float[]{0.05f, 0.05f, 0.08f, 1.0f};
        clearOptions.clear = true;
        clearOptions.discard = true;
        renderer.setClearOptions(clearOptions);

        double aspect = (double) WINDOW_WIDTH / WINDOW_HEIGHT;

        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();

            // Update orbit camera
            double eyeX = radius * Math.cos(phi) * Math.sin(theta);
            double eyeY = radius * Math.sin(phi);
            double eyeZ = radius * Math.cos(phi) * Math.cos(theta);
            camera.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL);
            camera.lookAt(eyeX, eyeY, eyeZ, 0, -0.3, 0, 0, 1, 0);

            if (renderer.beginFrame(swapChain, System.nanoTime())) {
                renderer.render(view);
                renderer.endFrame();
            }
        }

        // --- Cleanup ---
        sphereMesh.destroy(engine);
        cylinderMesh.destroy(engine);
        flatMesh.destroy(engine);

        fontManager.destroy();
        textRenderer.destroy();

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
