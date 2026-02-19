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
 * Loads and renders a glTF model in a GLFW window using Filament.
 */
public class HelloGltf {

    private static final int WINDOW_WIDTH = 1024;
    private static final int WINDOW_HEIGHT = 768;

    public static void main(String[] args) throws Exception {
        // Initialize GLFW
        GLFWErrorCallback.createPrint(System.err).set();

        // Filament's Vulkan backend on Linux only supports X11 surfaces.
        glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11);

        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);

        long window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT,
                "filament-java — Hello glTF", NULL, NULL);
        if (window == NULL) {
            glfwTerminate();
            throw new RuntimeException("Failed to create GLFW window");
        }

        long nativeWindow = glfwGetX11Window(window);

        // Initialize Filament + gltfio
        Gltfio.init();

        // Create engine
        Engine engine = new Engine.Builder().backend(Engine.Backend.VULKAN).build();
        if (engine == null) {
            engine = new Engine.Builder().backend(Engine.Backend.OPENGL).build();
        }

        SwapChain swapChain = engine.createSwapChainFromRawPointer(nativeWindow, 0);
        Renderer renderer = engine.createRenderer();
        Scene scene = engine.createScene();
        View view = engine.createView();
        Camera camera = engine.createCamera(engine.getEntityManager().create());

        // Setup view
        view.setScene(scene);
        view.setCamera(camera);
        view.setViewport(new Viewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT));

        // Add a directional light
        int sunlight = EntityManager.get().create();
        new LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 1.0f, 1.0f)
                .intensity(100000.0f)
                .direction(-0.5f, -1.0f, -0.5f)
                .castShadows(true)
                .build(engine, sunlight);
        scene.addEntity(sunlight);

        // Add indirect light for ambient
        int iblEntity = EntityManager.get().create();
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

        // Load resources (textures, etc.)
        ResourceLoader resourceLoader = new ResourceLoader(engine);
        resourceLoader.loadResources(asset);
        asset.releaseSourceData();

        // Add all entities to the scene
        int[] entities = asset.getEntities();
        for (int entity : entities) {
            scene.addEntity(entity);
        }
        scene.addEntity(asset.getRoot());

        // Setup camera — perspective looking at the model
        Box boundingBox = asset.getBoundingBox();
        float[] center = boundingBox.getCenter();
        float[] halfExtent = boundingBox.getHalfExtent();
        float maxExtent = Math.max(halfExtent[0], Math.max(halfExtent[1], halfExtent[2]));

        double aspect = (double) WINDOW_WIDTH / WINDOW_HEIGHT;
        camera.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL);

        // Position camera to see the model
        float distance = maxExtent * 3.5f;
        camera.lookAt(
                center[0] + distance * 0.5, center[1] + distance * 0.3, center[2] + distance,
                center[0], center[1], center[2],
                0.0, 1.0, 0.0
        );

        // Set clear color
        Renderer.ClearOptions clearOptions = new Renderer.ClearOptions();
        clearOptions.clearColor = new float[]{0.2f, 0.2f, 0.25f, 1.0f};
        clearOptions.clear = true;
        clearOptions.discard = true;
        renderer.setClearOptions(clearOptions);

        System.out.println("Rendering glTF model... Close the window to exit.");

        // Animation setup
        Animator animator = asset.getInstance().getAnimator();
        int animationCount = animator.getAnimationCount();
        if (animationCount > 0) {
            System.out.println("Model has " + animationCount + " animation(s):");
            for (int i = 0; i < animationCount; i++) {
                System.out.println("  " + i + ": " + animator.getAnimationName(i)
                        + " (" + animator.getAnimationDuration(i) + "s)");
            }
        }

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

    private static byte[] loadResource(String path) throws IOException {
        try (InputStream in = HelloGltf.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Resource not found: " + path);
            }
            return in.readAllBytes();
        }
    }
}
